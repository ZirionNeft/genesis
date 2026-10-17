package dev.ifelse.zirion.genesis.core.genetics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable MVP genotype, with no NPC, owner, age or phenotype state. Not a Genesis Code codec. */
public record Genome(RulesetVersion ruleset, Map<Locus, AllelePair> loci) {
    public Genome {
        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(loci, "loci");
        EnumMap<Locus, AllelePair> copy = new EnumMap<>(Locus.class);
        copy.putAll(loci);
        for (Locus locus : Locus.values()) {
            if (copy.get(locus) == null) throw new IllegalArgumentException("Missing allele pair at " + locus);
        }
        loci = Collections.unmodifiableMap(copy);
    }
}
