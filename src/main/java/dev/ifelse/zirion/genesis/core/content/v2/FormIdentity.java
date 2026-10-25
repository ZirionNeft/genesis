package dev.ifelse.zirion.genesis.core.content.v2;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;

import java.util.Objects;

/** Persistable historical identity. Genetic support is derived from a validated genome, never stored here. */
public record FormIdentity(RegistryFingerprint hybridGraph, ContentId historicalForm) {
    public FormIdentity {
        Objects.requireNonNull(hybridGraph, "hybridGraph");
        Objects.requireNonNull(historicalForm, "historicalForm");
        if (hybridGraph.reference().version() != 1) throw new IllegalArgumentException("Only hybrid graph v1 is supported");
    }
}
