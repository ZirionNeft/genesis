package dev.ifelse.zirion.genesis.core.genetics.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Test-only reading of gene definitions, not a runtime loader or acceptance of expression rules. */
final class V2TestContent {
    private static final JsonObject SCHEMA = read("Genesis_GDD_v0.7_Gene_Schema_v2.json");
    private static final JsonObject CONTENT = read("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json");
    private static final JsonObject CONFIG = read("Genesis_GDD_v0.7_Server_Config.json");

    private V2TestContent() {}

    static ContentId id(String path) { return ContentId.parse("genesis:" + path); }

    static FamilyGeneSchema schema(String family) {
        String familyId = "genesis:" + family;
        Set<ContentId> founders = ids(SCHEMA.getAsJsonObject("familyFounders").getAsJsonArray(familyId), false);
        var definitions = new TreeMap<ContentId, GeneDefinition>();
        SCHEMA.getAsJsonObject("coreGenes").entrySet().forEach(entry -> {
            ContentId gene = id(entry.getKey().toLowerCase(java.util.Locale.ROOT));
            definitions.put(gene, new GeneDefinition(gene,
                    ids(entry.getValue().getAsJsonObject().getAsJsonArray("variants"), true), GeneDefinition.Kind.TRAIT));
        });
        for (var element : SCHEMA.getAsJsonObject("familyGenes").getAsJsonArray(familyId)) {
            String name = element.getAsString();
            ContentId gene = id(name.toLowerCase(java.util.Locale.ROOT));
            definitions.put(gene, new GeneDefinition(gene,
                    ids(CONTENT.getAsJsonObject("familyGeneDefinitions").getAsJsonObject(name)
                            .getAsJsonArray("variants"), true), GeneDefinition.Kind.TRAIT));
        }
        for (var element : SCHEMA.getAsJsonArray("heritageGenes")) {
            ContentId gene = id(element.getAsString().toLowerCase(java.util.Locale.ROOT));
            definitions.put(gene, new GeneDefinition(gene, founders, GeneDefinition.Kind.HERITAGE));
        }
        var reference = new RegistryFingerprint(new RulesetVersion(ContentId.parse(SCHEMA.get("id").getAsString()),
                SCHEMA.get("version").getAsInt()), CONFIG.getAsJsonObject("genetics").getAsJsonObject("registries")
                .getAsJsonObject("geneSchema").get("sha256").getAsString());
        return new FamilyGeneSchema(reference, ContentId.parse(familyId), founders, definitions);
    }

    static Map<ContentId, AllelePair> pairs(FamilyGeneSchema schema, String founder) {
        var result = new TreeMap<ContentId, AllelePair>();
        schema.genes().forEach((id, gene) -> {
            ContentId variant = gene.kind() == GeneDefinition.Kind.HERITAGE ? id(founder)
                    : gene.variants().iterator().next();
            result.put(id, new AllelePair(variant, variant));
        });
        return result;
    }

    static Genome genome(FamilyGeneSchema schema, String founder) {
        return schema.createGenome(pairs(schema, founder));
    }

    static RegistryFingerprint fingerprint(String suffix) {
        return new RegistryFingerprint(new RulesetVersion(id("genes"), 2), suffix.repeat(64));
    }

    private static Set<ContentId> ids(JsonArray array, boolean shortId) {
        var result = new TreeSet<ContentId>();
        array.forEach(value -> result.add(shortId ? id(value.getAsString()) : ContentId.parse(value.getAsString())));
        return result;
    }

    private static JsonObject read(String name) {
        Path path = Path.of(System.getProperty("genesis.gddDirectory")).resolve(name);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
