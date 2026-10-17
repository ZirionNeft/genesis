package dev.ifelse.zirion.genesis.core.mutation;

import dev.ifelse.zirion.genesis.core.genetics.AlleleDefinition;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.ifelse.zirion.genesis.core.mutation.MutationDefinition.Impact;

/** Pure one-shot de novo step, after inheritance for an eligible successful birth. Not a transaction. */
public final class MutationRules {
    public static final int ALGORITHM_VERSION = 1;

    public sealed interface Result permits Unchanged, Skipped, Mutated {
        Genome genome();
    }

    public record Unchanged(Genome genome) implements Result {}
    public enum SkipReason { CATEGORY_UNAVAILABLE, IMPACT_UNAVAILABLE, GENOTYPE_UNSUPPORTED }
    public record Skipped(Genome genome, MutationCategory category, Impact impact, SkipReason reason) implements Result {}
    /** copyIndex identifies the canonical input pair, not parent-of-origin or the sorted output pair. */
    public record Change(ContentId mutationId, MutationCategory category, Impact impact, Locus locus,
                         int copyIndex, ContentId previousAllele, ContentId newAllele) {}
    public record Mutated(Genome genome, Change change) implements Result {}

    private record TargetKey(MutationCategory category, Locus locus, ContentId allele) {}

    private final RulesetVersion reference;
    private final GeneticsRuleset genetics;
    private final Map<MutationCategory, Set<Locus>> allowedLoci;
    private final List<MutationDefinition> definitions;

    /** All source/target alleles must already belong to a complete frozen genetics ruleset. */
    public MutationRules(RulesetVersion reference, GeneticsRuleset genetics,
                         Map<MutationCategory, Set<Locus>> allowedLoci, Collection<MutationDefinition> definitions) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.genetics = Objects.requireNonNull(genetics, "genetics");
        EnumMap<MutationCategory, Set<Locus>> loci = new EnumMap<>(MutationCategory.class);
        for (MutationCategory category : MutationCategory.values()) {
            Set<Locus> allowed = allowedLoci.get(category);
            if (allowed == null) throw new IllegalArgumentException("Missing explicit locus allowlist: " + category);
            loci.put(category, Set.copyOf(allowed));
        }
        this.allowedLoci = Collections.unmodifiableMap(loci);
        this.definitions = definitions.stream().sorted(Comparator.comparing(MutationDefinition::id)).toList();
        var ids = new HashSet<ContentId>();
        var targets = new HashSet<TargetKey>();
        for (MutationDefinition mutation : this.definitions) {
            if (!ids.add(mutation.id())) throw new IllegalArgumentException("Duplicate mutation ID: " + mutation.id());
            if (!targets.add(new TargetKey(mutation.category(), mutation.locus(), mutation.targetAllele()))) {
                throw new IllegalArgumentException("Duplicate mutation target at " + mutation.locus());
            }
            if (!this.allowedLoci.get(mutation.category()).contains(mutation.locus())) {
                throw new IllegalArgumentException("Locus not allowed for mutation category: " + mutation.id());
            }
            requireAllele(mutation.locus(), mutation.targetAllele());
            for (AllelePair pair : mutation.fromPairs()) {
                requireAllele(mutation.locus(), pair.first());
                requireAllele(mutation.locus(), pair.second());
            }
        }
    }

    public RulesetVersion reference() { return reference; }

    public RulesetVersion geneticsReference() { return genetics.reference(); }

    /** At most one actual copy changes per call. The future birth workflow must invoke this only once. */
    public Result apply(Genome inherited, MutationPolicy policy, MutationRandom random) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(random, "random");
        genetics.validate(inherited);
        BigDecimal draw = unit(random);
        BigDecimal end = policy.noMutationChance();
        if (draw.compareTo(end) < 0) return new Unchanged(inherited);
        MutationCategory category = null;
        for (MutationCategory candidate : MutationCategory.values()) {
            end = end.add(BigDecimal.valueOf(policy.categoryChances().get(candidate)));
            if (draw.compareTo(end) < 0) {
                category = candidate;
                break;
            }
        }
        if (category == null) throw new IllegalStateException("Category probability coverage is incomplete");
        Impact impact = category == MutationCategory.COSMETIC ? Impact.COSMETIC : selectImpact(policy, random);
        EnumMap<Locus, List<MutationDefinition>> eligible = new EnumMap<>(Locus.class);
        boolean hasCategory = false;
        boolean hasImpact = false;
        for (MutationDefinition mutation : definitions) {
            if (mutation.category() == category) {
                hasCategory = true;
                if (mutation.impact() == impact) {
                    hasImpact = true;
                    if (mutation.fromPairs().contains(inherited.loci().get(mutation.locus()))) {
                        eligible.computeIfAbsent(mutation.locus(), ignored -> new ArrayList<>()).add(mutation);
                    }
                }
            }
        }
        if (eligible.isEmpty()) {
            // SKIP is deliberately the only supported policy: no retry, renormalization or genome replacement.
            SkipReason reason = !hasCategory ? SkipReason.CATEGORY_UNAVAILABLE
                    : !hasImpact ? SkipReason.IMPACT_UNAVAILABLE : SkipReason.GENOTYPE_UNSUPPORTED;
            return switch (policy.unavailablePolicy()) {
                case SKIP -> new Skipped(inherited, category, impact, reason);
            };
        }
        var loci = List.copyOf(eligible.keySet());
        Locus locus = loci.get(index(random, loci.size()));
        var candidates = eligible.get(locus);
        MutationDefinition selected = candidates.get(index(random, candidates.size()));
        AllelePair before = inherited.loci().get(locus);
        List<Integer> copies = new ArrayList<>(2);
        if (!before.first().equals(selected.targetAllele())) copies.add(0);
        if (!before.second().equals(selected.targetAllele())) copies.add(1);
        int copy = copies.get(index(random, copies.size()));
        ContentId previous = copy == 0 ? before.first() : before.second();
        AllelePair after = copy == 0 ? new AllelePair(selected.targetAllele(), before.second())
                : new AllelePair(before.first(), selected.targetAllele());
        EnumMap<Locus, AllelePair> child = new EnumMap<>(inherited.loci());
        child.put(locus, after);
        Genome mutated = new Genome(inherited.ruleset(), child);
        genetics.validate(mutated);
        return new Mutated(mutated, new Change(selected.id(), category, impact, locus, copy, previous, selected.targetAllele()));
    }

    private static Impact selectImpact(MutationPolicy policy, MutationRandom random) {
        BigDecimal draw = unit(random);
        BigDecimal end = BigDecimal.ZERO;
        for (Impact impact : Impact.values()) {
            if (impact == Impact.COSMETIC) continue;
            end = end.add(BigDecimal.valueOf(policy.impactChances().get(impact)));
            if (draw.compareTo(end) < 0) return impact;
        }
        throw new IllegalStateException("Impact probability coverage is incomplete");
    }

    private static BigDecimal unit(MutationRandom random) {
        double value = random.nextUnit();
        if (!Double.isFinite(value) || value < 0 || value >= 1) throw new IllegalArgumentException("RNG value must be in [0,1)");
        return BigDecimal.valueOf(value);
    }

    private static int index(MutationRandom random, int bound) {
        int value = random.nextIndex(bound);
        if (value < 0 || value >= bound) throw new IllegalArgumentException("RNG index outside requested bound");
        return value;
    }

    private void requireAllele(Locus locus, ContentId id) {
        AlleleDefinition allele = genetics.alleles().get(id);
        if (allele == null || allele.locus() != locus) throw new IllegalArgumentException("Unknown or misplaced mutation allele: " + id);
    }
}
