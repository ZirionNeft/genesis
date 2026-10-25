package dev.ifelse.zirion.genesis.core.phenotype.v2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.v2.GeneDefinition;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PhenotypeResolverTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(PhenotypeResolverTest.class.getClassLoader());
    private static final PhenotypeResolver RESOLVER = new PhenotypeResolver(BUNDLE);
    private static final JsonObject CONTENT = JsonParser.parseString(new String(BUNDLE.documents()
            .get("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject();

    @Test
    void all111PairRulesMatchIndependentRationalPythonVectors() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/phenotype-v2-vectors.json"), StandardCharsets.UTF_8)) {
            JsonObject vectors = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals(BUNDLE.expressionRules().references().expression().sha256(), vectors.get("expressionSha256").getAsString());
            assertEquals(111, vectors.getAsJsonArray("cases").size());
            for (var row : vectors.getAsJsonArray("cases")) {
                var vector = row.getAsJsonObject();
                var schema = BUNDLE.families().get(ContentId.parse(vector.get("family").getAsString()));
                ContentId founder = ContentId.parse(vector.get("founder").getAsString());
                var pairs = new TreeMap<>(neutral(schema, founder).genes());
                String[] pair = vector.get("pair").getAsString().split("\\|");
                ContentId gene = id(vector.get("gene").getAsString().toLowerCase(java.util.Locale.ROOT));
                pairs.put(gene, new AllelePair(id(pair[0]), id(pair[1])));
                var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(founder), schema.createGenome(pairs));
                String label = gene + " " + vector.get("pair");
                assertEquals(vector.get("state").getAsString(), result.geneStates().get(gene), label);
                for (Stat stat : Stat.values()) assertDecimal(vector.getAsJsonObject("stats").get(stat.key()).getAsString(), result.stats().get(stat));
                vector.getAsJsonObject("visual").entrySet().forEach(entry -> assertEquals(entry.getValue().getAsString(), result.visualStates().get(entry.getKey()), label));
                vector.getAsJsonObject("utility").entrySet().forEach(entry -> assertDecimal(entry.getValue().getAsString(), result.utility().get(entry.getKey())));
                assertEquals(schema.genes().size() - 3, result.geneStates().size());
                assertFalse(result.geneStates().containsKey(FamilyGeneSchema.HERITAGE_GENES.getFirst()));
            }
        }
    }

    @Test
    void allTwelveNeutralFoundersUseTheirOwnSevenReferenceStats() {
        int count = 0;
        for (var family : BUNDLE.hybridGraph().families().values()) for (ContentId founder : family.founders()) {
            var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(founder), neutral(BUNDLE.families().get(family.id()), founder));
            assertEquals(BUNDLE.expressionRules().references(), result.references());
            var reference = CONTENT.getAsJsonArray("forms").asList().stream().map(element -> element.getAsJsonObject())
                    .filter(form -> form.get("formId").getAsString().equals(founder.toString())).findFirst().orElseThrow().getAsJsonObject("baseStats");
            for (Stat stat : Stat.values()) assertDecimal(reference.get(stat.key()).getAsString(), result.stats().get(stat));
            count++;
        }
        assertEquals(12, count);
    }

    @Test
    void equalWolfFoxMixtureMatchesGddExampleAndRoundsIntegerHalvesUp() {
        var genome = mixture(3, "wolf", "fox");
        var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("wolf_fox")), genome);
        assertDecimal("100", result.stats().get(Stat.HP)); assertDecimal("5.65", result.stats().get(Stat.SPEED_BPS));
        assertDecimal("14", result.stats().get(Stat.ATTACK)); // (16+11)/2 = 13.5
        assertDecimal("5", result.stats().get(Stat.ARMOR));
        assertDecimal("110", result.stats().get(Stat.STAMINA));
        assertDecimal("3", result.stats().get(Stat.CARGO_SLOTS));
        assertDecimal("21", result.stats().get(Stat.SENSE_RADIUS));
    }

    @Test
    void sixthsAreNotRoundedBeforeTheFinalHalfUp() {
        var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("wolf_fox")), mixture(1, "wolf", "fox"));
        assertDecimal("87", result.stats().get(Stat.HP)); assertDecimal("5.88", result.stats().get(Stat.SPEED_BPS));
        var tie = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("wolf_hyena")), mixture(5, "wolf", "hyena"));
        assertDecimal("123", tie.stats().get(Stat.HP)); // (120*5+135)/6 = 122.5
    }

    @Test
    void historicalHybridAndTriadNeverChooseThePhysiologicalBaseline() {
        var fox = neutral(BUNDLE.families().get(id("canids")), id("fox"));
        var pure = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("fox")), fox);
        for (String history : new String[]{"wolf_fox", "canid_triad"}) {
            var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id(history)), fox);
            assertEquals(pure.stats(), result.stats());
            assertEquals(id("fox"), result.forms().effectiveSignatureForm().id());
            assertEquals(id(history), result.forms().historicalForm().id());
        }
        assertEquals(12, BUNDLE.expressionRules().founderStats().size());
        assertFalse(BUNDLE.expressionRules().founderStats().containsKey(id("wolf_fox")));
    }

    @Test
    void percentEffectsAreAddedRatherThanMultipliedAndFlatEffectsComeLater() {
        var genome = change(wolf(), "body_frame", "large", "normal");
        genome = change(genome, "limb_form", "long", "standard");
        genome = change(genome, "bone_strength", "dense", "normal");
        var result = wolfResult(genome);
        assertDecimal("127", result.stats().get(Stat.HP)); // 120*(1+.08-.02)
        assertDecimal("5.14", result.stats().get(Stat.SPEED_BPS)); // 5.3*(1-.03+.02-.02)
        assertDecimal("8", result.stats().get(Stat.ARMOR));
    }

    @Test
    void aggregateClampPrecedesFlatDeltaAndMinimumAndRounding() {
        var rule = new ExpressionRules.PairRule("synthetic", Map.of(Stat.HP, decimal("0.90"), Stat.SPEED_BPS, decimal("-0.90"), Stat.ARMOR, decimal("0.90")),
                Map.of(Stat.ARMOR, decimal("100")), Map.of(), Map.of("body", "normal"));
        var result = new PhenotypeResolver(withBodyRule(rule)).resolve(BUNDLE.hybridGraph().identity(id("wolf")), wolf());
        assertDecimal("162", result.stats().get(Stat.HP)); // 120*1.35
        assertDecimal("3.98", result.stats().get(Stat.SPEED_BPS)); // 5.3*.75 = 3.975
        assertDecimal("109", result.stats().get(Stat.ARMOR)); // 7*1.35+100, no clamp after the flat contribution
        var negative = new ExpressionRules.PairRule("synthetic", Map.of(), Map.of(Stat.HP, decimal("-999"), Stat.ARMOR, decimal("-999"),
                Stat.SPEED_BPS, decimal("-999"), Stat.STAMINA, decimal("-999")), Map.of(), Map.of("body", "normal"));
        var minimum = new PhenotypeResolver(withBodyRule(negative)).resolve(BUNDLE.hybridGraph().identity(id("wolf")), wolf());
        assertDecimal("1", minimum.stats().get(Stat.HP)); assertDecimal("0", minimum.stats().get(Stat.ARMOR));
        assertDecimal("0.10", minimum.stats().get(Stat.SPEED_BPS)); assertDecimal("1", minimum.stats().get(Stat.STAMINA));
    }

    @Test
    void recessiveCarriersRetainTheirGenotypeWithoutExpressingTheSpecialVisual() {
        var carrier = change(change(wolf(), "pattern", "plain", "spotted"), "eye_appearance", "ordinary", "luminous");
        var before = new TreeMap<>(carrier.genes());
        var result = wolfResult(carrier);
        assertEquals("plain", result.visualStates().get("pattern")); assertEquals("ordinary", result.visualStates().get("eyes"));
        assertEquals(before, carrier.genes());
        var expressed = wolfResult(change(change(carrier, "pattern", "spotted", "spotted"), "eye_appearance", "luminous", "luminous"));
        assertEquals("spotted", expressed.visualStates().get("pattern")); assertEquals("luminous", expressed.visualStates().get("eyes"));
    }

    @Test
    void familyVisualsIncludeExplicitNoneAndNeverInventOtherFamilySlots() {
        for (String family : new String[]{"runners", "heavies"}) {
            var schema = BUNDLE.families().get(id(family)); var founder = schema.founders().iterator().next();
            var genome = change(neutral(schema, founder), "horn_form", "none", "none");
            var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(founder), genome);
            assertEquals("none", result.visualStates().get("horns"));
            assertFalse(result.visualStates().containsKey("wings"));
        }
        var avians = BUNDLE.families().get(id("avians")); var raven = neutral(avians, id("raven"));
        var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("raven")), raven);
        assertTrue(result.visualStates().containsKey("wings")); assertTrue(result.visualStates().containsKey("beak"));
        assertFalse(result.visualStates().containsKey("horns")); assertFalse(wolfResult(wolf()).visualStates().containsKey("horns"));
    }

    @Test
    void utilityValuesRemainSeparateFromSevenPhysiologicalStats() {
        var genome = change(change(change(wolf(), "coat_density", "thick", "thick"), "muscle", "normal", "strong"), "resource_trait", "gifted", "gifted");
        var result = wolfResult(genome);
        assertDecimal("0.90", result.utility().get("coldDamageScale"));
        assertDecimal("1.06", result.utility().get("hotStaminaDrainScale"));
        assertDecimal("1.03", result.utility().get("attackStaminaCostScale"));
        assertDecimal("4", result.utility().get("extraResourcePerCycles"));
        assertDecimal("100", result.stats().get(Stat.STAMINA));
        assertEquals(7, result.stats().values().size());
    }

    @Test
    void awakeArcanaProducesOnePotentialSpellForEveryElementPair() {
        assertEquals(Optional.empty(), wolfResult(wolf()).innateSpellPotential());
        var awake = change(wolf(), "arcana", "awake", "quiet");
        for (var pair : BUNDLE.expressionRules().genes().get(ExpressionRules.ELEMENT).pairs().entrySet()) {
            var genome = change(awake, "element", pair.getKey().first().path(), pair.getKey().second().path());
            var result = wolfResult(genome);
            assertEquals(Optional.of(id(pair.getValue().state())), result.innateSpellPotential());
            assertEquals(Optional.empty(), wolfResult(change(genome, "arcana", "quiet", "quiet")).innateSpellPotential());
        }
    }

    @Test
    void outputsAndLoadedNestedDefinitionsAreImmutable() {
        var result = wolfResult(wolf());
        assertThrows(UnsupportedOperationException.class, () -> result.stats().values().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.geneStates().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.visualStates().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.utility().clear());
        assertThrows(UnsupportedOperationException.class, () -> BUNDLE.expressionRules().genes().clear());
        var gene = BUNDLE.expressionRules().genes().get(id("body_frame"));
        assertThrows(UnsupportedOperationException.class, () -> gene.families().clear());
        assertThrows(UnsupportedOperationException.class, () -> gene.pairs().clear());
        assertThrows(UnsupportedOperationException.class, () -> gene.pairs().values().iterator().next().percentDelta().clear());
        var values = new EnumMap<>(result.stats().values()); var copy = new StatValues(values); values.clear();
        assertEquals(result.stats(), copy);
    }

    @Test
    void invalidGenomeHistoryOrSchemaFailsBeforeExpression() {
        var schema = BUNDLE.families().get(id("canids"));
        var pairs = new TreeMap<>(wolf().genes()); pairs.remove(id("muscle"));
        assertThrows(IllegalArgumentException.class, () -> wolfResult(new Genome(schema.reference(), schema.familyId(), pairs)));
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("fox")), wolf()));
        var wrongPin = new RegistryFingerprint(schema.reference().reference(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> wolfResult(new Genome(wrongPin, schema.familyId(), wolf().genes())));
    }

    @Test
    void bundleRejectsExpressionFromDifferentPinnedDefinitions() {
        var original = BUNDLE.expressionRules(); var refs = original.references();
        var stale = new ExpressionRules.References(new RegistryFingerprint(refs.expression().reference(), "0".repeat(64)),
                refs.geneSchema(), refs.hybridGraph(), refs.morphology(), refs.contentCatalogId(), refs.contentCatalogSha256());
        var rules = new ExpressionRules(stale, original.policy(), original.genes(), original.founderStats(), original.spellMap());
        assertThrows(IllegalArgumentException.class, () -> new CatalogBundle(BUNDLE.defaults(), BUNDLE.families(), BUNDLE.hybridGraph(),
                rules, BUNDLE.mutations(), BUNDLE.signaturePassives(), BUNDLE.contentCatalogId(), BUNDLE.contentCatalogSha256(), BUNDLE.documents()));
        var extraBase = new TreeMap<>(original.founderStats()); extraBase.put(id("wolf_fox"), extraBase.get(id("wolf")));
        var hybridBaseline = new ExpressionRules(refs, original.policy(), original.genes(), extraBase, original.spellMap());
        assertThrows(IllegalArgumentException.class, () -> new CatalogBundle(BUNDLE.defaults(), BUNDLE.families(), BUNDLE.hybridGraph(),
                hybridBaseline, BUNDLE.mutations(), BUNDLE.signaturePassives(), BUNDLE.contentCatalogId(), BUNDLE.contentCatalogSha256(), BUNDLE.documents()));
    }

    private static CatalogBundle withBodyRule(ExpressionRules.PairRule replacement) {
        // Arithmetic boundary fixture only; bypassing source-byte fingerprints is never a production load path.
        var original = BUNDLE.expressionRules(); var genes = new TreeMap<>(original.genes());
        var gene = genes.get(id("body_frame")); var pairs = new HashMap<>(gene.pairs());
        pairs.put(new AllelePair(id("normal"), id("normal")), replacement);
        genes.put(id("body_frame"), new ExpressionRules.GeneRules(gene.families(), gene.visualSlot(), pairs));
        var rules = new ExpressionRules(original.references(), original.policy(), genes, original.founderStats(), original.spellMap());
        return new CatalogBundle(BUNDLE.defaults(), BUNDLE.families(), BUNDLE.hybridGraph(), rules, BUNDLE.mutations(), BUNDLE.signaturePassives(),
                BUNDLE.contentCatalogId(), BUNDLE.contentCatalogSha256(), BUNDLE.documents());
    }

    private static Genome neutral(FamilyGeneSchema schema, ContentId founder) {
        var defaults = new HashMap<ContentId, ContentId>();
        for (var value : CONTENT.getAsJsonArray("genes")) {
            var gene = value.getAsJsonObject();
            defaults.put(id(gene.get("id").getAsString().toLowerCase(java.util.Locale.ROOT)), id(gene.get("defaultVariant").getAsString()));
        }
        var pairs = new TreeMap<ContentId, AllelePair>();
        schema.genes().forEach((id, definition) -> {
            ContentId allele = definition.kind() == GeneDefinition.Kind.HERITAGE ? founder : defaults.getOrDefault(id, definition.variants().iterator().next());
            pairs.put(id, new AllelePair(allele, allele));
        });
        return schema.createGenome(pairs);
    }

    private static Genome mixture(int copiesA, String a, String b) {
        var schema = BUNDLE.families().get(id("canids")); var pairs = new TreeMap<>(neutral(schema, id(a)).genes());
        int copy = 0;
        for (ContentId gene : FamilyGeneSchema.HERITAGE_GENES) {
            ContentId first = id(copy++ < copiesA ? a : b), second = id(copy++ < copiesA ? a : b);
            pairs.put(gene, new AllelePair(first, second));
        }
        return schema.createGenome(pairs);
    }

    private static Genome wolf() { return neutral(BUNDLE.families().get(id("canids")), id("wolf")); }
    private static PhenotypeResolver.Result wolfResult(Genome genome) { return RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("wolf")), genome); }
    private static Genome change(Genome genome, String gene, String a, String b) {
        var pairs = new TreeMap<>(genome.genes()); pairs.put(id(gene), new AllelePair(id(a), id(b)));
        return BUNDLE.families().get(genome.familyId()).createGenome(pairs);
    }
    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual, expected); assertEquals(0, decimal(expected).compareTo(actual), () -> expected + " != " + actual);
    }
}
