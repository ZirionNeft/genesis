package dev.ifelse.zirion.genesis.core.breeding.v2;

import dev.ifelse.zirion.genesis.core.breeding.AlleleRandom;
import dev.ifelse.zirion.genesis.core.breeding.GenerationSeed;
import dev.ifelse.zirion.genesis.core.breeding.SeededAlleleRandom;
import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.content.v2.FormIdentity;
import dev.ifelse.zirion.genesis.core.content.v2.FormResolver;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.genetics.v2.MendelianInheritance;
import dev.ifelse.zirion.genesis.core.lineage.PedigreeEntry;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Pure pre-mutation attempt pipeline for v2 genomes. Successful sampling returns AwaitingMutation,
 * never a final offspring/Spark. No persistence, authorization, assets or kinship penalties.
 */
public final class BreedingWorkflow {
    public static final RulesetVersion ALGORITHM = new RulesetVersion(ContentId.parse("genesis:breeding_inheritance_workflow"), 2);

    public record Algorithms(RulesetVersion workflow, RulesetVersion generationSeed, ContentId alleleRandom, ContentId inheritance) {
        public Algorithms { Objects.requireNonNull(workflow); Objects.requireNonNull(generationSeed); Objects.requireNonNull(alleleRandom); Objects.requireNonNull(inheritance); }
    }

    public static final Algorithms ALGORITHMS = new Algorithms(ALGORITHM,
            new RulesetVersion(GenerationSeed.ALGORITHM, GenerationSeed.SCHEME_VERSION),
            SeededAlleleRandom.ALGORITHM, MendelianInheritance.ALGORITHM);

    /** Full trusted parent snapshot; extension data is retained even though inheritance is unsupported. */
    public record Parent(UUID creatureId, FormIdentity form, Genome genome, List<ExtensionPayload> extensions) {
        public Parent {
            Objects.requireNonNull(creatureId); Objects.requireNonNull(form); Objects.requireNonNull(genome);
            extensions = List.copyOf(extensions);
        }
    }

    public record Conditions(BigDecimal readinessA, BigDecimal readinessB, BigDecimal environment) {
        public Conditions {
            probability(readinessA); probability(readinessB); Objects.requireNonNull(environment);
            if (environment.signum() < 0) throw new IllegalArgumentException("Negative environment multiplier");
        }
    }

    public enum Mode { AUTO, MANUAL }
    public record Approach(Mode mode, Optional<BigDecimal> score, int familySuccesses) {
        public Approach {
            Objects.requireNonNull(mode); Objects.requireNonNull(score);
            if (familySuccesses < 0 || score.isPresent() != (mode == Mode.MANUAL)) throw new IllegalArgumentException("Invalid breeding approach");
            score.ifPresent(value -> {
                if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("Score must be in [0,100]");
            });
        }
        public static Approach auto(int familySuccesses) { return new Approach(Mode.AUTO, Optional.empty(), familySuccesses); }
        public static Approach manual(BigDecimal score) { return new Approach(Mode.MANUAL, Optional.of(score), 0); }
    }

    /** IDs and seed are supplied by the trusted operation layer and must be persisted once in stage 2A. */
    public record Request(UUID operationId, UUID offspringId, Parent parentA, Parent parentB,
                          Conditions conditions, Approach approach, GenerationSeed seed) {
        public Request {
            Objects.requireNonNull(operationId); Objects.requireNonNull(offspringId);
            Objects.requireNonNull(parentA); Objects.requireNonNull(parentB); Objects.requireNonNull(conditions);
            Objects.requireNonNull(approach); Objects.requireNonNull(seed);
        }
    }

    /** In-memory replay context, not a durable journal or an external transport format. */
    public record Replay(Algorithms algorithms, BreedingContext context, Request request,
                         Set<ExtensionPayload.Key> requiredExtensions) {
        public Replay {
            Objects.requireNonNull(algorithms); Objects.requireNonNull(context); Objects.requireNonNull(request);
            requiredExtensions = Set.copyOf(requiredExtensions);
        }
    }

    public record Quote(BigDecimal successProbability, BigDecimal cooldownMinutes, BigDecimal harmonyMultiplier) {
        public Quote {
            probability(successProbability); Objects.requireNonNull(cooldownMinutes); Objects.requireNonNull(harmonyMultiplier);
            if (cooldownMinutes.signum() < 0 || harmonyMultiplier.signum() < 0) throw new IllegalArgumentException("Negative quote value");
        }
    }

    public enum Reason { BREEDING_DISABLED, SAME_PARENT, OFFSPRING_IS_PARENT, UNSUPPORTED_EXTENSIONS,
        INCOMPATIBLE_FAMILY, INVALID_PARENT, ENVIRONMENT_OUT_OF_RANGE, MANUAL_DISABLED, AUTO_DISABLED, AUTO_LOCKED }
    public sealed interface Result permits Rejected, Failed, AwaitingMutation {}
    /** No random sampling. The caller must not spend an attempt on this result. */
    public record Rejected(Reason reason) implements Result { public Rejected { Objects.requireNonNull(reason); } }
    public record Failed(Replay replay, Quote quote, double attemptDraw) implements Result {}
    /** Inherited genotype/potential only. Mutation evaluation and all game commit checks remain mandatory. */
    public record AwaitingMutation(Replay replay, Quote quote, double attemptDraw, FormIdentity historicalForm,
                                   Genome inherited, PhenotypeResolver.Result inheritedPotential, PedigreeEntry pedigree,
                                   long mutationSeed, long cosmeticSeed) implements Result {}

    private final BreedingContext context;
    private final Set<ExtensionPayload.Key> requiredExtensions;
    private final FormResolver forms;
    private final PhenotypeResolver phenotype;

    public BreedingWorkflow(BreedingContext context, Set<ExtensionPayload.Key> requiredExtensions) {
        this.context = Objects.requireNonNull(context);
        this.requiredExtensions = Set.copyOf(requiredExtensions);
        this.forms = new FormResolver(context.catalogs().hybridGraph(), context.catalogs().families());
        this.phenotype = new PhenotypeResolver(context.catalogs());
    }

    public Result calculate(Request request) {
        Objects.requireNonNull(request);
        return calculate(request, new SeededStreams(request.seed()));
    }

    public static Result replay(Replay replay) {
        Objects.requireNonNull(replay);
        if (!ALGORITHMS.equals(replay.algorithms())) throw new IllegalArgumentException("Unsupported replay algorithms");
        return new BreedingWorkflow(replay.context(), replay.requiredExtensions()).calculate(replay.request());
    }

    // Test seam only; production always uses the recorded seed and algorithms.
    interface Streams { double attempt(); AlleleRandom inheritance(); }

    Result calculate(Request request, Streams streams) {
        Objects.requireNonNull(request); Objects.requireNonNull(streams);
        var config = context.config().breeding();
        Parent a = request.parentA(), b = request.parentB();
        if (!config.enabled()) return new Rejected(Reason.BREEDING_DISABLED);
        if (a.creatureId().equals(b.creatureId())) return new Rejected(Reason.SAME_PARENT);
        if (request.offspringId().equals(a.creatureId()) || request.offspringId().equals(b.creatureId())) return new Rejected(Reason.OFFSPRING_IS_PARENT);
        if (!requiredExtensions.isEmpty() || !a.extensions().isEmpty() || !b.extensions().isEmpty()) return new Rejected(Reason.UNSUPPORTED_EXTENSIONS);
        if (!a.genome().familyId().equals(b.genome().familyId())) return new Rejected(Reason.INCOMPATIBLE_FAMILY);
        FormIdentity childForm;
        try {
            forms.resolve(a.form(), a.genome()); forms.resolve(b.form(), b.genome());
            childForm = context.catalogs().hybridGraph().historicalChild(a.form(), b.form());
        } catch (IllegalArgumentException exception) { return new Rejected(Reason.INVALID_PARENT); }
        var conditions = request.conditions(); var approach = request.approach();
        if (conditions.environment().compareTo(config.environmentMinMultiplier()) < 0
                || conditions.environment().compareTo(config.environmentMaxMultiplier()) > 0) return new Rejected(Reason.ENVIRONMENT_OUT_OF_RANGE);
        if (approach.mode() == Mode.MANUAL && !config.manualMinigameEnabled()) return new Rejected(Reason.MANUAL_DISABLED);
        if (approach.mode() == Mode.AUTO) {
            if (!config.autoBreedingEnabled()) return new Rejected(Reason.AUTO_DISABLED);
            if (config.manualMinigameEnabled() && approach.familySuccesses() < config.autoUnlockAfterFamilySuccesses()) return new Rejected(Reason.AUTO_LOCKED);
        }
        BigDecimal harmony = approach.mode() == Mode.AUTO ? BigDecimal.ONE
                : new BigDecimal("0.90").add(new BigDecimal("0.25").multiply(approach.score().orElseThrow().movePointLeft(2)));
        BigDecimal base = a.form().historicalForm().equals(b.form().historicalForm()) ? config.baseSameFormSuccess() : config.baseSameFamilySuccess();
        BigDecimal chance = base.multiply(conditions.readinessA()).multiply(conditions.readinessB())
                .multiply(conditions.environment()).multiply(harmony).min(config.maxSuccessProbability());
        BigDecimal cooldown = BigDecimal.valueOf(config.baseCooldownMinutes());
        if (approach.mode() == Mode.MANUAL && approach.score().orElseThrow().compareTo(BigDecimal.valueOf(config.manualScoreBonusThreshold())) >= 0) {
            cooldown = cooldown.multiply(BigDecimal.ONE.subtract(config.manualCooldownReduction()));
        }
        var quote = new Quote(chance, cooldown.max(BigDecimal.valueOf(config.minCooldownMinutes())), harmony);
        var replay = new Replay(ALGORITHMS, context, request, requiredExtensions);
        double draw = streams.attempt(); // exactly one draw even at chance zero
        if (!Double.isFinite(draw) || draw < 0 || draw >= 1) throw new IllegalArgumentException("Attempt RNG value must be in [0,1)");
        // nextDouble is an exact binary fraction n/2^53. Preserve it exactly for comparison with decimal chance.
        if (new BigDecimal(draw).compareTo(chance) >= 0) return new Failed(replay, quote, draw);
        var schema = context.catalogs().families().get(a.genome().familyId());
        Genome inherited = MendelianInheritance.inherit(a.genome(), b.genome(), schema, streams.inheritance());
        return new AwaitingMutation(replay, quote, draw, childForm, inherited, phenotype.resolve(childForm, inherited),
                PedigreeEntry.child(request.offspringId(), a.creatureId(), b.creatureId()),
                request.seed().forStream(GenerationSeed.Stream.MUTATION), request.seed().forStream(GenerationSeed.Stream.COSMETIC));
    }

    private record SeededStreams(GenerationSeed seed) implements Streams {
        @Override public double attempt() { return new Random(seed.forStream(GenerationSeed.Stream.ATTEMPT)).nextDouble(); }
        @Override public AlleleRandom inheritance() { return new SeededAlleleRandom(seed.forStream(GenerationSeed.Stream.INHERITANCE)); }
    }

    private static void probability(BigDecimal value) {
        Objects.requireNonNull(value);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Probability must be in [0,1]");
    }
}
