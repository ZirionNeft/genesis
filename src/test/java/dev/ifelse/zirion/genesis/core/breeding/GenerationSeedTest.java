package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.lineage.SeededFrailtyRandom;
import dev.ifelse.zirion.genesis.core.mutation.SeededMutationRandom;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GenerationSeedTest {
    @Test
    void matchesIndependentPythonHashesAndJavaLcgDrawsIncludingSignedSeedExtremes() throws Exception {
        var vectors = new Properties();
        try (var input = getClass().getResourceAsStream("/generation-seed-v1-vector.properties")) {
            assertNotNull(input);
            vectors.load(input);
        }
        assertEquals(1, GenerationSeed.SCHEME_VERSION);
        assertEquals(ContentId.parse("genesis:generation_sha256_v1"), GenerationSeed.ALGORITHM);
        for (int index = 0; index < Integer.parseInt(vectors.getProperty("count")); index++) {
            String prefix = "vector." + index;
            var seed = new GenerationSeed(Long.parseLong(vectors.getProperty(prefix + ".seed")));
            assertEquals(vectors.getProperty(prefix + ".hex"), HexFormat.of().formatHex(seed.encode()));
            assertEquals(seed, GenerationSeed.decode(seed.encode()));
            for (var stream : GenerationSeed.Stream.values()) {
                String key = prefix + "." + stream.name().toLowerCase(Locale.ROOT);
                assertEquals(stream.name().toLowerCase(Locale.ROOT), stream.label());
                long derived = seed.forStream(stream);
                assertEquals(Long.parseLong(vectors.getProperty(key + ".seed")), derived);
                switch (stream) {
                    case ATTEMPT -> assertEquals(Double.parseDouble(vectors.getProperty(key + ".draw")), new Random(derived).nextDouble());
                    case INHERITANCE -> {
                        var random = new SeededAlleleRandom(derived);
                        StringBuilder bits = new StringBuilder();
                        for (int draw = 0; draw < 24; draw++) bits.append(random.chooseSecond() ? '1' : '0');
                        assertEquals(vectors.getProperty(key + ".bits"), bits.toString());
                    }
                    case MUTATION -> assertEquals(Double.parseDouble(vectors.getProperty(key + ".draw")), new SeededMutationRandom(derived).nextUnit());
                    case FRAILTY -> assertEquals(Double.parseDouble(vectors.getProperty(key + ".draw")), new SeededFrailtyRandom(derived).nextUnit());
                    case COSMETIC -> { /* The full derived i64 is the cosmetic seed. */ }
                }
            }
        }
    }

    @Test
    void decoderRejectsTruncationTrailingBytesBadMagicAndUnknownVersions() {
        byte[] encoded = new GenerationSeed(42).encode();
        for (int size = 0; size < encoded.length; size++) {
            byte[] truncated = Arrays.copyOf(encoded, size);
            assertThrows(IllegalArgumentException.class, () -> GenerationSeed.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> GenerationSeed.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        for (int offset : new int[]{0, 4, 7}) {
            byte[] changed = encoded.clone();
            changed[offset] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> GenerationSeed.decode(changed));
        }
        assertThrows(NullPointerException.class, () -> GenerationSeed.decode(null));
    }

    @Test
    void binaryCopiesCannotMutateTheSeedAndAllSixtyFourInputBitsMatter() {
        var seed = new GenerationSeed(42);
        byte[] encoded = seed.encode();
        encoded[15] = 0;
        assertEquals(42, seed.value());
        var higherBits = new GenerationSeed(42 + (1L << 48));
        for (var stream : GenerationSeed.Stream.values()) {
            assertNotEquals(seed.forStream(stream), higherBits.forStream(stream));
            // Existing Java Random truncates to 48 bits; these vectors also differ after that truncation.
            long mask = (1L << 48) - 1;
            assertNotEquals(seed.forStream(stream) & mask, higherBits.forStream(stream) & mask);
        }
    }

    @Test
    void arbitraryConsumptionOfOtherStreamsDoesNotAdvanceInheritanceOrCosmeticSeed() {
        var seed = new GenerationSeed(42);
        var expected = new SeededAlleleRandom(seed.forStream(GenerationSeed.Stream.INHERITANCE));
        var actual = new SeededAlleleRandom(seed.forStream(GenerationSeed.Stream.INHERITANCE));
        long cosmetic = seed.forStream(GenerationSeed.Stream.COSMETIC);
        var mutation = new SeededMutationRandom(seed.forStream(GenerationSeed.Stream.MUTATION));
        var frailty = new SeededFrailtyRandom(seed.forStream(GenerationSeed.Stream.FRAILTY));
        var attempt = new Random(seed.forStream(GenerationSeed.Stream.ATTEMPT));
        var distinct = new HashSet<Long>();
        for (var stream : GenerationSeed.Stream.values()) assertTrue(distinct.add(seed.forStream(stream)));
        for (int index = 0; index < 1000; index++) {
            mutation.nextUnit();
            mutation.nextIndex(97);
            frailty.nextUnit();
            attempt.nextDouble();
            assertEquals(expected.chooseSecond(), actual.chooseSecond());
            assertEquals(cosmetic, seed.forStream(GenerationSeed.Stream.COSMETIC));
        }
    }
}
