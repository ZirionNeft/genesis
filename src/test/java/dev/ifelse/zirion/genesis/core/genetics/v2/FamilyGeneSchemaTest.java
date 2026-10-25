package dev.ifelse.zirion.genesis.core.genetics.v2;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static dev.ifelse.zirion.genesis.core.genetics.v2.V2TestContent.*;
import static org.junit.jupiter.api.Assertions.*;

class FamilyGeneSchemaTest {
    @Test
    void actualGeneDefinitionsHaveFamilySpecificCountsAndParts() {
        for (var family : Map.of("canids", 26, "runners", 27, "heavies", 27, "avians", 28).entrySet()) {
            var schema = schema(family.getKey());
            assertEquals(family.getValue(), schema.genes().size());
            String founder = schema.founders().iterator().next().path();
            assertEquals(family.getValue(), genome(schema, founder).genes().size());
            assertEquals(family.getKey().equals("runners") || family.getKey().equals("heavies"),
                    schema.genes().containsKey(id("horn_form")));
            assertEquals(family.getKey().equals("avians"), schema.genes().containsKey(id("wing_form")));
            assertEquals(family.getKey().equals("avians"), schema.genes().containsKey(id("beak_form")));
            assertEquals(3, schema.genes().values().stream().filter(g -> !g.mutationAllowed()).count());
        }
    }

    @Test
    void variantIdsAreScopedToGenesRatherThanGloballyUnique() {
        var schema = schema("canids");
        var pairs = pairs(schema, "wolf");
        pairs.put(id("body_frame"), new AllelePair(id("normal"), id("normal")));
        pairs.put(id("muscle"), new AllelePair(id("normal"), id("normal")));
        assertDoesNotThrow(() -> schema.createGenome(pairs));
        pairs.put(id("body_frame"), new AllelePair(id("normal"), id("strong")));
        assertThrows(IllegalArgumentException.class, () -> schema.createGenome(pairs));
    }

    @Test
    void unknownVariantsAndCrossFamilyFoundersAreRejected() {
        var schema = schema("canids");
        for (ContentId variant : List.of(id("horse"), id("unknown"))) {
            var pairs = pairs(schema, "wolf");
            pairs.put(id("heritage_2"), new AllelePair(id("wolf"), variant));
            assertThrows(IllegalArgumentException.class, () -> schema.createGenome(pairs));
        }
    }

    @Test
    void everyMissingOrExtraGeneIsRejected() {
        var schema = schema("canids");
        for (ContentId gene : schema.genes().keySet()) {
            var pairs = pairs(schema, "wolf");
            pairs.remove(gene);
            assertThrows(IllegalArgumentException.class, () -> schema.createGenome(pairs), gene.toString());
        }
        var pairs = pairs(schema, "wolf");
        pairs.put(id("wing_form"), new AllelePair(id("broad"), id("broad")));
        assertThrows(IllegalArgumentException.class, () -> schema.createGenome(pairs));
    }

    @Test
    void validationRequiresExactSchemaIdVersionDigestAndFamily() {
        var schema = schema("canids");
        var original = genome(schema, "wolf");
        var differentDigest = new Genome(fingerprint("f"), original.familyId(), original.genes());
        var differentRegistry = new Genome(new RegistryFingerprint(new RulesetVersion(id("other_genes"), 2),
                original.geneSchema().sha256()), original.familyId(), original.genes());
        var differentFamily = new Genome(original.geneSchema(), id("runners"), original.genes());
        for (var invalid : List.of(differentDigest, differentRegistry, differentFamily)) {
            assertThrows(IllegalArgumentException.class, () -> schema.validate(invalid));
            assertThrows(IllegalArgumentException.class, () -> schema.founderCopyCounts(invalid));
        }
        for (int version : List.of(1, 3)) {
            var reference = new RegistryFingerprint(new RulesetVersion(id("genes"), version), "a".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> new Genome(reference, schema.familyId(), original.genes()));
            assertThrows(IllegalArgumentException.class,
                    () -> new FamilyGeneSchema(reference, schema.familyId(), schema.founders(), schema.genes()));
        }
    }

    @Test
    void requiresCanonicalFullSha256WithoutPretendingToAuthenticateIt() {
        var reference = new RulesetVersion(id("genes"), 2);
        assertEquals("a".repeat(64), new RegistryFingerprint(reference, "a".repeat(64)).sha256());
        for (String invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> new RegistryFingerprint(reference, invalid));
        }
    }

    @Test
    void copiesAllMutableInputsAndExposesOnlyImmutableSnapshots() {
        var source = schema("canids");
        var founders = new HashSet<>(source.founders());
        var definitions = new HashMap<>(source.genes());
        var variants = new HashSet<>(Set.of(id("normal"), id("large")));
        definitions.put(id("body_frame"), new GeneDefinition(id("body_frame"), variants, GeneDefinition.Kind.TRAIT));
        var schema = new FamilyGeneSchema(source.reference(), source.familyId(), founders, definitions);
        var input = pairs(schema, "wolf");
        var genome = schema.createGenome(input);
        founders.clear();
        definitions.clear();
        variants.clear();
        input.clear();
        assertEquals(3, schema.founders().size());
        assertEquals(26, schema.genes().size());
        assertEquals(2, schema.genes().get(id("body_frame")).variants().size());
        assertEquals(26, genome.genes().size());
        assertThrows(UnsupportedOperationException.class, () -> schema.founders().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.genes().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.genes().get(id("body_frame")).variants().clear());
        assertThrows(UnsupportedOperationException.class, () -> genome.genes().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.founderCopyCounts(genome).clear());
    }

    @Test
    void canonicalGeneOrderDoesNotDependOnMapInsertionOrder() {
        var source = schema("canids");
        var keys = new ArrayList<>(source.genes().keySet());
        Collections.reverse(keys);
        var reversedDefinitions = new LinkedHashMap<ContentId, GeneDefinition>();
        var reversedPairs = new LinkedHashMap<ContentId, AllelePair>();
        var original = genome(source, "wolf");
        keys.forEach(id -> { reversedDefinitions.put(id, source.genes().get(id)); reversedPairs.put(id, original.genes().get(id)); });
        var reversed = new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), reversedDefinitions);
        assertEquals(List.copyOf(source.genes().keySet()), List.copyOf(reversed.genes().keySet()));
        assertEquals(original, reversed.createGenome(reversedPairs));
        assertEquals(List.copyOf(source.genes().keySet()), List.copyOf(reversed.createGenome(reversedPairs).genes().keySet()));
    }

    @Test
    void exactlyThreeUnmutatableHeritageGenesMustUseExactlyTheFamilyFounders() {
        var source = schema("canids");
        for (ContentId gene : FamilyGeneSchema.HERITAGE_GENES) {
            var missing = new TreeMap<>(source.genes());
            missing.remove(gene);
            assertThrows(IllegalArgumentException.class,
                    () -> new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), missing));
            var ordinary = new TreeMap<>(source.genes());
            ordinary.put(gene, new GeneDefinition(gene, source.founders(), GeneDefinition.Kind.TRAIT));
            assertThrows(IllegalArgumentException.class,
                    () -> new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), ordinary));
            var foreign = new TreeMap<>(source.genes());
            foreign.put(gene, new GeneDefinition(gene, Set.of(id("horse"), id("deer"), id("antelope")), GeneDefinition.Kind.HERITAGE));
            assertThrows(IllegalArgumentException.class,
                    () -> new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), foreign));
        }
        var fourth = new TreeMap<>(source.genes());
        fourth.put(id("heritage_4"), new GeneDefinition(id("heritage_4"), source.founders(), GeneDefinition.Kind.HERITAGE));
        assertThrows(IllegalArgumentException.class,
                () -> new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), fourth));
    }

    @Test
    void requiresThreeFoundersAndMatchingGeneMapKeys() {
        var source = schema("canids");
        assertThrows(IllegalArgumentException.class, () -> new FamilyGeneSchema(source.reference(), source.familyId(),
                Set.of(id("wolf"), id("fox")), source.genes()));
        var misplaced = new TreeMap<>(source.genes());
        misplaced.put(id("body_frame"), source.genes().get(id("head_form")));
        assertThrows(IllegalArgumentException.class,
                () -> new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), misplaced));
        assertThrows(IllegalArgumentException.class, () -> new GeneDefinition(id("empty"), Set.of(), GeneDefinition.Kind.TRAIT));
    }

    @Test
    void threeHeritagePairsGiveSixCopiesWithoutPedigreeOrAgeState() {
        var schema = schema("canids");
        assertEquals(Map.of(id("wolf"), 6), schema.founderCopyCounts(genome(schema, "wolf")));
        var pairs = pairs(schema, "wolf");
        pairs.put(id("heritage_1"), new AllelePair(id("wolf"), id("fox")));
        pairs.put(id("heritage_2"), new AllelePair(id("hyena"), id("fox")));
        pairs.put(id("heritage_3"), new AllelePair(id("wolf"), id("hyena")));
        assertEquals(Map.of(id("wolf"), 2, id("fox"), 2, id("hyena"), 2), schema.founderCopyCounts(schema.createGenome(pairs)));
        assertEquals(FamilyGeneSchema.HERITAGE_COPY_COUNT,
                schema.founderCopyCounts(schema.createGenome(pairs)).values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void newNamespacedTraitNeedsNoEnumChangeAndHasItsOwnDefinition() {
        var source = schema("canids");
        var crest = ContentId.parse("fixture:crest");
        var variants = Set.of(ContentId.parse("fixture:plain"), ContentId.parse("fixture:tufted"));
        var definitions = new TreeMap<>(source.genes());
        definitions.put(crest, new GeneDefinition(crest, variants, GeneDefinition.Kind.TRAIT));
        var extended = new FamilyGeneSchema(fingerprint("c"), source.familyId(), source.founders(), definitions);
        var pairs = pairs(extended, "wolf");
        assertEquals(27, extended.createGenome(pairs).genes().size());
        assertThrows(IllegalArgumentException.class, () -> source.validate(extended.createGenome(pairs)));
    }

    @Test
    void nullAndEmptySnapshotsCannotEnterTheModel() {
        var source = schema("canids");
        assertThrows(IllegalArgumentException.class, () -> new Genome(source.reference(), source.familyId(), Map.of()));
        var missingPair = new HashMap<>(pairs(source, "wolf"));
        missingPair.put(id("body_frame"), null);
        assertThrows(NullPointerException.class, () -> source.createGenome(missingPair));
        var missingDefinition = new HashMap<>(source.genes());
        missingDefinition.put(id("body_frame"), null);
        assertThrows(NullPointerException.class,
                () -> new FamilyGeneSchema(source.reference(), source.familyId(), source.founders(), missingDefinition));
    }
}
