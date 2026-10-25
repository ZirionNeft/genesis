package dev.ifelse.zirion.genesis.core.genetics.v2;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable v2 genotype. FamilyGeneSchema validates definitions before any dependent calculation. */
public record Genome(RegistryFingerprint geneSchema, ContentId familyId, Map<ContentId, AllelePair> genes) {
    public Genome {
        Objects.requireNonNull(geneSchema, "geneSchema");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(genes, "genes");
        if (geneSchema.reference().version() != 2) {
            throw new IllegalArgumentException("Only gene schema v2 is supported");
        }
        var copy = new TreeMap<ContentId, AllelePair>();
        genes.forEach((gene, pair) -> copy.put(Objects.requireNonNull(gene, "gene"),
                Objects.requireNonNull(pair, "pair")));
        if (copy.isEmpty()) throw new IllegalArgumentException("Genome must not be empty");
        genes = Collections.unmodifiableSortedMap(copy);
    }
}
