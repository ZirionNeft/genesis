package dev.ifelse.zirion.genesis.core.code.v2;

import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;

/** Closed payload schema 2 over GEN1 transport. Structural decoding preserves unresolved pins/opaque bytes. */
public final class GenesisCodeCodec {
    public static final int PAYLOAD_VERSION = 2;
    private final ServerConfig.CodeLimits limits;

    public GenesisCodeCodec(ServerConfig.CodeLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public String encode(GenesisBlueprint blueprint) {
        byte[] payload = encodePayload(blueprint);
        String result = "GEN1." + base64(payload) + "." + crc32(payload);
        if (result.length() > limits.maxCodeChars()) throw invalid("GEN1 code exceeds character limit");
        return result;
    }

    public GenesisBlueprint decode(String code) {
        Objects.requireNonNull(code);
        if (code.length() > limits.maxCodeChars()) throw invalid("GEN1 code exceeds character limit");
        if (!code.startsWith("GEN1.")) throw invalid("Unsupported GEN1 transport");
        int last = code.lastIndexOf('.');
        if (last <= 5 || last != code.indexOf('.', 5) || code.length() - last != 9) throw invalid("Invalid GEN1 structure");
        String checksum = code.substring(last + 1);
        if (!checksum.matches("[0-9A-F]{8}")) throw invalid("Invalid CRC32 representation");
        byte[] payload = decodeBase64(code.substring(5, last), limits.maxPayloadBytes());
        if (!crc32(payload).equals(checksum)) throw invalid("GEN1 checksum mismatch");
        return decodePayload(payload);
    }

    public byte[] encodePayload(GenesisBlueprint blueprint) {
        Objects.requireNonNull(blueprint);
        if (blueprint.genome().genes().size() > limits.maxGenes() || blueprint.extensions().size() > limits.maxExtensions()) {
            throw invalid("GEN1 collection exceeds configured limit");
        }
        var refs = blueprint.references(); var genes = new TreeMap<String, Object>();
        blueprint.genome().genes().forEach((id, pair) -> genes.put(id.toString(), List.of(pair.first().toString(), pair.second().toString())));
        var extensions = new ArrayList<Object>();
        for (var extension : blueprint.extensions()) {
            if (extension.payload().size() > limits.maxPayloadBytes()) throw invalid("GEN1 extension exceeds payload limit");
            extensions.add(Map.of(
                "definition", pin(extension.definition()), "schemaVersion", extension.schemaVersion(),
                "kind", extension.kind() == ExtensionPayload.Kind.REQUIRED ? "required" : "optional_cosmetic",
                "payload", base64(extension.payload().copy())));
        }
        return PayloadJson.write(Map.of(
                "payloadVersion", PAYLOAD_VERSION,
                "registries", Map.of("geneSchema", pin(refs.geneSchema()), "hybridGraph", pin(refs.hybridGraph()),
                        "expressionRules", pin(refs.expression()), "morphologySet", pin(refs.morphology())),
                "contentCatalog", Map.of("id", refs.contentCatalogId().toString(), "sha256", refs.contentCatalogSha256()),
                "familyId", blueprint.genome().familyId().toString(), "historicalForm", blueprint.historicalForm().toString(),
                "genes", genes, "visualSeed", Long.toString(blueprint.visualSeed()), "extensions", extensions), limits.maxPayloadBytes());
    }

    /** No catalog resolution, permissions, analysis check, asset validation or side effects occur here. */
    public GenesisBlueprint decodePayload(byte[] source) {
        Objects.requireNonNull(source);
        if (source.length > limits.maxPayloadBytes()) throw invalid("GEN1 payload exceeds byte limit");
        byte[] payload = source.clone();
        var root = object(PayloadJson.read(payload), Set.of("payloadVersion", "registries", "contentCatalog", "familyId",
                "historicalForm", "genes", "visualSeed", "extensions"));
        if (integer(root.get("payloadVersion")) != PAYLOAD_VERSION) throw invalid("Unsupported GEN1 payload schema");
        var registries = object(root.get("registries"), Set.of("geneSchema", "hybridGraph", "expressionRules", "morphologySet"));
        var content = object(root.get("contentCatalog"), Set.of("id", "sha256"));
        var refs = new ExpressionRules.References(readPin(registries.get("expressionRules")), readPin(registries.get("geneSchema")),
                readPin(registries.get("hybridGraph")), readPin(registries.get("morphologySet")), id(content.get("id")), string(content.get("sha256")));
        var genes = object(root.get("genes"), null);
        if (genes.size() > limits.maxGenes()) throw invalid("Too many GEN1 genes");
        var pairs = new TreeMap<ContentId, AllelePair>();
        genes.forEach((gene, pair) -> {
            var values = array(pair); if (values.size() != 2) throw invalid("Expected diploid gene pair");
            pairs.put(ContentId.parse(gene), new AllelePair(id(values.get(0)), id(values.get(1))));
        });
        var rawExtensions = array(root.get("extensions"));
        if (rawExtensions.size() > limits.maxExtensions()) throw invalid("Too many GEN1 extensions");
        var extensions = new ArrayList<GenesisBlueprint.OpaqueExtension>();
        for (var raw : rawExtensions) {
            var extension = object(raw, Set.of("definition", "schemaVersion", "kind", "payload"));
            var kind = switch (string(extension.get("kind"))) {
                case "required" -> ExtensionPayload.Kind.REQUIRED; case "optional_cosmetic" -> ExtensionPayload.Kind.OPTIONAL_COSMETIC;
                default -> throw invalid("Unknown GEN1 extension kind");
            };
            extensions.add(new GenesisBlueprint.OpaqueExtension(readPin(extension.get("definition")), integer(extension.get("schemaVersion")), kind,
                    new ImmutableBytes(decodeBase64(string(extension.get("payload")), limits.maxPayloadBytes()))));
        }
        String seedText = string(root.get("visualSeed"));
        if (!seedText.matches("-?(0|[1-9][0-9]*)")) throw invalid("Invalid visual seed");
        long seed;
        try { seed = Long.parseLong(seedText); } catch (NumberFormatException exception) { throw invalid("Visual seed outside signed 64-bit range"); }
        var result = new GenesisBlueprint(refs, id(root.get("historicalForm")), new Genome(refs.geneSchema(), id(root.get("familyId")), pairs), seed, extensions);
        if (!Arrays.equals(payload, encodePayload(result))) throw invalid("Noncanonical GEN1 payload");
        return result;
    }

    private static Map<String, Object> pin(RegistryFingerprint pin) {
        return Map.of("id", pin.reference().id().toString(), "version", pin.reference().version(), "sha256", pin.sha256());
    }

    private static RegistryFingerprint readPin(Object value) {
        var pin = object(value, Set.of("id", "version", "sha256"));
        return new RegistryFingerprint(new RulesetVersion(id(pin.get("id")), integer(pin.get("version"))), string(pin.get("sha256")));
    }

    private static Map<String, Object> object(Object value, Set<String> keys) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Expected GEN1 object");
        var result = new TreeMap<String, Object>();
        map.forEach((key, item) -> { if (!(key instanceof String text)) throw invalid("Invalid GEN1 key"); result.put(text, item); });
        if (keys != null && !result.keySet().equals(keys)) throw invalid("Missing or unknown GEN1 fields");
        return result;
    }

    private static List<?> array(Object value) { if (value instanceof List<?> list) return list; throw invalid("Expected GEN1 array"); }
    private static String string(Object value) { if (value instanceof String text) return text; throw invalid("Expected GEN1 string"); }
    private static int integer(Object value) { if (value instanceof Integer number) return number; throw invalid("Expected GEN1 int32"); }
    private static ContentId id(Object value) { return ContentId.parse(string(value)); }
    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    private static byte[] decodeBase64(String encoded, int maxBytes) {
        if (!encoded.matches("[A-Za-z0-9_-]*") || encoded.length() % 4 == 1
                || ((long) encoded.length() * 3 / 4) > maxBytes) throw invalid("Invalid or oversized Base64url");
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(encoded); } catch (IllegalArgumentException exception) { throw invalid("Invalid Base64url"); }
        if (bytes.length > maxBytes || !base64(bytes).equals(encoded)) throw invalid("Noncanonical Base64url");
        return bytes;
    }

    private static String crc32(byte[] payload) { var crc = new CRC32(); crc.update(payload); return String.format(Locale.ROOT, "%08X", crc.getValue()); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
