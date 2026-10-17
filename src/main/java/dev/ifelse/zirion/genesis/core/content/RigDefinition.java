package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Objects;

/** Semantic rig identity; asset coverage must be verified by the morphology adapter separately. */
public record RigDefinition(ContentId id, int version) {
    public RigDefinition {
        Objects.requireNonNull(id, "id");
        if (version < 1) throw new IllegalArgumentException("Rig version must be positive");
    }
}
