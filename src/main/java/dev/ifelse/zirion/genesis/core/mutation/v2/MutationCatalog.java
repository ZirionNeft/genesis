package dev.ifelse.zirion.genesis.core.mutation.v2;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Frozen mutation IDs/targets. Category probabilities and guarantee threshold come from the operation config. */
public record MutationCatalog(ContentId contentId, String contentSha256, RegistryFingerprint geneSchema,
                              ContentId selectionId, Map<ContentId, Definition> definitions) {
    public static final ContentId SELECTION = ContentId.parse("genesis:mutation-selection-v1");
    public enum Category { COSMETIC, PHYSIOLOGICAL, BEHAVIORAL, MAGICAL }
    public record Definition(ContentId id, Category category, ContentId gene, ContentId target) {
        public Definition { Objects.requireNonNull(id); Objects.requireNonNull(category); Objects.requireNonNull(gene); Objects.requireNonNull(target); }
    }

    public MutationCatalog {
        Objects.requireNonNull(contentId); Objects.requireNonNull(contentSha256); Objects.requireNonNull(geneSchema);
        if (!contentSha256.matches("[0-9a-f]{64}") || !SELECTION.equals(selectionId)) throw new IllegalArgumentException("Unsupported mutation catalog");
        var copy = new TreeMap<ContentId, Definition>();
        definitions.forEach((key, definition) -> {
            if (!key.equals(definition.id())) throw new IllegalArgumentException("Mutation map key differs from ID");
            copy.put(key, definition);
        });
        definitions = Collections.unmodifiableSortedMap(copy);
    }

    public void validate(Map<ContentId, FamilyGeneSchema> schemas) {
        for (var schema : schemas.values()) if (!schema.reference().equals(geneSchema)) throw new IllegalArgumentException("Mutation schema pin differs");
        if (definitions.values().stream().filter(d -> d.category() == Category.MAGICAL).count() != 1) {
            throw new IllegalArgumentException("Exactly one ARCANA awakening is required for the first-discovery guarantee");
        }
        var targets = new java.util.HashSet<String>();
        for (Definition definition : definitions.values()) {
            boolean found = false;
            boolean arcana = definition.gene().equals(ContentId.parse("genesis:arcana"));
            if (arcana != (definition.category() == Category.MAGICAL)
                    || arcana && !definition.target().equals(ContentId.parse("genesis:awake"))) {
                throw new IllegalArgumentException("Magical mutation must awaken ARCANA");
            }
            if (!targets.add(definition.gene() + "|" + definition.target())) throw new IllegalArgumentException("Duplicate mutation target");
            for (var schema : schemas.values()) {
                var gene = schema.genes().get(definition.gene());
                if (gene == null) continue;
                found = true;
                if (!gene.mutationAllowed() || !gene.variants().contains(definition.target())) throw new IllegalArgumentException("Forbidden mutation target");
            }
            if (!found) throw new IllegalArgumentException("Unregistered mutation gene");
        }
    }
}
