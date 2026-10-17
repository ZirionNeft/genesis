package dev.ifelse.zirion.genesis.core.code;

import java.util.Arrays;
import java.util.Objects;

/** Value equality and defensive copies for opaque extension data. */
public final class ImmutableBytes {
    private final byte[] value;

    public ImmutableBytes(byte[] value) { this.value = Objects.requireNonNull(value, "bytes").clone(); }
    public byte[] copy() { return value.clone(); }
    public int size() { return value.length; }

    @Override
    public boolean equals(Object other) { return other instanceof ImmutableBytes bytes && Arrays.equals(value, bytes.value); }

    @Override
    public int hashCode() { return Arrays.hashCode(value); }

    @Override
    public String toString() { return "ImmutableBytes[size=" + value.length + "]"; }
}
