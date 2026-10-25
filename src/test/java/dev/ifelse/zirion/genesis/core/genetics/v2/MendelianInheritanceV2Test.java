package dev.ifelse.zirion.genesis.core.genetics.v2;

import dev.ifelse.zirion.genesis.core.breeding.SeededAlleleRandom;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.ifelse.zirion.genesis.core.genetics.v2.V2TestContent.*;
import static org.junit.jupiter.api.Assertions.*;

class MendelianInheritanceV2Test {
    private static Genome heterozygous(FamilyGeneSchema schema) {
        var pairs = new TreeMap<ContentId, AllelePair>();
        schema.genes().forEach((id, gene) -> {
            var variants = List.copyOf(gene.variants());
            pairs.put(id, new AllelePair(variants.getFirst(), variants.getLast()));
        });
        return schema.createGenome(pairs);
    }

    @Test
    void inheritsExactlyOneCopyFromEachPureFounderAtEveryHeritageGene() {
        var schema = schema("canids");
        var wolf = genome(schema, "wolf");
        var fox = genome(schema, "fox");
        var child = MendelianInheritance.inherit(wolf, fox, schema, new SeededAlleleRandom(42));
        for (ContentId gene : FamilyGeneSchema.HERITAGE_GENES) {
            assertEquals(new AllelePair(id("wolf"), id("fox")), child.genes().get(gene));
        }
        assertEquals(Map.of(id("wolf"), 3, id("fox"), 3), schema.founderCopyCounts(child));
        assertEquals(Map.of(id("wolf"), 6), schema.founderCopyCounts(wolf));
        assertEquals(Map.of(id("fox"), 6), schema.founderCopyCounts(fox));
    }

    @Test
    void homozygotesStillConsumeExactlyTwoChoicesPerGeneForEachFamily() {
        for (String family : List.of("canids", "runners", "heavies", "avians")) {
            var schema = schema(family);
            String founder = schema.founders().iterator().next().path();
            var parent = genome(schema, founder);
            var choices = new AtomicInteger();
            assertEquals(parent, MendelianInheritance.inherit(parent, parent, schema,
                    () -> choices.incrementAndGet() % 2 == 0));
            assertEquals(2 * schema.genes().size(), choices.get());
        }
    }

    @Test
    void invalidSecondParentAndUnknownDigestAreRejectedBeforeAnyRandomChoice() {
        var schema = schema("canids");
        var parent = genome(schema, "wolf");
        var invalidPairs = new TreeMap<>(parent.genes());
        invalidPairs.put(id("tail_form"), new AllelePair(id("unknown"), id("standard")));
        var invalidVariant = new Genome(parent.geneSchema(), parent.familyId(), invalidPairs);
        var missing = new TreeMap<>(parent.genes());
        missing.remove(id("temper"));
        var missingGene = new Genome(parent.geneSchema(), parent.familyId(), missing);
        var differentHash = new Genome(fingerprint("e"), parent.familyId(), parent.genes());
        var foreignSchema = schema("runners");
        var foreign = genome(foreignSchema, "horse");
        for (var invalid : List.of(invalidVariant, missingGene, differentHash, foreign)) {
            var choices = new AtomicInteger();
            assertThrows(IllegalArgumentException.class,
                    () -> MendelianInheritance.inherit(parent, invalid, schema, () -> choices.incrementAndGet() > 0));
            assertEquals(0, choices.get());
            assertThrows(IllegalArgumentException.class,
                    () -> MendelianInheritance.inherit(invalid, parent, schema, () -> choices.incrementAndGet() > 0));
            assertEquals(0, choices.get());
        }
    }

    @Test
    void backcrossCanLoseAnInheritedFounderWithoutRequiringHistoricalFormChanges() {
        var schema = schema("canids");
        var wolf = genome(schema, "wolf");
        var hybrid = MendelianInheritance.inherit(wolf, genome(schema, "fox"), schema, () -> false);
        // Canonical fox/wolf ordering: choosing second from this hybrid keeps wolf.
        var child = MendelianInheritance.inherit(hybrid, wolf, schema, () -> true);
        assertEquals(Map.of(id("wolf"), 6), schema.founderCopyCounts(child));
        assertEquals(Map.of(id("wolf"), 3, id("fox"), 3), schema.founderCopyCounts(hybrid));
        // Historical roots and graph resolution deliberately belong outside the genome.
    }

    @Test
    void replayDoesNotDependOnDefinitionOrGenomeMapInsertionOrder() {
        var schema = schema("canids");
        var parent = heterozygous(schema);
        var genes = new ArrayList<>(schema.genes().keySet());
        Collections.reverse(genes);
        var definitions = new LinkedHashMap<ContentId, GeneDefinition>();
        var pairs = new LinkedHashMap<ContentId, AllelePair>();
        genes.forEach(gene -> { definitions.put(gene, schema.genes().get(gene)); pairs.put(gene, parent.genes().get(gene)); });
        var reordered = new FamilyGeneSchema(schema.reference(), schema.familyId(), schema.founders(), definitions);
        var replayParent = reordered.createGenome(pairs);
        var expected = MendelianInheritance.inherit(parent, parent, schema, new SeededAlleleRandom(42));
        assertEquals(expected, MendelianInheritance.inherit(replayParent, replayParent, reordered, new SeededAlleleRandom(42)));
        assertEquals(expected, MendelianInheritance.inherit(parent, parent, schema, new SeededAlleleRandom(42)));
    }

    @Test
    void matchesIndependentPythonGeneOrderAndCompleteOffspringVector() throws Exception {
        var vector = new Properties();
        try (var input = getClass().getResourceAsStream("/inheritance-v2-vector.properties")) {
            assertNotNull(input);
            vector.load(input);
        }
        var schema = schema("canids");
        var parent = heterozygous(schema);
        long seed = Long.parseLong(vector.getProperty("seed"));
        assertEquals(MendelianInheritance.ALGORITHM.toString(), vector.getProperty("algorithm"));
        assertEquals(schema.reference().sha256(), vector.getProperty("schemaSha256"));
        assertEquals(schema.familyId().toString(), vector.getProperty("family"));
        var choices = new StringBuilder();
        var random = new SeededAlleleRandom(seed);
        var child = MendelianInheritance.inherit(parent, parent, schema, () -> {
            boolean second = random.chooseSecond();
            choices.append(second ? '1' : '0');
            return second;
        });
        assertEquals(vector.getProperty("choices"), choices.toString());
        assertEquals(Integer.parseInt(vector.getProperty("geneCount")), child.genes().size());
        var order = List.copyOf(child.genes().keySet());
        for (int i = 0; i < order.size(); i++) {
            ContentId gene = ContentId.parse(vector.getProperty("gene." + i + ".id"));
            assertEquals(gene, order.get(i));
            assertEquals(new AllelePair(ContentId.parse(vector.getProperty("gene." + i + ".first")),
                    ContentId.parse(vector.getProperty("gene." + i + ".second"))), child.genes().get(gene));
        }
    }

    @Test
    void mendelianFrequenciesAndGeneIndependenceHoldWithFixedSeed() {
        var schema = schema("canids");
        var parent = heterozygous(schema);
        var choices = new SeededAlleleRandom(20261025L);
        ContentId geneA = id("body_frame");
        ContentId geneB = id("limb_form");
        ContentId firstA = parent.genes().get(geneA).first();
        ContentId firstB = parent.genes().get(geneB).first();
        int[] counts = new int[3];
        int bothFirst = 0;
        int sample = 20_000;
        for (int i = 0; i < sample; i++) {
            var child = MendelianInheritance.inherit(parent, parent, schema, choices);
            var a = child.genes().get(geneA);
            var b = child.genes().get(geneB);
            int copies = (a.first().equals(firstA) ? 1 : 0) + (a.second().equals(firstA) ? 1 : 0);
            counts[copies]++;
            if (copies == 2 && b.equals(new AllelePair(firstB, firstB))) bothFirst++;
            assertEquals(6, schema.founderCopyCounts(child).values().stream().mapToInt(Integer::intValue).sum());
        }
        double[] probabilities = {0.25, 0.5, 0.25};
        for (int i = 0; i < counts.length; i++) {
            double p = probabilities[i];
            assertEquals(sample * p, counts[i], 6 * Math.sqrt(sample * p * (1 - p)) + 1);
        }
        double joint = 1.0 / 16;
        assertEquals(sample * joint, bothFirst, 6 * Math.sqrt(sample * joint * (1 - joint)) + 1);
    }
}
