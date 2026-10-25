package dev.ifelse.zirion.genesis.core.breeding.v2;

import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.mutation.SeededMutationRandom;
import dev.ifelse.zirion.genesis.core.mutation.v2.MutationCatalog;
import dev.ifelse.zirion.genesis.core.mutation.v2.MutationEvaluator;
import dev.ifelse.zirion.genesis.core.mutation.v2.ResearchSnapshot;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Full pure genetic candidate calculation. Game eligibility, durable research CAS and Spark creation remain external. */
public final class BreedingMutationWorkflow {
    public static final RulesetVersion ALGORITHM = new RulesetVersion(ContentId.parse("genesis:breeding_mutation_workflow"), 2);
    public record Algorithms(RulesetVersion workflow, BreedingWorkflow.Algorithms inheritance,
                              RulesetVersion mutation, ContentId selection, ContentId mutationRandom) {
        public Algorithms { Objects.requireNonNull(workflow); Objects.requireNonNull(inheritance); Objects.requireNonNull(mutation); Objects.requireNonNull(selection); Objects.requireNonNull(mutationRandom); }
    }
    public static final Algorithms ALGORITHMS = new Algorithms(ALGORITHM, BreedingWorkflow.ALGORITHMS,
            MutationEvaluator.ALGORITHM, MutationCatalog.SELECTION, SeededMutationRandom.ALGORITHM);
    public record Request(BreedingWorkflow.Request breeding, ResearchSnapshot research) {
        public Request { Objects.requireNonNull(breeding); Objects.requireNonNull(research); }
    }
    public record Replay(Algorithms algorithms, BreedingContext context, Request request, Set<ExtensionPayload.Key> requiredExtensions) {
        public Replay { Objects.requireNonNull(algorithms); Objects.requireNonNull(context); Objects.requireNonNull(request); requiredExtensions = Set.copyOf(requiredExtensions); }
    }
    public enum Reason { RESEARCH_FAMILY_MISMATCH, PRECONDITION }
    public sealed interface Result permits Rejected, Failed, Conceived {}
    public record Rejected(Reason reason, Optional<BreedingWorkflow.Reason> attemptReason) implements Result {
        public Rejected { Objects.requireNonNull(reason); Objects.requireNonNull(attemptReason); }
    }
    /** No mutation evaluation or discovery update exists for a failed attempt. */
    public record Failed(Replay replay, BreedingWorkflow.Failed attempt) implements Result {}
    /** Fixed genetic candidate and proposed discovery update, not a committed birth or live NPC. */
    public record Conceived(Replay replay, BreedingWorkflow.AwaitingMutation inherited,
                            MutationEvaluator.Result mutation, PhenotypeResolver.Result potential) implements Result {
        public Genome genome() { return mutation.genome(); }
    }

    private final BreedingContext context;
    private final Set<ExtensionPayload.Key> requiredExtensions;
    private final BreedingWorkflow inheritance;
    private final PhenotypeResolver phenotype;
    public BreedingMutationWorkflow(BreedingContext context, Set<ExtensionPayload.Key> requiredExtensions) {
        this.context = Objects.requireNonNull(context); this.requiredExtensions = Set.copyOf(requiredExtensions);
        inheritance = new BreedingWorkflow(context, requiredExtensions); phenotype = new PhenotypeResolver(context.catalogs());
    }

    public Result calculate(Request request) {
        Objects.requireNonNull(request);
        if (!request.research().familyId().equals(request.breeding().parentA().genome().familyId())) {
            return new Rejected(Reason.RESEARCH_FAMILY_MISMATCH, Optional.empty());
        }
        var first = inheritance.calculate(request.breeding());
        if (first instanceof BreedingWorkflow.Rejected rejected) return new Rejected(Reason.PRECONDITION, Optional.of(rejected.reason()));
        var replay = new Replay(ALGORITHMS, context, request, requiredExtensions);
        if (first instanceof BreedingWorkflow.Failed failed) return new Failed(replay, failed);
        var pending = (BreedingWorkflow.AwaitingMutation) first;
        var schema = context.catalogs().families().get(pending.inherited().familyId());
        var mutation = new MutationEvaluator(context.catalogs().mutations(), schema, context.config().genetics().mutation())
                .apply(pending.inherited(), request.research(), pending.mutationSeed());
        return new Conceived(replay, pending, mutation, phenotype.resolve(pending.historicalForm(), mutation.genome()));
    }

    public static Result replay(Replay replay) {
        Objects.requireNonNull(replay);
        if (!ALGORITHMS.equals(replay.algorithms())) throw new IllegalArgumentException("Unsupported full breeding replay algorithms");
        return new BreedingMutationWorkflow(replay.context(), replay.requiredExtensions()).calculate(replay.request());
    }
}
