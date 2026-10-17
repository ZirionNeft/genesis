package dev.ifelse.zirion.genesis.core.mutation;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Locus;

import java.util.Objects;
import java.util.Set;

/** An explicit target allele and allowed pre-mutation pairs; not an extra inherited genome flag. */
public record MutationDefinition(ContentId id, MutationCategory category, Locus locus, ContentId targetAllele,
                                 Impact impact, Set<AllelePair> fromPairs) {
    public enum Impact { COSMETIC, BENEFICIAL, TRADEOFF, HARMFUL }

    public MutationDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(locus, "locus");
        Objects.requireNonNull(targetAllele, "targetAllele");
        Objects.requireNonNull(impact, "impact");
        fromPairs = Set.copyOf(fromPairs);
        if (fromPairs.isEmpty()) throw new IllegalArgumentException("Mutation requires explicit source pairs");
        if ((category == MutationCategory.COSMETIC) != (impact == Impact.COSMETIC)) {
            throw new IllegalArgumentException("Only cosmetic mutations use cosmetic impact");
        }
        for (AllelePair pair : fromPairs) {
            if (pair.first().equals(targetAllele) && pair.second().equals(targetAllele)) {
                throw new IllegalArgumentException("Source pair cannot already contain two target copies");
            }
        }
    }
}
