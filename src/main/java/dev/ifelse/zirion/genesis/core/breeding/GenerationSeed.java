package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Internal replay contract. Stream labels and the binary preimage are fixed by scheme version 1. */
public record GenerationSeed(long value) {
    public static final int SCHEME_VERSION = 1;
    public static final ContentId ALGORITHM = ContentId.parse("genesis:generation_sha256_v1");
    public static final int ENCODED_BYTES = 16;
    private static final int MAGIC = 0x47454E53; // ASCII GENS
    private static final byte[] DOMAIN = "genesis:rng\0".getBytes(StandardCharsets.US_ASCII);

    public enum Stream {
        ATTEMPT("attempt"), INHERITANCE("inheritance"), MUTATION("mutation"),
        FRAILTY("frailty"), COSMETIC("cosmetic");

        private final String label;
        Stream(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Big-endian GENS magic, i32 scheme version, i64 generation seed. All 64 seed bits are retained. */
    public byte[] encode() {
        return ByteBuffer.allocate(ENCODED_BYTES).putInt(MAGIC).putInt(SCHEME_VERSION).putLong(value).array();
    }

    /** Exact-length canonical decoding; unknown schemes never silently fall back to the current one. */
    public static GenerationSeed decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != ENCODED_BYTES) throw new IllegalArgumentException("Invalid generation seed length");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IllegalArgumentException("Invalid generation seed magic");
        if (buffer.getInt() != SCHEME_VERSION) throw new IllegalArgumentException("Unsupported generation seed scheme");
        return new GenerationSeed(buffer.getLong());
    }

    /** First eight SHA-256 bytes, interpreted as a signed big-endian i64, without ordinal/hashCode use. */
    public long forStream(Stream stream) {
        Objects.requireNonNull(stream, "stream");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update(encode());
            digest.update(stream.label.getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.wrap(Arrays.copyOf(digest.digest(), Long.BYTES)).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
