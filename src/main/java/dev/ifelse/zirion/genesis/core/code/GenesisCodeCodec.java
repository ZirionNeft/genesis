package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Strict canonical wire v1, separate from definition resolution, migrations and import permissions. */
public final class GenesisCodeCodec {
    public static final int WIRE_VERSION = 1;
    public static final int GENOME_SCHEMA_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 65_536;
    public static final int MAX_ID_BYTES = 256;
    public static final int MAX_DICTIONARY = 128;
    public static final int MAX_CODE_LENGTH = 5 + (MAX_PAYLOAD_BYTES * 8 + 4) / 5 + 1 + 16;

    private GenesisCodeCodec() {}

    public static String encode(GenesisBlueprint blueprint) {
        byte[] payload = encodePayload(blueprint);
        return "GEN1-" + Base32.encode(payload) + "-" + checksum(payload);
    }

    /** Reading preserves unknown IDs and extension bytes; success does not authorize their use. */
    public static GenesisBlueprint decode(String code) {
        Objects.requireNonNull(code, "code");
        if (code.length() > MAX_CODE_LENGTH) throw new IllegalArgumentException("Genesis Code exceeds limit");
        if (!code.startsWith("GEN1-")) throw new IllegalArgumentException("Unsupported Genesis Code prefix");
        int separator = code.lastIndexOf('-');
        if (separator <= 5 || code.length() - separator != 17) throw new IllegalArgumentException("Invalid Genesis Code structure");
        byte[] payload = Base32.decode(code.substring(5, separator), MAX_PAYLOAD_BYTES);
        if (!checksum(payload).equals(code.substring(separator + 1))) throw new IllegalArgumentException("Genesis Code checksum mismatch");
        return decodePayload(payload);
    }

    public static byte[] encodePayload(GenesisBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        TreeSet<ContentId> ids = new TreeSet<>();
        List<RulesetVersion> references = List.of(blueprint.genome().ruleset(), blueprint.family(),
                blueprint.rig(), blueprint.archetype(), blueprint.assetSet());
        references.forEach(reference -> ids.add(reference.id()));
        blueprint.genome().loci().values().forEach(pair -> { ids.add(pair.first()); ids.add(pair.second()); });
        blueprint.extensions().forEach(extension -> ids.add(extension.key().extension().id()));
        if (ids.size() > MAX_DICTIONARY) throw new IllegalArgumentException("ID dictionary exceeds limit");
        long size = 94; // Header/counts, five references, twelve pairs and cosmetic seed.
        for (ContentId id : ids) {
            int length = id.toString().length(); // ContentId permits ASCII only.
            if (length > MAX_ID_BYTES) throw new IllegalArgumentException("Content ID exceeds wire limit");
            size += 2 + length;
        }
        for (ExtensionPayload extension : blueprint.extensions()) size += 15 + extension.payload().size();
        if (size > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Binary payload exceeds limit");
        Map<ContentId, Integer> dictionary = new HashMap<>();
        int index = 0;
        for (ContentId id : ids) dictionary.put(id, index++);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(WIRE_VERSION);
            output.writeShort(GENOME_SCHEMA_VERSION);
            output.writeShort(ids.size());
            for (ContentId id : ids) {
                byte[] text = id.toString().getBytes(StandardCharsets.US_ASCII);
                output.writeShort(text.length);
                output.write(text);
            }
            for (RulesetVersion reference : references) writeReference(output, dictionary, reference);
            for (Locus locus : Locus.values()) {
                AllelePair pair = blueprint.genome().loci().get(locus);
                output.writeShort(dictionary.get(pair.first()));
                output.writeShort(dictionary.get(pair.second()));
            }
            output.writeLong(blueprint.cosmeticSeed());
            output.writeShort(blueprint.extensions().size());
            for (ExtensionPayload extension : blueprint.extensions()) {
                writeReference(output, dictionary, extension.key().extension());
                output.writeInt(extension.key().schemaVersion());
                output.writeByte(extension.kind() == ExtensionPayload.Kind.REQUIRED ? 0 : 1);
                output.writeInt(extension.payload().size());
                output.write(extension.payload().copy());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("In-memory payload encoding failed", exception);
        }
    }

    public static GenesisBlueprint decodePayload(byte[] source) {
        Objects.requireNonNull(source, "payload");
        if (source.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Binary payload exceeds limit");
        byte[] payload = source.clone();
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readUnsignedShort() != WIRE_VERSION || input.readUnsignedShort() != GENOME_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported wire or genome schema version");
            }
            int count = input.readUnsignedShort();
            if (count == 0 || count > MAX_DICTIONARY) throw new IllegalArgumentException("Invalid dictionary size");
            List<ContentId> dictionary = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = input.readUnsignedShort();
                if (length == 0 || length > MAX_ID_BYTES || length > input.available()) throw new IllegalArgumentException("Invalid ID length");
                byte[] text = input.readNBytes(length);
                for (byte value : text) if (value < 0) throw new IllegalArgumentException("Non-ASCII content ID");
                ContentId id = ContentId.parse(new String(text, StandardCharsets.US_ASCII));
                if (!dictionary.isEmpty() && dictionary.getLast().compareTo(id) >= 0) throw new IllegalArgumentException("Dictionary is not canonical");
                dictionary.add(id);
            }
            RulesetVersion genetics = readReference(input, dictionary);
            RulesetVersion family = readReference(input, dictionary);
            RulesetVersion rig = readReference(input, dictionary);
            RulesetVersion archetype = readReference(input, dictionary);
            RulesetVersion assets = readReference(input, dictionary);
            EnumMap<Locus, AllelePair> pairs = new EnumMap<>(Locus.class);
            for (Locus locus : Locus.values()) pairs.put(locus, new AllelePair(readId(input, dictionary), readId(input, dictionary)));
            long seed = input.readLong();
            int extensions = input.readUnsignedShort();
            if (extensions > GenesisBlueprint.MAX_EXTENSIONS) throw new IllegalArgumentException("Extension count exceeds limit");
            List<ExtensionPayload> data = new ArrayList<>(extensions);
            for (int i = 0; i < extensions; i++) {
                RulesetVersion extension = readReference(input, dictionary);
                int schema = input.readInt();
                int kind = input.readUnsignedByte();
                if (kind > 1) throw new IllegalArgumentException("Unknown extension kind");
                int length = input.readInt();
                if (length < 0 || length > ExtensionPayload.MAX_BYTES || length > input.available()) throw new IllegalArgumentException("Invalid extension length");
                data.add(new ExtensionPayload(new ExtensionPayload.Key(extension, schema), kind == 0 ? ExtensionPayload.Kind.REQUIRED
                        : ExtensionPayload.Kind.OPTIONAL_COSMETIC, new ImmutableBytes(input.readNBytes(length))));
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing payload bytes");
            GenesisBlueprint blueprint = new GenesisBlueprint(new Genome(genetics, pairs), family, rig, archetype, assets, seed, data);
            // Reject unused IDs, unsorted allele pairs/extensions and any alternate representation.
            if (!Arrays.equals(payload, encodePayload(blueprint))) throw new IllegalArgumentException("Noncanonical binary payload");
            return blueprint;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Truncated Genesis Code payload", exception);
        }
    }

    private static void writeReference(DataOutputStream output, Map<ContentId, Integer> dictionary, RulesetVersion reference) throws IOException {
        output.writeShort(dictionary.get(reference.id()));
        output.writeInt(reference.version());
    }

    private static ContentId readId(DataInputStream input, List<ContentId> dictionary) throws IOException {
        int index = input.readUnsignedShort();
        if (index >= dictionary.size()) throw new IllegalArgumentException("Invalid dictionary reference");
        return dictionary.get(index);
    }

    private static RulesetVersion readReference(DataInputStream input, List<ContentId> dictionary) throws IOException {
        return new RulesetVersion(readId(input, dictionary), input.readInt());
    }

    private static String checksum(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().withUpperCase().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
