package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;

import java.util.Objects;

/** Registered original species and a starter template; not an individual animal or a breed. */
public record SpeciesDefinition(ContentId id, int version, ContentId familyId, ContentId rigId,
                                Genome starterGenome) {
    public SpeciesDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(rigId, "rigId");
        Objects.requireNonNull(starterGenome, "starterGenome");
        if (version < 1) throw new IllegalArgumentException("Species version must be positive");
    }
}
