package dev.ifelse.zirion.genesis.core.mutation.v2;

import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.mutation.MutationRandom;
import dev.ifelse.zirion.genesis.core.mutation.SeededMutationRandom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.mutation.v2.MutationCatalog.Category;

/** One de novo copy change after ordinary inheritance. Does not commit research progress or create a Spark. */
public final class MutationEvaluator {
    public static final RulesetVersion ALGORITHM = new RulesetVersion(ContentId.parse("genesis:mutation_evaluator"), 2);
    private static final ContentId ARCANA = ContentId.parse("genesis:arcana");
    private static final ContentId QUIET = ContentId.parse("genesis:quiet");
    public enum UnchangedReason { NO_EVENT, CATEGORY_UNAVAILABLE, NO_ELIGIBLE_MUTATIONS }
    /** copyIndex refers to the canonical input pair, never parental provenance or sorted output. */
    public record Change(ContentId mutationId, Category category, ContentId gene, int copyIndex,
                         ContentId previous, ContentId target, boolean guaranteed) {}
    /** Proposed CAS update. The operation layer must atomically apply it once with the birth result. */
    public record DiscoveryUpdate(UUID playerId, ContentId familyId, long expectedRevision, int qualifyingSuccesses,
                                   boolean firstDiscoveryAwarded, boolean awardFirstDiscovery, boolean qualifyingBirth) {}
    public record Result(Genome genome, Optional<Change> change, Optional<UnchangedReason> unchangedReason,
                         DiscoveryUpdate discovery) {}

    private final MutationCatalog catalog;
    private final FamilyGeneSchema schema;
    private final ServerConfig.Mutation policy;

    public MutationEvaluator(MutationCatalog catalog, FamilyGeneSchema schema, ServerConfig.Mutation policy) {
        this.catalog = Objects.requireNonNull(catalog); this.schema = Objects.requireNonNull(schema); this.policy = Objects.requireNonNull(policy);
        catalog.validate(java.util.Map.of(schema.familyId(), schema));
    }

    public Result apply(Genome inherited, ResearchSnapshot research, long seed) {
        return apply(inherited, research, new SeededMutationRandom(seed));
    }

    // Deterministic test seam; production owns a fresh dedicated mutation stream.
    Result apply(Genome inherited, ResearchSnapshot research, MutationRandom random) {
        Objects.requireNonNull(random); schema.validate(inherited); Objects.requireNonNull(research);
        if (!schema.familyId().equals(research.familyId())) throw new IllegalArgumentException("Research belongs to another family");
        var eligible = new java.util.EnumMap<Category, List<MutationCatalog.Definition>>(Category.class);
        for (Category category : Category.values()) eligible.put(category, new ArrayList<>());
        for (var definition : catalog.definitions().values()) {
            AllelePair pair = inherited.genes().get(definition.gene());
            if (pair != null && (!pair.first().equals(definition.target()) || !pair.second().equals(definition.target()))) {
                eligible.get(definition.category()).add(definition);
            }
        }
        AllelePair arcana = inherited.genes().get(ARCANA);
        boolean qualifying = policy.arcaneFocus().enabled() && research.origin() == ResearchSnapshot.Origin.BREEDING
                && research.tier() == ResearchSnapshot.Tier.MASTERY && research.focused() && !research.firstDiscoveryAwarded()
                && arcana != null && arcana.first().equals(QUIET) && arcana.second().equals(QUIET);
        boolean guaranteed = qualifying && research.qualifyingSuccesses() >= policy.arcaneFocus().firstDiscoveryGuaranteeEligibleSuccessfulBirths() - 1;
        Category selected;
        if (guaranteed) {
            selected = Category.MAGICAL;
            if (eligible.get(selected).isEmpty()) throw new IllegalArgumentException("Required first discovery cannot be produced by this catalog");
        } else {
            if (eligible.values().stream().allMatch(List::isEmpty)) return unchanged(inherited, research, qualifying, UnchangedReason.NO_ELIGIBLE_MUTATIONS);
            var chances = switch (research.tier()) {
                case BASE -> policy.probabilityByTier().base();
                case DISCOVERY -> policy.probabilityByTier().discovery();
                case MASTERY -> policy.probabilityByTier().mastery();
            };
            // Ordered exclusive categories, followed by the remaining no-event interval. No impact roll or renormalization.
            BigDecimal draw = unit(random), end = BigDecimal.ZERO; selected = null;
            for (Category category : Category.values()) {
                BigDecimal chance = switch (category) {
                    case COSMETIC -> chances.cosmetic(); case PHYSIOLOGICAL -> chances.physiological();
                    case BEHAVIORAL -> chances.behavioral(); case MAGICAL -> chances.magical();
                };
                end = end.add(chance);
                if (draw.compareTo(end) < 0) { selected = category; break; }
            }
            if (selected == null) return unchanged(inherited, research, qualifying, UnchangedReason.NO_EVENT);
        }
        List<MutationCatalog.Definition> candidates = eligible.get(selected);
        if (candidates.isEmpty()) return unchanged(inherited, research, qualifying, UnchangedReason.CATEGORY_UNAVAILABLE);
        var mutation = candidates.get(index(random, candidates.size()));
        AllelePair before = inherited.genes().get(mutation.gene());
        var copies = new ArrayList<Integer>(2);
        if (!before.first().equals(mutation.target())) copies.add(0);
        if (!before.second().equals(mutation.target())) copies.add(1);
        int copy = copies.get(index(random, copies.size()));
        ContentId previous = copy == 0 ? before.first() : before.second();
        var pairs = new TreeMap<>(inherited.genes());
        pairs.put(mutation.gene(), copy == 0 ? new AllelePair(mutation.target(), before.second()) : new AllelePair(before.first(), mutation.target()));
        Genome genome = schema.createGenome(pairs);
        var change = new Change(mutation.id(), selected, mutation.gene(), copy, previous, mutation.target(), guaranteed);
        return new Result(genome, Optional.of(change), Optional.empty(), update(research, qualifying, selected == Category.MAGICAL));
    }

    private Result unchanged(Genome genome, ResearchSnapshot research, boolean qualifying, UnchangedReason reason) {
        return new Result(genome, Optional.empty(), Optional.of(reason), update(research, qualifying, false));
    }

    private DiscoveryUpdate update(ResearchSnapshot before, boolean qualifying, boolean magical) {
        boolean award = magical && before.origin() == ResearchSnapshot.Origin.BREEDING && !before.firstDiscoveryAwarded();
        return new DiscoveryUpdate(before.playerId(), before.familyId(), before.revision(),
                before.qualifyingSuccesses() + (qualifying ? 1 : 0), before.firstDiscoveryAwarded() || award, award, qualifying);
    }

    private static BigDecimal unit(MutationRandom random) {
        double value = random.nextUnit();
        if (!Double.isFinite(value) || value < 0 || value >= 1) throw new IllegalArgumentException("Mutation RNG must be in [0,1)");
        return new BigDecimal(value); // exact binary sample, matching v2 attempt comparison
    }
    private static int index(MutationRandom random, int bound) {
        int value = random.nextIndex(bound);
        if (value < 0 || value >= bound) throw new IllegalArgumentException("Mutation RNG index out of bounds");
        return value;
    }
}
