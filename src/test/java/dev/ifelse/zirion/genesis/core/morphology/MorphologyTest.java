package dev.ifelse.zirion.genesis.core.morphology;

import dev.ifelse.zirion.genesis.core.breeding.MendelianInheritance;
import dev.ifelse.zirion.genesis.core.content.CanidStarterContent;
import dev.ifelse.zirion.genesis.core.content.GeneticsCatalog;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.ifelse.zirion.genesis.core.morphology.MorphologyRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Appearance IDs are synthetic test plans, not claims about available Hytale assets. */
class MorphologyTest {
    private static final GeneticsCatalog CATALOG = CanidStarterContent.create();
    private static final GeneticsRuleset GENETICS = CATALOG.rulesets().get(CanidStarterContent.RULESET.id());
    private static final Genome WOLF = CATALOG.species().get(CanidStarterContent.WOLF).starterGenome();
    private static final Genome FOX = CATALOG.species().get(CanidStarterContent.FOX).starterGenome();
    private static final Set<Locus> VISUAL = EnumSet.range(Locus.FRAME, Locus.COVER);
    private static final RulesetVersion PROFILE = new RulesetVersion(ContentId.parse("fixture:morphology"), 1);

    private static Signature signature(Genome genome) {
        var expressions = GENETICS.resolveExpressions(genome);
        var traits = new EnumMap<Locus, ContentId>(Locus.class);
        VISUAL.forEach(locus -> traits.put(locus, expressions.get(locus)));
        return new Signature(traits);
    }

    private static Appearance appearance(String name) {
        return new Appearance(ContentId.parse("fixture:" + name), 1, CanidStarterContent.RIG);
    }

    private static MorphologyRules rules(Map<Signature, Appearance> appearances) {
        return new MorphologyRules(PROFILE, GENETICS, CanidStarterContent.RIG, VISUAL, appearances);
    }

    private static List<Genome> visualGenomes() {
        var result = new ArrayList<Genome>();
        enumerate(0, new EnumMap<>(WOLF.loci()), result);
        return result;
    }

    private static void enumerate(int index, EnumMap<Locus, AllelePair> loci, List<Genome> result) {
        if (index == VISUAL.size()) {
            result.add(new Genome(WOLF.ruleset(), loci));
            return;
        }
        Locus locus = Locus.values()[index];
        var alleles = GENETICS.alleles().values().stream().filter(allele -> allele.locus() == locus)
                .map(allele -> allele.id()).sorted().toList();
        for (int first = 0; first < alleles.size(); first++) {
            for (int second = first; second < alleles.size(); second++) {
                loci.put(locus, new AllelePair(alleles.get(first), alleles.get(second)));
                enumerate(index + 1, loci, result);
            }
        }
    }

    @Test
    void exactPlansCanSelectEitherModularOrPreparedWholeAppearances() {
        var modular = appearance("modular");
        var whole = appearance("whole");
        var rules = rules(Map.of(signature(WOLF), modular, signature(FOX), whole));
        assertEquals(new Resolved(signature(WOLF), modular), rules.resolve(WOLF));
        assertEquals(new Resolved(signature(FOX), whole), rules.resolve(FOX));
        assertEquals(PROFILE, rules.reference());
        assertEquals(GENETICS.reference(), rules.geneticsReference());
        assertFalse(rules.coverage().complete());
    }

    @Test
    void missingHybridIsReportedWithoutFallbackOrGenomeChanges() {
        var hybrid = MendelianInheritance.inherit(WOLF, FOX, GENETICS, () -> false);
        var before = new Genome(hybrid.ruleset(), hybrid.loci());
        var rules = rules(Map.of(signature(WOLF), appearance("wolf"), signature(FOX), appearance("fox")));
        var result = assertInstanceOf(Unsupported.class, rules.resolve(hybrid));
        assertEquals(signature(hybrid), result.signature());
        assertEquals(ContentId.parse("genesis:expression/head/wolf_fox"), result.signature().traits().get(Locus.HEAD));
        assertEquals(ContentId.parse("genesis:expression/pigment/grey_orange"), result.signature().traits().get(Locus.PIGMENT));
        assertEquals(before, hybrid);
        assertEquals(result, rules.resolve(hybrid));
    }

    @Test
    void all729PhysicalGenotypesResolveTo486DistinctExpressionSignatures() {
        var genomes = visualGenomes();
        assertEquals(729, genomes.size());
        var plans = new HashMap<Signature, Appearance>();
        for (Genome genome : genomes) plans.computeIfAbsent(signature(genome), key -> appearance("plan_" + plans.size()));
        assertEquals(486, plans.size());
        var full = rules(plans);
        assertEquals(new Coverage(BigInteger.valueOf(486), 486), full.coverage());
        assertTrue(full.coverage().complete());
        for (Genome genome : genomes) {
            var result = assertInstanceOf(Resolved.class, full.resolve(genome));
            assertEquals(plans.get(signature(genome)), result.appearance());
        }
        Signature missing = signature(WOLF);
        plans.remove(missing);
        var incomplete = rules(plans);
        assertEquals(485, incomplete.coverage().registered());
        assertFalse(incomplete.coverage().complete());
        assertInstanceOf(Unsupported.class, incomplete.resolve(WOLF));
        assertInstanceOf(Resolved.class, full.resolve(WOLF));
    }

    @Test
    void unknownMisplacedPartialOrWrongRigPlansAndInvalidGenomesAreRejected() {
        var traits = new EnumMap<>(signature(WOLF).traits());
        traits.put(Locus.HEAD, ContentId.parse("missing:head"));
        assertThrows(IllegalArgumentException.class, () -> rules(Map.of(new Signature(traits), appearance("unknown"))));
        traits.put(Locus.HEAD, traits.get(Locus.FRAME));
        assertThrows(IllegalArgumentException.class, () -> rules(Map.of(new Signature(traits), appearance("misplaced"))));
        traits.remove(Locus.HEAD);
        assertThrows(IllegalArgumentException.class, () -> rules(Map.of(new Signature(traits), appearance("partial"))));
        var wrongRig = new Appearance(ContentId.parse("fixture:wrong"), 1, ContentId.parse("fixture:another_rig"));
        assertThrows(IllegalArgumentException.class, () -> rules(Map.of(signature(WOLF), wrongRig)));
        assertThrows(IllegalArgumentException.class, () -> new Appearance(ContentId.parse("fixture:bad_version"), 0, CanidStarterContent.RIG));
        assertThrows(IllegalArgumentException.class,
                () -> new MorphologyRules(PROFILE, GENETICS, CanidStarterContent.RIG, Set.of(), Map.of()));
        var empty = rules(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> empty.resolve(new Genome(new RulesetVersion(WOLF.ruleset().id(), 2), WOLF.loci())));
        var badGenome = new EnumMap<>(WOLF.loci());
        badGenome.put(Locus.HEAD, WOLF.loci().get(Locus.FRAME));
        assertThrows(IllegalArgumentException.class, () -> empty.resolve(new Genome(WOLF.ruleset(), badGenome)));
    }

    @Test
    void signaturesPlanMapsAndDeclaredLociAreImmutableSnapshots() {
        var traits = new EnumMap<>(signature(WOLF).traits());
        var signature = new Signature(traits);
        traits.clear();
        var plans = new HashMap<Signature, Appearance>();
        plans.put(signature, appearance("snapshot"));
        var loci = EnumSet.copyOf(VISUAL);
        var rules = new MorphologyRules(PROFILE, GENETICS, CanidStarterContent.RIG, loci, plans);
        loci.clear();
        plans.clear();
        assertEquals(new Resolved(signature, appearance("snapshot")), rules.resolve(WOLF));
        assertThrows(UnsupportedOperationException.class, () -> signature.traits().clear());
    }
}
