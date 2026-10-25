package dev.ifelse.zirion.genesis.config;

import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.content.v2.HybridGraph;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.GeneDefinition;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;
import dev.ifelse.zirion.genesis.core.phenotype.v2.Stat;
import dev.ifelse.zirion.genesis.core.phenotype.v2.StatValues;
import dev.ifelse.zirion.genesis.core.mutation.v2.MutationCatalog;
import dev.ifelse.zirion.genesis.core.role.SignaturePassiveCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.ifelse.zirion.genesis.config.CatalogDocument.*;
import static dev.ifelse.zirion.genesis.config.StrictJson.error;

/** Validates all six sources before returning any frozen v2 definitions. No Python, fallback or allowlist. */
public final class CatalogLoader {
    private static final Set<String> STATS = Set.of("hp", "attack", "armor", "speedBps", "stamina", "cargoSlots", "senseRadius");
    private static final Set<String> FAMILIES = Set.of("genesis:canids", "genesis:runners", "genesis:heavies", "genesis:avians");

    public CatalogBundle loadBundled(ClassLoader resources) {
        return loadSources(document -> resources.getResourceAsStream(document.resourcePath()));
    }

    public CatalogBundle loadDirectory(Path directory) {
        return loadSources(document -> {
            try { return Files.newInputStream(directory.resolve(document.filename())); }
            catch (IOException exception) { throw error("INPUT", document.filename() + "#", "Required catalog cannot be read"); }
        });
    }

    private CatalogBundle loadSources(Function<CatalogDocument, InputStream> source) {
        var bytes = new EnumMap<CatalogDocument, byte[]>(CatalogDocument.class);
        for (CatalogDocument document : values()) {
            try (InputStream input = source.apply(document)) {
                if (input == null) throw error("INPUT", document.filename() + "#", "Required catalog is unavailable");
                bytes.put(document, input.readNBytes(StrictJson.MAX_BYTES + 1));
            } catch (IOException exception) { throw error("INPUT", document.filename() + "#", "Cannot read catalog"); }
        }
        return load(bytes);
    }

    /** Copies supplied bytes before parsing; a failure never returns a partial bundle. */
    public CatalogBundle load(Map<CatalogDocument, byte[]> input) {
        if (!input.keySet().equals(Set.of(values()))) throw error("INPUT", "catalogs#", "Exactly six design documents are required");
        var documents = new EnumMap<CatalogDocument, JsonNode>(CatalogDocument.class);
        var snapshots = new TreeMap<String, ImmutableBytes>();
        for (CatalogDocument document : values()) {
            byte[] raw = input.get(document);
            if (raw == null || raw.length > StrictJson.MAX_BYTES) throw error("INPUT", document.filename() + "#", "Missing or oversized document");
            var copy = new ImmutableBytes(raw);
            snapshots.put(document.filename(), copy);
            documents.put(document, new JsonNode(StrictJson.parse(copy.copy(), document.filename()), document.filename() + "#"));
        }
        return validate(documents, snapshots);
    }

    private CatalogBundle validate(Map<CatalogDocument, JsonNode> docs, Map<String, ImmutableBytes> snapshots) {
        JsonNode s = docs.get(SCHEMA), g = docs.get(GRAPH), e = docs.get(EXPRESSION),
                m = docs.get(MORPHOLOGY), c = docs.get(CONTENT), cfg = docs.get(CONFIG);
        ServerConfig defaults = new ConfigLoader().decode(cfg);
        var pins = defaults.genetics().registries();
        header(s, "id", "version", "genesis:genes", 2);
        header(g, "schemaId", "graphVersion", "genesis:hybrid_graph", 1);
        header(e, "rulesetId", "rulesetVersion", "genesis:expression", 2);
        header(m, "id", "version", "genesis:morphology", 2);
        pin(s, "id", "version", pins.geneSchema(), cfg.field("genetics").field("registries").field("geneSchema"));
        pin(g, "schemaId", "graphVersion", pins.hybridGraph(), cfg.field("genetics").field("registries").field("hybridGraph"));
        pin(e, "rulesetId", "rulesetVersion", pins.expressionRules(), cfg.field("genetics").field("registries").field("expressionRules"));
        pin(m, "id", "version", pins.morphologySet(), cfg.field("genetics").field("registries").field("morphologySet"));
        expect(e.field("geneSchemaId"), s.field("id").string()); expect(e.field("geneSchemaVersion"), 2);
        expect(e.field("hybridGraphId"), g.field("schemaId").string()); expect(e.field("hybridGraphVersion"), 1);
        expect(e.field("morphologySetId"), m.field("id").string()); expect(e.field("morphologySetVersion"), 2);
        JsonNode compatibility = e.field("catalogCompatibility");
        expect(compatibility.field("contentCatalogVersion"), c.field("catalogVersion").string());
        expect(compatibility.field("graphVersion"), 1);
        expect(compatibility.field("contentCatalogSha256"), DesignFingerprint.sha256(c), "FINGERPRINT");
        expect(compatibility.field("hybridGraphSha256"), DesignFingerprint.sha256(g), "FINGERPRINT");
        expect(g.field("crossFamilyBreeding"), false); expect(g.field("resolver"), "union-roots-v1");
        expect(g.field("extensionPolicy"), "explicit-edges-only");
        expect(s.field("heritageMutationAllowed"), false);
        expectSet(s.field("heritageGenes"), Set.of("HERITAGE_1", "HERITAGE_2", "HERITAGE_3"), "HERITAGE");
        for (JsonNode node : List.of(s.field("familyGenes"), s.field("familyFounders"), s.field("geneCountByFamily"),
                e.field("families"), m.field("families"))) node.keys(FAMILIES, "FAMILIES");
        var graphFamilies = indexed(g.field("families"), "familyId");
        g.field("families").require(graphFamilies.keySet().equals(FAMILIES), "FAMILIES", "Expected the four v0.7 families");
        JsonNode common = s.field("coreGenes"), familyDefinitions = c.field("familyGeneDefinitions");
        common.require(common.object().size() == 23, "GENES", "Expected 23 common genes");
        var familyGeneNames = new HashSet<String>();
        for (String family : FAMILIES) familyGeneNames.addAll(geneNames(s.field("familyGenes").field(family)));
        familyDefinitions.keys(familyGeneNames, "GENES");
        common.require(java.util.Collections.disjoint(common.object().keySet(), familyGeneNames), "GENES", "Common and family genes must be disjoint");
        var variants = new TreeMap<String, Set<String>>();
        for (String gene : common.object().keySet()) variants.put(gene, variants(common.field(gene).field("variants")));
        for (String gene : familyGeneNames) variants.put(gene, variants(familyDefinitions.field(gene).field("variants")));
        for (String gene : variants.keySet()) geneId(gene, common.path());
        validateExpressions(e, m, s, variants, familyGeneNames);

        var schemas = new TreeMap<ContentId, FamilyGeneSchema>();
        var forms = new TreeMap<String, Form>();
        for (String family : new TreeSet<>(FAMILIES)) {
            JsonNode familyNode = s.field("familyGenes").field(family);
            Set<String> genes = geneNames(familyNode);
            Set<String> founders = ids(s.field("familyFounders").field(family));
            familyNode.require(founders.size() == 3, "FOUNDERS", "Expected three founders");
            JsonNode graphFamily = graphFamilies.get(family);
            String rig = graphFamily.field("rigId").id().toString();
            expectSet(graphFamily.field("baseSpecies"), founders, "FOUNDERS");
            for (JsonNode node : List.of(e.field("families").field(family), m.field("families").field(family))) {
                expect(node.field("rigId"), rig); expectSet(node.field("familyGenes"), genes, "GENES");
            }
            expectSet(e.field("families").field(family).field("founders"), founders, "FOUNDERS");
            expectSet(m.field("families").field(family).field("founderSpecies"), founders, "FOUNDERS");
            expectSet(e.field("families").field(family).field("heritageGenes"), Set.of("HERITAGE_1", "HERITAGE_2", "HERITAGE_3"), "HERITAGE");
            expect(e.field("families").field(family).field("historicalGraphVersion"), 1);
            var definitions = new TreeMap<ContentId, GeneDefinition>();
            for (String gene : variants.keySet()) if (common.object().has(gene) || genes.contains(gene)) {
                ContentId id = geneId(gene, familyNode.path());
                definitions.put(id, new GeneDefinition(id, variants.get(gene).stream().map(CatalogLoader::variantId).collect(Collectors.toSet()), GeneDefinition.Kind.TRAIT));
            }
            Set<ContentId> founderIds = founders.stream().map(ContentId::parse).collect(Collectors.toSet());
            for (ContentId id : FamilyGeneSchema.HERITAGE_GENES) definitions.put(id, new GeneDefinition(id, founderIds, GeneDefinition.Kind.HERITAGE));
            expect(s.field("geneCountByFamily").field(family), definitions.size());
            schemas.put(ContentId.parse(family), new FamilyGeneSchema(pins.geneSchema().fingerprint(), ContentId.parse(family), founderIds, definitions));
            validateGraphFamily(graphFamily, family, founders, rig, forms);
        }
        validateContent(c, s, e, variants, familyGeneNames, forms);
        validateDefaultMirrors(cfg, c, e);
        HybridGraph graph = decodeGraph(g, pins.hybridGraph().fingerprint());
        ExpressionRules expression = decodeExpression(e, c, defaults, graph, schemas);
        return new CatalogBundle(defaults, schemas, graph, expression,
                decodeMutations(c, pins.geneSchema().fingerprint(), schemas),
                decodeSignaturePassives(c, e, expression.references(), graph),
                c.field("catalogVersion").id(), DesignFingerprint.sha256(c), snapshots);
    }

    private SignaturePassiveCatalog decodeSignaturePassives(JsonNode content, JsonNode expression,
                                                            ExpressionRules.References references, HybridGraph graph) {
        expect(expression.field("catalogCompatibility").field("signaturePassives"),
                "resolved by genetically supported graph node and only applicable when selected role matches");
        var definitions = new TreeMap<ContentId, SignaturePassiveCatalog.Definition>();
        for (JsonNode form : content.field("forms").array()) {
            JsonNode passive = form.field("signaturePassive");
            passive.keys(Set.of("requiredRole", "ru", "en"), "PASSIVE_FIELDS");
            var descriptions = new TreeMap<String, String>();
            for (String locale : List.of("ru", "en")) {
                JsonNode text = passive.field(locale);
                text.require(!text.string().isBlank(), "PASSIVE_DESCRIPTION", "Passive description must not be blank");
                descriptions.put(locale, text.string());
            }
            definitions.put(form.field("formId").id(), new SignaturePassiveCatalog.Definition(
                    form.field("formId").id(), form.field("familyId").id(),
                    form.field("allowedRoles").strings().stream().map(CatalogLoader::variantId).collect(Collectors.toSet()),
                    variantId(passive.field("requiredRole").string()), descriptions));
        }
        var result = new SignaturePassiveCatalog(references, SignaturePassiveCatalog.SUPPORTED_ROLES, definitions);
        result.validate(graph); return result;
    }

    private MutationCatalog decodeMutations(JsonNode c, RegistryFingerprint schema, Map<ContentId, FamilyGeneSchema> schemas) {
        JsonNode selection = c.field("mutationVariantSelection");
        expect(selection.field("version"), MutationCatalog.SELECTION.toString());
        expect(selection.field("method"), "uniform over eligible mutation IDs within the rolled category, ordered by stable mutation ID; if none eligible, no mutation");
        JsonNode guarantee = c.field("mutationRules").field("guarantee");
        expect(guarantee.field("tier"), "mastery"); expect(guarantee.field("oncePerPlayerFamily"), true);
        expect(guarantee.field("condition"), "post-inheritance ARCANA quiet/quiet, focused mastery breeding, first discovery not yet awarded");
        var definitions = new TreeMap<ContentId, MutationCatalog.Definition>();
        for (JsonNode mutation : c.field("mutations").array()) {
            mutation.field("id").require(mutation.field("id").string().matches("[a-z][a-z0-9_]*"), "MUTATION_ID", "Invalid stable mutation token");
            ContentId id = variantId(mutation.field("id").string());
            definitions.put(id, new MutationCatalog.Definition(id,
                    MutationCatalog.Category.valueOf(mutation.field("category").string().toUpperCase(Locale.ROOT)),
                    geneId(mutation.field("geneId").string(), mutation.path()), variantId(mutation.field("resultingVariant").string())));
        }
        try {
            var result = new MutationCatalog(c.field("catalogVersion").id(), DesignFingerprint.sha256(c), schema, selection.field("version").id(), definitions);
            result.validate(schemas); return result;
        } catch (IllegalArgumentException exception) {
            if (exception instanceof ConfigurationException failure) throw failure;
            throw error("MUTATION_RULES", c.path(), "Invalid executable mutation definitions");
        }
    }

    private ExpressionRules decodeExpression(JsonNode e, JsonNode content, ServerConfig defaults,
                                             HybridGraph graph, Map<ContentId, FamilyGeneSchema> schemas) {
        var pins = defaults.genetics().registries();
        var refs = new ExpressionRules.References(pins.expressionRules().fingerprint(), pins.geneSchema().fingerprint(),
                pins.hybridGraph().fingerprint(), pins.morphologySet().fingerprint(),
                content.field("catalogVersion").id(), DesignFingerprint.sha256(content));
        JsonNode policy = e.field("statPolicy");
        expect(policy.field("formula"), "round_half_up(weightedFounderBase[stat] * clamp(1+sum(percentDelta),"
                + policy.field("statMultiplierMin").number(true).stripTrailingZeros().toPlainString() + ","
                + policy.field("statMultiplierMax").number(true).stripTrailingZeros().toPlainString() + ")+sum(flatDelta))");
        var genes = new TreeMap<ContentId, ExpressionRules.GeneRules>();
        for (String name : e.field("genes").object().keySet()) {
            JsonNode gene = e.field("genes").field(name);
            var pairs = new LinkedHashMap<AllelePair, ExpressionRules.PairRule>();
            for (String key : gene.field("pairRules").object().keySet()) {
                String[] variants = key.split("\\|", -1);
                JsonNode rule = gene.field("pairRules").field(key);
                var visual = new TreeMap<String, String>();
                for (String slot : rule.field("visual").object().keySet()) visual.put(slot, rule.field("visual").field(slot).string());
                pairs.put(new AllelePair(variantId(variants[0]), variantId(variants[1])), new ExpressionRules.PairRule(
                        rule.field("state").string(), statEffects(rule.field("multipliers")), statEffects(rule.field("flat")),
                        decimalEffects(rule.field("utility")), visual));
            }
            Set<ContentId> applicable = schemas.values().stream().filter(schema -> schema.genes().containsKey(geneId(name, gene.path())))
                    .map(FamilyGeneSchema::familyId).collect(Collectors.toSet());
            Optional<String> slot = gene.field("visualSlot").value().isJsonNull() ? Optional.empty() : Optional.of(gene.field("visualSlot").string());
            genes.put(geneId(name, gene.path()), new ExpressionRules.GeneRules(applicable, slot, pairs));
        }
        var founderStats = new TreeMap<ContentId, StatValues>();
        for (JsonNode form : content.field("forms").array()) {
            ContentId id = form.field("formId").id();
            if (graph.form(id).roots().size() == 1) founderStats.put(id, new StatValues(statEffects(form.field("baseStats"))));
        }
        JsonNode ability = e.field("abilityPolicy");
        expect(ability.field("innateMaxActive"), 1);
        var spells = new TreeMap<String, ContentId>();
        for (String element : ability.field("spellMap").object().keySet()) {
            String spell = ability.field("spellMap").field(element).string();
            ability.field("spellMap").field(element).require(content.field("magicSpells").object().has(spell), "SPELL", "Unknown innate spell");
            spells.put(element, variantId(spell));
        }
        try {
            var rules = new ExpressionRules(refs, new ExpressionRules.Policy(policy.field("statMultiplierMin").number(true),
                    policy.field("statMultiplierMax").number(true), new StatValues(statEffects(policy.field("minimum"))),
                    policy.field("speedBpsRoundPlaces").integer()), genes, founderStats, spells);
            rules.validate(graph, schemas);
            return rules;
        } catch (IllegalArgumentException exception) {
            if (exception instanceof ConfigurationException failure) throw failure;
            throw error("EXPRESSION", e.path(), "Invalid executable expression definitions");
        }
    }

    private Map<Stat, BigDecimal> statEffects(JsonNode source) {
        var result = new EnumMap<Stat, BigDecimal>(Stat.class);
        for (String key : source.object().keySet()) result.put(Stat.fromKey(key), source.field(key).number(true));
        return result;
    }

    private Map<String, BigDecimal> decimalEffects(JsonNode source) {
        var result = new TreeMap<String, BigDecimal>();
        for (String key : source.object().keySet()) result.put(key, source.field(key).number(true));
        return result;
    }

    private HybridGraph decodeGraph(JsonNode source, RegistryFingerprint reference) {
        var families = new TreeMap<ContentId, HybridGraph.Family>();
        var forms = new TreeMap<ContentId, HybridGraph.Form>();
        var edges = new ArrayList<HybridGraph.Edge>();
        for (JsonNode family : source.field("families").array()) {
            ContentId familyId = family.field("familyId").id(), rig = family.field("rigId").id();
            families.put(familyId, new HybridGraph.Family(familyId, rig,
                    ids(family.field("baseSpecies")).stream().map(ContentId::parse).collect(Collectors.toSet())));
            for (JsonNode form : family.field("forms").array()) {
                ContentId id = form.field("id").id();
                forms.put(id, new HybridGraph.Form(id, familyId, form.field("rigId").id(),
                        ids(form.field("roots")).stream().map(ContentId::parse).collect(Collectors.toSet())));
            }
            for (JsonNode pair : family.field("allowedPairs").array()) {
                edges.add(new HybridGraph.Edge(pair.field("parentA").id(), pair.field("parentB").id(), pair.field("childForm").id()));
            }
        }
        try { return new HybridGraph(reference, families, forms, edges); }
        catch (IllegalArgumentException exception) { throw error("GRAPH", source.path(), "Invalid executable hybrid graph"); }
    }

    private void validateExpressions(JsonNode e, JsonNode m, JsonNode s, Map<String, Set<String>> variants, Set<String> familyGenes) {
        JsonNode rules = e.field("genes"); rules.keys(variants.keySet(), "GENES");
        Set<String> slots = new HashSet<>(m.field("slots").strings());
        m.field("slots").require(slots.size() == 14, "SLOTS", "Expected the 14 initial slots");
        expectSet(m.field("ageVariants"), Set.of("infant", "juvenile", "adult"), "AGES");
        expectSet(e.field("morphology").field("ageVariants"), Set.of("infant", "juvenile", "adult"), "AGES");
        expect(m.field("veteranUsesAdultRig"), true); expect(e.field("morphology").field("veteranUsesAdultRig"), true);
        expect(e.field("morphology").field("required"), true);
        for (var entry : variants.entrySet()) {
            String gene = entry.getKey(); JsonNode definition = rules.field(gene);
            expect(definition.field("version"), 2); expectSet(definition.field("variants"), entry.getValue(), "VARIANTS");
            if (familyGenes.contains(gene)) {
                var applicable = new HashSet<String>();
                for (String family : FAMILIES) if (s.field("familyGenes").field(family).strings().contains(gene)) applicable.add(family);
                expectSet(definition.field("families"), applicable, "FAMILY_GENE_MEMBERSHIP");
            } else if (definition.object().has("families")) expectSet(definition.field("families"), FAMILIES, "FAMILY_GENE_MEMBERSHIP");
            JsonNode table = definition.field("pairRules");
            var expectedPairs = new HashSet<String>();
            var choices = new ArrayList<>(new TreeSet<>(entry.getValue()));
            for (int a = 0; a < choices.size(); a++) for (int b = a; b < choices.size(); b++) expectedPairs.add(choices.get(a) + "|" + choices.get(b));
            table.keys(expectedPairs, "PAIR_COVERAGE");
            String visualSlot = definition.field("visualSlot").value().isJsonNull() ? null : definition.field("visualSlot").string();
            definition.require(visualSlot == null || slots.contains(visualSlot), "VISUAL_SLOT", "Unregistered gene slot");
            for (String pair : table.object().keySet()) {
                JsonNode rule = table.field(pair); rule.field("state").string();
                for (String field : List.of("multipliers", "flat", "utility")) {
                    JsonNode effects = rule.field(field);
                    for (String stat : effects.object().keySet()) {
                        effects.field(stat).number(true);
                        effects.field(stat).require(field.equals("utility") || STATS.contains(stat), "STAT", "Unknown stat effect");
                    }
                }
                JsonNode visual = rule.field("visual");
                for (String slot : visual.object().keySet()) {
                    visual.field(slot).string();
                    visual.field(slot).require(slots.contains(slot) && slot.equals(visualSlot), "VISUAL_SLOT", "Visual token differs from registered gene slot");
                }
            }
        }
        JsonNode statPolicy = e.field("statPolicy");
        statPolicy.field("minimum").keys(STATS, "STAT");
        for (String stat : STATS) statPolicy.field("minimum").field(stat).require(
                statPolicy.field("minimum").field(stat).number(true).signum() >= 0, "STAT", "Negative stat minimum");
        var integers = new HashSet<>(STATS); integers.remove("speedBps");
        expectSet(statPolicy.field("integerStats"), integers, "STAT"); expect(statPolicy.field("speedBpsRoundPlaces"), 2);
        expect(e.field("abilityPolicy").field("arcanaGene"), "ARCANA"); expect(e.field("abilityPolicy").field("elementGene"), "ELEMENT");
        expect(e.field("abilityPolicy").field("unlockedByGenesOnly"), false);
    }

    private record Form(String family, Set<String> roots) {}

    private void validateGraphFamily(JsonNode node, String family, Set<String> founders, String rig, Map<String, Form> all) {
        var definitions = indexed(node.field("forms"), "id");
        var roots = new HashSet<Set<String>>();
        for (var entry : definitions.entrySet()) {
            JsonNode form = entry.getValue(); form.field("id").id();
            Set<String> support = ids(form.field("roots"));
            form.require(!support.isEmpty() && founders.containsAll(support), "ROOTS", "Invalid founder support");
            expect(form.field("rigId"), rig); expect(form.field("kind"), support.size() == 1 ? "base" : "hybrid");
            if (support.size() == 1) form.require(support.contains(entry.getKey()), "ROOTS", "Founder ID must equal its sole root");
            form.require(roots.add(support), "DUPLICATE", "Duplicate founder support");
            form.require(all.putIfAbsent(entry.getKey(), new Form(family, support)) == null, "DUPLICATE", "Duplicate global form ID");
        }
        node.require(definitions.size() == 7 && roots.size() == 7, "FORM_COVERAGE", "Expected all seven founder subsets");
        var seen = new HashSet<List<String>>();
        for (JsonNode pair : node.field("allowedPairs").array()) {
            String a = pair.field("parentA").id().toString(), b = pair.field("parentB").id().toString(), child = pair.field("childForm").id().toString();
            pair.require(definitions.containsKey(a) && definitions.containsKey(b) && definitions.containsKey(child), "PAIR_REFERENCE", "Cross-family or unknown form in pair");
            var union = new HashSet<>(all.get(a).roots()); union.addAll(all.get(b).roots());
            pair.field("childForm").require(union.equals(all.get(child).roots()), "CHILD_ROOTS", "Child is not the union of parent roots");
            List<String> key = a.compareTo(b) <= 0 ? List.of(a, b) : List.of(b, a);
            pair.require(seen.add(key), "DUPLICATE", "Duplicate unordered parent pair");
        }
        node.field("allowedPairs").require(seen.size() == 28, "PAIR_COVERAGE", "Expected all 28 unordered pairs");
    }

    private void validateContent(JsonNode c, JsonNode s, JsonNode e, Map<String, Set<String>> variants,
                                 Set<String> familyGenes, Map<String, Form> forms) {
        var contentForms = indexed(c.field("forms"), "formId");
        c.field("forms").require(contentForms.keySet().equals(forms.keySet()), "FORM_COVERAGE", "Graph and content forms differ");
        expect(c.field("baseSpeciesCount"), 12);
        Set<String> roles = c.field("roles").object().keySet();
        c.field("roles").require(roles.equals(SignaturePassiveCatalog.SUPPORTED_ROLES.stream().map(ContentId::path).collect(Collectors.toSet())),
                "ROLES", "Expected the seven registered v0.7 role definitions");
        for (var entry : contentForms.entrySet()) {
            JsonNode form = entry.getValue(); Form graph = forms.get(entry.getKey());
            expect(form.field("familyId"), graph.family());
            expectSet(form.field("originRoots"), graph.roots().stream().map(id -> ContentId.parse(id).path()).collect(Collectors.toSet()), "ROOTS");
            JsonNode stats = form.field("baseStats"); stats.keys(STATS, "STAT");
            for (String stat : STATS) {
                JsonNode value = stats.field(stat); value.require(value.number(false).signum() >= 0, "STAT", "Negative base stat");
                if (!stat.equals("speedBps")) value.integer();
            }
            Set<String> allowed = new HashSet<>(form.field("allowedRoles").strings());
            form.field("allowedRoles").require(roles.containsAll(allowed), "ROLE", "Unknown allowed role");
            form.field("signaturePassive").field("requiredRole").require(allowed.contains(form.field("signaturePassive").field("requiredRole").string()), "ROLE", "Passive requires an unavailable role");
        }
        var coreDefinitions = indexed(c.field("genes"), "id");
        c.field("genes").require(coreDefinitions.keySet().equals(s.field("coreGenes").object().keySet()), "GENES", "Catalog common genes differ from schema");
        for (var entry : coreDefinitions.entrySet()) for (String field : List.of("defaultVariant", "specialVariant")) {
            JsonNode value = entry.getValue().field(field);
            value.require(variants.get(entry.getKey()).contains(value.string()), "VARIANTS", "Unknown catalog variant");
        }
        var additional = new HashSet<String>();
        for (String family : FAMILIES) {
            Set<String> names = geneNames(s.field("familyGenes").field(family));
            if (!names.isEmpty()) {
                String shortName = ContentId.parse(family).path(); additional.add(shortName);
                expectSet(c.field("additionalGenes").field(shortName), names, "GENES");
            }
        }
        c.field("additionalGenes").keys(additional, "GENES");
        for (String gene : familyGenes) {
            var applicability = FAMILIES.stream().filter(family -> s.field("familyGenes").field(family).strings().contains(gene))
                    .map(family -> ContentId.parse(family).path()).collect(Collectors.toSet());
            expectSet(c.field("familyGeneDefinitions").field(gene).field("families"), applicability, "FAMILY_GENE_MEMBERSHIP");
        }
        var mutations = indexed(c.field("mutations"), "id");
        c.field("mutations").require(mutations.size() == 21, "MUTATIONS", "Expected 21 mutation definitions");
        for (JsonNode mutation : mutations.values()) {
            String gene = mutation.field("geneId").string();
            mutation.field("geneId").require(variants.containsKey(gene), "MUTATION_TARGET", "Unknown or HERITAGE mutation target");
            mutation.field("resultingVariant").require(variants.get(gene).contains(mutation.field("resultingVariant").string()), "MUTATION_TARGET", "Unknown resulting variant");
            mutation.field("category").require(Set.of("cosmetic", "physiological", "behavioral", "magical").contains(mutation.field("category").string()), "MUTATION_CATEGORY", "Unknown category");
            expect(mutation.field("drawOnEligibleChildOnly"), true);
        }
        var missions = indexed(c.field("trainingMissions"), "id");
        c.field("trainingMissions").require(missions.size() == 8, "MISSIONS", "Expected eight missions");
        for (JsonNode mission : missions.values()) {
            mission.field("id").id(); expect(mission.field("firstCompletionOnly"), true);
            mission.field("xp").require(mission.field("xp").integer() > 0, "MISSIONS", "Mission XP must be positive");
        }
        Set<String> elements = variants.get("ELEMENT");
        c.require(elements != null, "GENES", "ELEMENT is required");
        expectSet(c.field("elementVariants"), elements, "SPELL"); expectSet(c.field("elementExpressionPriority"), elements, "SPELL");
        c.field("magicSpells").keys(elements, "SPELL");
        e.field("abilityPolicy").field("spellMap").keys(elements, "SPELL");
        for (String element : elements) {
            JsonNode spell = c.field("magicSpells").field(element);
            expect(e.field("abilityPolicy").field("spellMap").field(element), element);
            spell.require(spell.field("cooldownSeconds").integer() >= 0 && spell.field("staminaCost").integer() >= 0,
                    "SPELL", "Negative spell cost or cooldown");
        }
    }

    private void validateDefaultMirrors(JsonNode cfg, JsonNode c, JsonNode e) {
        same(c.field("mutationRules").field("categoryProbabilityByTier"), cfg.field("genetics").field("mutation").field("probabilityByTier"));
        expect(c.field("mutationRules").field("maxMutationEventsPerChild"), 1);
        expect(c.field("mutationRules").field("eligibleOnly"), true);
        expect(c.field("mutationRules").field("inheritanceAffectedByMinigame"), false);
        same(c.field("mutationRules").field("guarantee").field("maxEligibleSuccessesWithoutFirstMagicalMutation"),
                cfg.field("genetics").field("mutation").field("arcaneFocus").field("firstDiscoveryGuaranteeEligibleSuccessfulBirths"));
        for (String field : List.of("initialCradleCapacity", "upgradedCradleCapacity", "dispersalRefundRate", "unknownTraitsDefault", "noveltyResearchCredit")) {
            same(c.field("lifeSparks").field(field), cfg.field("lifeSparks").field(field));
        }
        same(c.field("wildAffinityHypotheses").field("ordinaryCandidateArcanaAwakeProbability"), cfg.field("genetics").field("wildArcana").field("ordinaryCandidateRate"));
        same(c.field("wildAffinityHypotheses").field("affinityTaggedCandidateArcanaAwakeProbability"), cfg.field("genetics").field("wildArcana").field("affinityCandidateRate"));
        same(c.field("rolesMaxActiveLearned"), cfg.field("progression").field("roleSkillSlots"));
        same(c.field("maxInnateActive"), cfg.field("balance").field("maxInnateActiveSpells"));
        same(e.field("abilityPolicy").field("innateMaxActive"), c.field("maxInnateActive"));
        for (String bound : List.of("Min", "Max")) {
            JsonNode policy = e.field("statPolicy").field("statMultiplier" + bound);
            JsonNode config = cfg.field("balance").field("geneticStatMultiplier" + bound);
            policy.require(policy.number(true).compareTo(config.number(false)) == 0, "CONFIG_MIRROR", "Genetic bounds differ from config defaults");
            same(c.field("geneticStatMultipliersCap").field(bound.toLowerCase(Locale.ROOT)), config);
        }
    }

    private static void header(JsonNode node, String id, String version, String expectedId, int expectedVersion) {
        expect(node.field(id), expectedId); expect(node.field(version), expectedVersion);
    }

    private static void pin(JsonNode node, String id, String version, ServerConfig.RegistryPin pin, JsonNode declaration) {
        expect(node.field(id), pin.id().toString()); expect(node.field(version), pin.version());
        expect(declaration.field("sha256"), DesignFingerprint.sha256(node), "FINGERPRINT");
    }

    private static Map<String, JsonNode> indexed(JsonNode node, String id) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (JsonNode entry : node.array()) {
            String name = entry.field(id).string();
            entry.field(id).require(result.putIfAbsent(name, entry) == null, "DUPLICATE", "Duplicate registered ID");
        }
        return result;
    }

    private static Set<String> variants(JsonNode node) {
        Set<String> values = new TreeSet<>(node.strings());
        node.require(!values.isEmpty(), "VARIANTS", "Empty variant set");
        for (String value : values) node.require(value.matches("[a-z][a-z0-9_]*"), "VARIANTS", "Invalid variant token");
        return values;
    }

    private static Set<String> geneNames(JsonNode node) {
        Set<String> values = new HashSet<>(node.strings());
        values.forEach(gene -> geneId(gene, node.path()));
        return values;
    }

    private static ContentId geneId(String gene, String path) {
        if (!gene.matches("[A-Z][A-Z0-9_]*")) throw error("GENE_ID", path, "Invalid gene token");
        return ContentId.parse("genesis:" + gene.toLowerCase(Locale.ROOT));
    }

    private static ContentId variantId(String token) { return ContentId.parse("genesis:" + token); }
    private static Set<String> ids(JsonNode node) { var result = new HashSet<String>(); for (JsonNode item : node.array()) {
        String id = item.id().toString(); item.require(result.add(id), "DUPLICATE", "Duplicate ID"); } return result; }
    private static void expectSet(JsonNode node, Set<String> expected, String code) {
        node.require(new HashSet<>(node.strings()).equals(expected), code, "Declared set differs from referenced definitions");
    }
    private static void expect(JsonNode node, String expected) { expect(node, expected, "REFERENCE"); }
    private static void expect(JsonNode node, String expected, String code) { node.require(node.string().equals(expected), code, "Unexpected reference or fingerprint"); }
    private static void expect(JsonNode node, int expected) { node.require(node.integer() == expected, "REFERENCE", "Unexpected count or version"); }
    private static void expect(JsonNode node, boolean expected) { node.require(node.bool() == expected, "INVARIANT", "Unsupported invariant flag"); }
    private static void same(JsonNode a, JsonNode b) { a.require(a.value().equals(b.value()), "CONFIG_MIRROR", "Catalog and default config values differ"); }
}
