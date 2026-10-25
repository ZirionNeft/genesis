package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.*;
import dev.ifelse.zirion.genesis.core.morphology.MorphologyRules;
import dev.ifelse.zirion.genesis.core.mutation.*;
import dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Executable D05/D06 candidate, not approved gameplay balance or a spawn-ready asset profile. */
public final class CanidBreedingContent {
    public static final RulesetVersion RULESET = new RulesetVersion(CanidStarterContent.RULESET.id(), 2);
    public static final RulesetVersion MUTATIONS = new RulesetVersion(ContentId.parse("genesis:canid_mutations_draft"), 1);

    public enum Archetype { WOLF, FOX, HYBRID }

    public record Definitions(GeneticsCatalog catalog, MutationRules mutations, Map<Archetype, PhenotypeRules> phenotypes) {
        public Definitions { phenotypes = Map.copyOf(phenotypes); }

        /** An explicit coverage audit, with no invented appearance IDs or mappings to prototype geometry. */
        public MorphologyRules unmappedMorphology() {
            return new MorphologyRules(new RulesetVersion(ContentId.parse("genesis:canid_morphology_draft"), 1),
                    catalog.rulesets().get(RULESET.id()), CanidStarterContent.RIG,
                    Set.of(Locus.FRAME, Locus.HEAD, Locus.LIMBS, Locus.APPENDAGE, Locus.PIGMENT, Locus.COVER), Map.of());
        }
    }

    private record Addition(Locus locus, String allele, String withFirst, String withSecond, String homozygous,
                            MutationCategory category, MutationDefinition.Impact impact) {}

    // withFirst/withSecond refer to the explicit starter row order, not lexical order or a global rank.
    private static final List<Addition> ADDITIONS = List.of(
            new Addition(Locus.LIMBS, "fragile_bones", "normal", "runner", "fragile_bones",
                    MutationCategory.PHYSIOLOGICAL, MutationDefinition.Impact.HARMFUL),
            new Addition(Locus.PIGMENT, "shadow", "grey", "orange", "shadow",
                    MutationCategory.COSMETIC, MutationDefinition.Impact.COSMETIC),
            new Addition(Locus.MUSCLE, "hypertrophy", "hypertrophy_mild", "hypertrophy_mild", "hypertrophy",
                    MutationCategory.PHYSIOLOGICAL, MutationDefinition.Impact.TRADEOFF),
            new Addition(Locus.STAMINA, "developed_lungs", "developed_lungs", "developed_lungs", "developed_lungs",
                    MutationCategory.PHYSIOLOGICAL, MutationDefinition.Impact.BENEFICIAL),
            new Addition(Locus.TEMPER, "nervous", "uneasy", "uneasy", "nervous",
                    MutationCategory.BEHAVIORAL_SENSORY, MutationDefinition.Impact.HARMFUL),
            new Addition(Locus.ARCANA, "regeneration", "regeneration", "shadow_affinity", "regeneration",
                    MutationCategory.ARCANE, MutationDefinition.Impact.TRADEOFF)
    );

    private CanidBreedingContent() {}

    public static Definitions create() {
        GeneticsCatalog original = CanidStarterContent.create();
        GeneticsRuleset old = original.rulesets().get(CanidStarterContent.RULESET.id());
        Genome wolf = original.species().get(CanidStarterContent.WOLF).starterGenome();
        Genome fox = original.species().get(CanidStarterContent.FOX).starterGenome();
        var builder = GeneticsRuleset.builder(RULESET);
        old.alleles().values().forEach(allele -> builder.registerAllele("genesis", allele));
        // Carry every v1 pair forward exactly; build a separate immutable revision rather than replacing v1.
        for (Locus locus : Locus.values()) {
            for (AllelePair pair : basePairs(old, locus)) {
                var loci = new EnumMap<>(wolf.loci());
                loci.put(locus, pair);
                builder.registerExpression(locus, pair, old.resolveExpressions(new Genome(old.reference(), loci)).get(locus));
            }
        }
        for (Addition addition : ADDITIONS) {
            Locus locus = addition.locus();
            ContentId target = allele(locus, addition.allele());
            builder.registerAllele("genesis", new AlleleDefinition(target, locus));
            // FRAME is not extended; at these six loci the starters are the two original homozygotes.
            ContentId first = wolf.loci().get(locus).first();
            ContentId second = locus == Locus.ARCANA ? allele(locus, "shadow_affinity") : fox.loci().get(locus).first();
            builder.registerExpression(locus, new AllelePair(first, target), expression(locus, addition.withFirst()));
            builder.registerExpression(locus, new AllelePair(second, target), expression(locus, addition.withSecond()));
            builder.registerExpression(locus, new AllelePair(target, target), expression(locus, addition.homozygous()));
        }
        GeneticsRuleset genetics = builder.freeze();
        var catalog = GeneticsCatalog.builder(original.id(), 2).registerRuleset("genesis", genetics)
                .registerRig("genesis", new RigDefinition(CanidStarterContent.RIG, 1))
                .registerFamily("genesis", new FamilyDefinition(CanidStarterContent.FAMILY, 2, Set.of(CanidStarterContent.RIG), RULESET));
        original.species().values().forEach(species -> catalog.registerSpecies("genesis", new SpeciesDefinition(
                species.id(), 2, species.familyId(), species.rigId(), new Genome(RULESET, species.starterGenome().loci()))));

        var allowed = new EnumMap<MutationCategory, Set<Locus>>(MutationCategory.class);
        allowed.put(MutationCategory.COSMETIC, Set.of(Locus.PIGMENT));
        allowed.put(MutationCategory.PHYSIOLOGICAL, Set.of(Locus.LIMBS, Locus.MUSCLE, Locus.STAMINA));
        allowed.put(MutationCategory.BEHAVIORAL_SENSORY, Set.of(Locus.TEMPER));
        allowed.put(MutationCategory.ARCANE, Set.of(Locus.ARCANA));
        var mutations = new ArrayList<MutationDefinition>();
        for (Addition addition : ADDITIONS) {
            mutations.add(new MutationDefinition(ContentId.parse("genesis:mutation/" + addition.allele()),
                    addition.category(), addition.locus(), allele(addition.locus(), addition.allele()), addition.impact(),
                    Set.copyOf(basePairs(old, addition.locus()))));
        }
        return new Definitions(catalog.freeze(), new MutationRules(MUTATIONS, genetics, allowed, mutations),
                CanidPhenotypeContent.create(genetics));
    }

    private static List<AllelePair> basePairs(GeneticsRuleset genetics, Locus locus) {
        var alleles = genetics.alleles().values().stream().filter(allele -> allele.locus() == locus)
                .map(AlleleDefinition::id).sorted().toList();
        var pairs = new ArrayList<AllelePair>();
        for (int a = 0; a < alleles.size(); a++) {
            for (int b = a; b < alleles.size(); b++) pairs.add(new AllelePair(alleles.get(a), alleles.get(b)));
        }
        return pairs;
    }

    static ContentId allele(Locus locus, String variant) { return id("allele", locus, variant); }
    static ContentId expression(Locus locus, String variant) { return id("expression", locus, variant); }
    private static ContentId id(String kind, Locus locus, String variant) {
        return ContentId.parse("genesis:" + kind + "/" + locus.name().toLowerCase(Locale.ROOT) + "/" + variant);
    }
}
