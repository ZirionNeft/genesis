package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.content.*;
import dev.ifelse.zirion.genesis.core.genetics.*;
import dev.ifelse.zirion.genesis.core.morphology.MorphologyRules;
import dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules;
import dev.ifelse.zirion.genesis.core.phenotype.Stat;

import java.util.*;

/** Test-only closed profiles/asset manifests; no claims about real Hytale content. */
final class CodeFixtures {
    static RulesetVersion ref(String id, int version) { return new RulesetVersion(ContentId.parse(id), version); }

    static GenesisBlueprint vector() {
        EnumMap<Locus, AllelePair> pairs = new EnumMap<>(Locus.class);
        for (Locus locus : Locus.values()) {
            String suffix = String.format(Locale.ROOT, "%02d", locus.ordinal());
            pairs.put(locus, new AllelePair(ContentId.parse("v:a" + suffix), ContentId.parse("v:b" + suffix)));
        }
        return new GenesisBlueprint(new Genome(ref("v:r", 1), pairs), ref("v:f", 1), ref("v:q", 1),
                ref("v:h", 1), ref("v:s", 1), 42, List.of(extension("v:x", 2, 3, ExtensionPayload.Kind.REQUIRED, new byte[]{0, -1, 1, 0})));
    }

    static ExtensionPayload extension(String id, int version, int schema, ExtensionPayload.Kind kind, byte[] bytes) {
        return new ExtensionPayload(new ExtensionPayload.Key(ref(id, version), schema), kind, new ImmutableBytes(bytes));
    }

    static GenesisBlueprint withExtensions(GenesisBlueprint source, List<ExtensionPayload> extensions) {
        return new GenesisBlueprint(source.genome(), source.family(), source.rig(), source.archetype(), source.assetSet(), source.cosmeticSeed(), extensions);
    }

    record Context(GeneticsCatalog catalog, ReproductionProfile profile, GenesisBlueprint blueprint) {
        ReproductionResolver resolver(ExtensionRegistry extensions) { return new ReproductionResolver(catalog, List.of(profile), extensions); }
    }

    static Context context(int version, Set<ExtensionPayload.Key> required) {
        var starter = CanidStarterContent.create();
        var old = starter.rulesets().get(CanidStarterContent.RULESET.id());
        var original = starter.species().get(CanidStarterContent.WOLF).starterGenome();
        var builder = GeneticsRuleset.builder(new RulesetVersion(old.reference().id(), version));
        old.alleles().values().forEach(allele -> builder.registerAllele("genesis", allele));
        for (Locus locus : Locus.values()) {
            var alleles = old.alleles().values().stream().filter(allele -> allele.locus() == locus).map(AlleleDefinition::id).toList();
            for (int a = 0; a < alleles.size(); a++) for (int b = a; b < alleles.size(); b++) {
                var pair = new AllelePair(alleles.get(a), alleles.get(b));
                var loci = new EnumMap<>(original.loci());
                loci.put(locus, pair);
                builder.registerExpression(locus, pair, old.resolveExpressions(new Genome(old.reference(), loci)).get(locus));
            }
        }
        var genetics = builder.freeze();
        var catalog = GeneticsCatalog.builder(ContentId.parse("fixture:code_catalog"), version)
                .registerRuleset("genesis", genetics)
                .registerRig("genesis", new RigDefinition(CanidStarterContent.RIG, version))
                .registerFamily("genesis", new FamilyDefinition(CanidStarterContent.FAMILY, version, Set.of(CanidStarterContent.RIG), genetics.reference()))
                .freeze();
        Map<PhenotypeRules.Trait, PhenotypeRules.Effect> effects = new HashMap<>();
        for (Locus locus : Locus.values()) for (ContentId expression : genetics.expressionOptions(locus)) {
            effects.put(new PhenotypeRules.Trait(locus, expression), new PhenotypeRules.Effect(Map.of(), Optional.empty()));
        }
        var ranges = new EnumMap<Stat, PhenotypeRules.Range>(Stat.class);
        for (Stat stat : Stat.values()) ranges.put(stat, new PhenotypeRules.Range(version == 1 ? 50 : 60, 0, 100));
        var phenotype = new PhenotypeRules(ref("fixture:phenotype", version), genetics, ranges, effects, List.of());
        var plans = new HashMap<MorphologyRules.Signature, MorphologyRules.Appearance>();
        var available = new HashSet<RulesetVersion>();
        for (ContentId expression : genetics.expressionOptions(Locus.HEAD)) {
            var appearance = new MorphologyRules.Appearance(new ContentId("fixture", "look/" + expression.path()), version, CanidStarterContent.RIG);
            plans.put(new MorphologyRules.Signature(Map.of(Locus.HEAD, expression)), appearance);
            available.add(new RulesetVersion(appearance.id(), version));
        }
        var morphology = new MorphologyRules(ref("fixture:morphology", version), genetics, CanidStarterContent.RIG, Set.of(Locus.HEAD), plans);
        var profile = new ReproductionProfile(ref("fixture:canid_archetype", version),
                new RulesetVersion(CanidStarterContent.FAMILY, version), new RulesetVersion(CanidStarterContent.RIG, version),
                ref("fixture:assets", version), phenotype, morphology, available, required);
        var blueprint = new GenesisBlueprint(new Genome(genetics.reference(), original.loci()), profile.family(), profile.rig(),
                profile.reference(), profile.assetSet(), Long.MIN_VALUE, List.of());
        return new Context(catalog, profile, blueprint);
    }
}
