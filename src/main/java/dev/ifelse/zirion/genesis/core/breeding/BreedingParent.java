package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.content.SpeciesDefinition;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;

import java.util.Objects;
import java.util.Optional;

/** Trusted original-species classification or a hybrid, separate from ownership and ancestry percentages. */
public record BreedingParent(ContentId familyId, ContentId rigId, Optional<ContentId> speciesId, Genome genome) {
    public BreedingParent {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(rigId, "rigId");
        Objects.requireNonNull(speciesId, "speciesId");
        Objects.requireNonNull(genome, "genome");
    }

    public static BreedingParent starter(SpeciesDefinition species) {
        return new BreedingParent(species.familyId(), species.rigId(), Optional.of(species.id()), species.starterGenome());
    }

    public static BreedingParent hybrid(ContentId familyId, ContentId rigId, Genome genome) {
        return new BreedingParent(familyId, rigId, Optional.empty(), genome);
    }
}
