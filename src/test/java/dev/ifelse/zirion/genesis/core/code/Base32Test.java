package dev.ifelse.zirion.genesis.core.code;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Base32Test {
    @Test
    void rfc4648VectorsMatchWithProtocolPaddingOmitted() {
        String[] plain = {"", "f", "fo", "foo", "foob", "fooba", "foobar"};
        String[] encoded = {"", "MY", "MZXQ", "MZXW6", "MZXW6YQ", "MZXW6YTB", "MZXW6YTBOI"};
        for (int i = 0; i < plain.length; i++) {
            byte[] bytes = plain[i].getBytes(StandardCharsets.US_ASCII);
            assertEquals(encoded[i], Base32.encode(bytes));
            assertArrayEquals(bytes, Base32.decode(encoded[i], 16));
        }
    }

    @Test
    void rejectsAlternativeCaseWhitespacePaddingAlphabetAndNonzeroTailBits() {
        for (String malformed : new String[]{"my", "MY=", "MY ", "0Y", "1Y", "MZ", "A", "AAA", "AAAAAA", "МY"}) {
            assertThrows(IllegalArgumentException.class, () -> Base32.decode(malformed, 16), malformed);
        }
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZXW6", 2));
    }
}
