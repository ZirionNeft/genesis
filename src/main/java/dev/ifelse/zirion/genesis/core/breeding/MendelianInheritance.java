package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Pure inheritance, conditional on an already successful and eligible breeding attempt. No mutations. */
public final class MendelianInheritance {
    public static final int ALGORITHM_VERSION = 1;

    private MendelianInheritance() {
    }

    public static Genome inherit(Genome parentA, Genome parentB, GeneticsRuleset ruleset, AlleleRandom random) {
        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(random, "random");
        // Validate both entire parents before consuming even one random choice.
        ruleset.validate(parentA);
        ruleset.validate(parentB);
        EnumMap<Locus, AllelePair> child = new EnumMap<>(Locus.class);
        for (Locus locus : Locus.values()) {
            AllelePair a = parentA.loci().get(locus);
            AllelePair b = parentB.loci().get(locus);
            child.put(locus, new AllelePair(random.chooseSecond() ? a.second() : a.first(),
                    random.chooseSecond() ? b.second() : b.first()));
        }
        return new Genome(ruleset.reference(), child);
    }

    /** Exact single-locus distribution: all four parental-copy combinations have weight 1/4. */
    public static Map<AllelePair, Double> distribution(AllelePair parentA, AllelePair parentB) {
        Objects.requireNonNull(parentA, "parentA");
        Objects.requireNonNull(parentB, "parentB");
        Map<AllelePair, Double> result = new HashMap<>();
        for (var a : new ContentId[]{parentA.first(), parentA.second()}) {
            for (var b : new ContentId[]{parentB.first(), parentB.second()}) {
                result.merge(new AllelePair(a, b), 0.25, Double::sum);
            }
        }
        return Map.copyOf(result);
    }
}
