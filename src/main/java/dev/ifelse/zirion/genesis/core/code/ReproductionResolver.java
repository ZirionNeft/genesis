package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.content.GeneticsCatalog;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.morphology.MorphologyRules;
import dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Pure readiness check. No NPC creation, import permissions, provenance, instance ID or persistence. */
public final class ReproductionResolver {
    public enum Mode { EXACT, GENETIC_CURRENT }
    public enum Operation { IMPORT, SPAWN, BREED, MUTATE }
    public enum Reason { UNKNOWN_ARCHETYPE, VERSION_UNSUPPORTED, FAMILY_MISMATCH, RIG_MISMATCH,
        RULESET_MISMATCH, INVALID_GENOME, EXTENSION_UNRESOLVED, MORPHOLOGY_UNSUPPORTED }
    public sealed interface Result permits Resolved, Rejected {}
    public record Resolved(GenesisBlueprint source, Genome evaluationGenome, ReproductionProfile profile,
                           MorphologyRules.Resolved morphology, PhenotypeRules.Phenotype phenotype,
                           boolean definitionsChanged) implements Result {}
    public record Rejected(Operation operation, Reason reason, Optional<ExtensionRegistry.Issue> extensionIssue) implements Result {}

    private final GeneticsCatalog catalog;
    private final Map<ContentId, ReproductionProfile> profiles;
    private final ExtensionRegistry extensions;

    /** One frozen content revision. Archived contexts can be retained separately for exact reproduction. */
    public ReproductionResolver(GeneticsCatalog catalog, Collection<ReproductionProfile> profiles, ExtensionRegistry extensions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        Map<ContentId, ReproductionProfile> copy = new TreeMap<>();
        for (ReproductionProfile profile : profiles) {
            if (copy.putIfAbsent(profile.reference().id(), profile) != null) throw new IllegalArgumentException("Duplicate archetype ID");
            var family = catalog.families().get(profile.family().id());
            var rig = catalog.rigs().get(profile.rig().id());
            if (family == null || family.version() != profile.family().version() || rig == null || rig.version() != profile.rig().version()
                    || !family.supportedRigs().contains(rig.id()) || !family.ruleset().equals(profile.phenotype().geneticsReference())) {
                throw new IllegalArgumentException("Archetype does not match frozen genetics catalog");
            }
        }
        this.profiles = Map.copyOf(copy);
    }

    /** Both modes preserve the source blueprint; current mode keeps every allele and reports changed definitions. */
    public Result resolve(GenesisBlueprint blueprint, Mode mode, Operation operation) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(operation, "operation");
        ReproductionProfile profile = profiles.get(blueprint.archetype().id());
        if (profile == null) return rejected(operation, Reason.UNKNOWN_ARCHETYPE);
        if (!profile.family().id().equals(blueprint.family().id())) return rejected(operation, Reason.FAMILY_MISMATCH);
        if (!profile.rig().id().equals(blueprint.rig().id())) return rejected(operation, Reason.RIG_MISMATCH);
        RulesetVersion current = profile.phenotype().geneticsReference();
        if (!current.id().equals(blueprint.genome().ruleset().id())) return rejected(operation, Reason.RULESET_MISMATCH);
        boolean changed = !profile.reference().equals(blueprint.archetype()) || !profile.family().equals(blueprint.family())
                || !profile.rig().equals(blueprint.rig()) || !profile.assetSet().equals(blueprint.assetSet())
                || !current.equals(blueprint.genome().ruleset());
        if (mode == Mode.EXACT && changed) return rejected(operation, Reason.VERSION_UNSUPPORTED);
        Genome evaluation = mode == Mode.EXACT ? blueprint.genome() : new Genome(current, blueprint.genome().loci());
        try {
            catalog.rulesets().get(current.id()).validate(evaluation);
        } catch (IllegalArgumentException exception) {
            return rejected(operation, Reason.INVALID_GENOME);
        }
        var extensionResult = extensions.check(blueprint, profile.requiredExtensions());
        if (extensionResult instanceof ExtensionRegistry.Rejected failure) {
            return new Rejected(operation, Reason.EXTENSION_UNRESOLVED, Optional.of(failure.issue()));
        }
        var visual = profile.morphology().resolve(evaluation);
        if (!(visual instanceof MorphologyRules.Resolved resolved)) return rejected(operation, Reason.MORPHOLOGY_UNSUPPORTED);
        return new Resolved(blueprint, evaluation, profile, resolved, profile.phenotype().resolve(evaluation), changed);
    }

    private static Rejected rejected(Operation operation, Reason reason) { return new Rejected(operation, reason, Optional.empty()); }
}
