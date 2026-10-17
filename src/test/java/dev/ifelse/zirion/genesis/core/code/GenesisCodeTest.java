package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

import static dev.ifelse.zirion.genesis.core.code.CodeFixtures.*;
import static dev.ifelse.zirion.genesis.core.code.GenesisCodeCodec.*;
import static org.junit.jupiter.api.Assertions.*;

class GenesisCodeTest {
    private static String wrap(byte[] payload) throws Exception {
        return "GEN1-" + Base32.encode(payload) + "-" + HexFormat.of().withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(payload), 0, 8);
    }

    private static int dictionaryEnd(byte[] payload) {
        ByteBuffer input = ByteBuffer.wrap(payload);
        input.position(4);
        int count = Short.toUnsignedInt(input.getShort());
        for (int i = 0; i < count; i++) { int length = Short.toUnsignedInt(input.getShort()); input.position(input.position() + length); }
        return input.position();
    }

    @Test
    void goldenPayloadAndCodeMatchAnIndependentPythonImplementation() throws IOException {
        Properties vector = new Properties();
        try (var source = getClass().getResourceAsStream("/genesis-code-v1-vector.properties")) {
            assertNotNull(source);
            vector.load(source);
        }
        assertEquals(vector.getProperty("payloadHex"), HexFormat.of().formatHex(encodePayload(vector())));
        assertEquals(vector.getProperty("code"), encode(vector()));
        assertEquals(vector(), decode(vector.getProperty("code")));
    }

    @Test
    void canidBlueprintAndEveryCosmeticSeedBoundaryRoundTripWithoutInstanceState() {
        var source = context(1, Set.of()).blueprint();
        for (long seed : new long[]{Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            var blueprint = new GenesisBlueprint(source.genome(), source.family(), source.rig(), source.archetype(), source.assetSet(), seed, List.of());
            assertEquals(blueprint, decode(encode(blueprint)));
            assertEquals(blueprint, decodePayload(encodePayload(blueprint)));
        }
    }

    @Test
    void orderingAndCanonicalPairsCannotChangeCodeOrOpaqueData() {
        var source = vector();
        var loci = new EnumMap<Locus, AllelePair>(Locus.class);
        source.genome().loci().forEach((locus, pair) -> loci.put(locus, new AllelePair(pair.second(), pair.first())));
        var other = extension("v-a:extra", 1, 1, ExtensionPayload.Kind.OPTIONAL_COSMETIC, new byte[]{-1, 0, -1});
        var first = new GenesisBlueprint(new Genome(source.genome().ruleset(), loci), source.family(), source.rig(), source.archetype(),
                source.assetSet(), source.cosmeticSeed(), List.of(source.extensions().getFirst(), other));
        var second = withExtensions(first, List.of(other, source.extensions().getFirst()));
        assertEquals(encode(first), encode(second));
        assertEquals(first, decode(encode(first)));
        var decoded = decode(encode(first));
        assertEquals(List.of(ContentId.parse("v:x"), ContentId.parse("v-a:extra")),
                decoded.extensions().stream().map(data -> data.key().extension().id()).toList());
        assertArrayEquals(new byte[]{-1, 0, -1}, decoded.extensions().getLast().payload().copy());
    }

    @Test
    void decodedUnknownDefinitionsAndSchemasArePreservedByteForByte() {
        var source = vector();
        var unknown = extension("absent:opaque", 777, 999, ExtensionPayload.Kind.REQUIRED, new byte[]{0, -1, 10, 13, 0, -128});
        var blueprint = withExtensions(source, List.of(unknown));
        var code = encode(blueprint);
        var decoded = decode(code);
        assertEquals(blueprint, decoded);
        assertEquals(code, encode(decoded));
        byte[] bytes = decoded.extensions().getFirst().payload().copy();
        bytes[0] = 42;
        assertEquals(code, encode(decoded));
    }

    @Test
    void damagedCodeAndNoncanonicalTextAreRejected() {
        String code = encode(vector());
        char altered = code.charAt(30) == 'A' ? 'B' : 'A';
        String damaged = code.substring(0, 30) + altered + code.substring(31);
        for (String invalid : List.of(damaged, code.toLowerCase(Locale.ROOT), " " + code, code + "\n", code.replaceFirst("GEN1", "GEN2"),
                code + "A", "GEN1--0000000000000000", "A".repeat(MAX_CODE_LENGTH + 1))) {
            assertThrows(IllegalArgumentException.class, () -> decode(invalid));
        }
    }

    @Test
    void everyTruncatedBinaryPrefixIsRejectedAndTrailingBytesAreNotIgnored() {
        byte[] payload = encodePayload(vector());
        for (int length = 0; length < payload.length; length++) {
            byte[] truncated = Arrays.copyOf(payload, length);
            assertThrows(IllegalArgumentException.class, () -> decodePayload(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> decodePayload(Arrays.copyOf(payload, payload.length + 1)));
    }

    @Test
    void validChecksumsDoNotMakeUnsupportedOrMalformedBinaryAcceptable() throws Exception {
        byte[] original = encodePayload(vector());
        int end = dictionaryEnd(original);
        int extension = end + 88;
        List<byte[]> malformed = new ArrayList<>();
        int[][] patches = {{1, 2}, {3, 2}, {4, 1}, {6, 2}, {8, 255}, {extension + 10, 2}};
        for (int[] patch : patches) {
            byte[] bytes = original.clone(); bytes[patch[0]] = (byte) patch[1]; malformed.add(bytes);
        }
        for (int offset : new int[]{end + 2, extension + 2, extension + 6}) {
            byte[] bytes = original.clone(); ByteBuffer.wrap(bytes).putInt(offset, 0); malformed.add(bytes);
        }
        for (int length : new int[]{-1, ExtensionPayload.MAX_BYTES + 1}) {
            byte[] bytes = original.clone(); ByteBuffer.wrap(bytes).putInt(extension + 11, length); malformed.add(bytes);
        }
        byte[] badReference = original.clone(); ByteBuffer.wrap(badReference).putShort(end, (short) 65_535); malformed.add(badReference);
        byte[] tooMany = original.clone(); ByteBuffer.wrap(tooMany).putShort(end + 86, (short) 65); malformed.add(tooMany);
        for (byte[] payload : malformed) {
            String code = wrap(payload);
            assertThrows(IllegalArgumentException.class, () -> decode(code));
        }
    }

    @Test
    void unsortedPairsAndUnusedDictionaryEntriesCannotBeSilentlyCanonicalized() {
        byte[] original = encodePayload(vector());
        int end = dictionaryEnd(original);
        byte[] unsorted = original.clone();
        ByteBuffer pairs = ByteBuffer.wrap(unsorted);
        short a = pairs.getShort(end + 30), b = pairs.getShort(end + 32);
        pairs.putShort(end + 30, b).putShort(end + 32, a);
        assertThrows(IllegalArgumentException.class, () -> decodePayload(unsorted));
        byte[] unused = "z:unused".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer added = ByteBuffer.allocate(original.length + unused.length + 2);
        added.put(original, 0, end).putShort((short) unused.length).put(unused).put(original, end, original.length - end);
        added.putShort(4, (short) (ByteBuffer.wrap(original).getShort(4) + 1));
        assertThrows(IllegalArgumentException.class, () -> decodePayload(added.array()));
        byte[] duplicate = original.clone(); duplicate[19] = '0'; // v:a01 -> v:a00, equal dictionary entries.
        assertThrows(IllegalArgumentException.class, () -> decodePayload(duplicate));
    }

    @Test
    void payloadsAreImmutableValuesAndDuplicateExtensionIdsAreRejected() {
        byte[] source = {1, 2, 3};
        var bytes = new ImmutableBytes(source);
        source[0] = 9;
        bytes.copy()[1] = 9;
        assertEquals(new ImmutableBytes(new byte[]{1, 2, 3}), bytes);
        assertEquals(new ImmutableBytes(new byte[]{1, 2, 3}).hashCode(), bytes.hashCode());
        var ext = vector().extensions().getFirst();
        assertThrows(IllegalArgumentException.class, () -> withExtensions(vector(), List.of(ext, ext)));
        var otherVersion = extension("v:x", 3, 1, ext.kind(), new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> withExtensions(vector(), List.of(ext, otherVersion)));
        assertThrows(UnsupportedOperationException.class, () -> vector().extensions().clear());
    }

    @Test
    void limitsAreEnforcedBeforeWritingOrReadingOversizedContent() {
        var source = vector();
        assertThrows(IllegalArgumentException.class, () -> extension("v:big", 1, 1, ExtensionPayload.Kind.REQUIRED, new byte[ExtensionPayload.MAX_BYTES + 1]));
        var data = new ArrayList<ExtensionPayload>();
        for (int i = 0; i < 65; i++) data.add(extension("v:x" + i, 1, 1, ExtensionPayload.Kind.REQUIRED, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> withExtensions(source, data));
        var longId = new GenesisBlueprint(new Genome(ref("v:" + "a".repeat(256), 1), source.genome().loci()),
                source.family(), source.rig(), source.archetype(), source.assetSet(), 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> encode(longId));
        assertThrows(IllegalArgumentException.class, () -> decodePayload(new byte[MAX_PAYLOAD_BYTES + 1]));
    }

    @Test
    void theMaximumBinarySizeRoundTripsAndOneExtraByteIsRejected() {
        var source = vector();
        var empty = new ArrayList<ExtensionPayload>();
        for (int i = 0; i < 4; i++) empty.add(extension("v:x" + i, 1, 1, ExtensionPayload.Kind.REQUIRED, new byte[0]));
        int overhead = encodePayload(withExtensions(source, empty)).length;
        var filled = new ArrayList<ExtensionPayload>();
        for (int i = 0; i < 4; i++) {
            int length = i < 3 ? ExtensionPayload.MAX_BYTES : MAX_PAYLOAD_BYTES - overhead - 3 * ExtensionPayload.MAX_BYTES;
            filled.add(extension("v:x" + i, 1, 1, ExtensionPayload.Kind.REQUIRED, new byte[length]));
        }
        var maximum = withExtensions(source, filled);
        String code = encode(maximum);
        assertEquals(MAX_PAYLOAD_BYTES, encodePayload(maximum).length);
        assertEquals(MAX_CODE_LENGTH, code.length());
        assertEquals(maximum, decode(code));
        var last = filled.getLast();
        filled.set(3, new ExtensionPayload(last.key(), last.kind(), new ImmutableBytes(new byte[last.payload().size() + 1])));
        assertThrows(IllegalArgumentException.class, () -> encode(withExtensions(source, filled)));
    }

    @Test
    void oneThousandBinaryMutationsEitherRejectOrRemainExactlyCanonical() {
        byte[] original = encodePayload(vector());
        Random random = new Random(0x47454e31434f4445L);
        for (int attempt = 0; attempt < 1_000; attempt++) {
            byte[] damaged = original.clone();
            damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            try { assertArrayEquals(damaged, encodePayload(decodePayload(damaged))); }
            catch (IllegalArgumentException expected) { /* Invalid structures may be preserved only outside wire v1. */ }
        }
    }
}
