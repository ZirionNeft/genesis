package dev.ifelse.zirion.genesis.core.code;

import java.io.ByteArrayOutputStream;

/** RFC 4648 alphabet; this protocol explicitly omits '=' and rejects noncanonical encodings. */
final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    static String encode(byte[] bytes) {
        StringBuilder output = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                output.append(ALPHABET.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) output.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return output.toString();
    }

    static byte[] decode(String encoded, int maxBytes) {
        if ((long) encoded.length() * 5 / 8 > maxBytes) throw new IllegalArgumentException("Base32 size limit exceeded");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int value = ALPHABET.indexOf(encoded.charAt(i));
            if (value < 0) throw new IllegalArgumentException("Invalid Base32 character");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((buffer >>> bits) & 255);
            }
        }
        byte[] result = bytes.toByteArray();
        if (!encode(result).equals(encoded)) throw new IllegalArgumentException("Noncanonical Base32 length or tail bits");
        return result;
    }
}
