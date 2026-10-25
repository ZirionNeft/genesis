package dev.ifelse.zirion.genesis.core.genetics;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact registry identity. A digest is neither content validation nor proof of provenance. */
public record RegistryFingerprint(RulesetVersion reference, String sha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public RegistryFingerprint {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Expected a lowercase SHA-256 digest");
        }
    }
}
