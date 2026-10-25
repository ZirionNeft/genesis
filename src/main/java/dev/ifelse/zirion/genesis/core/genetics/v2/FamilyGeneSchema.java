package dev.ifelse.zirion.genesis.core.genetics.v2;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Frozen gene definitions for one family, supplied by a trusted registry loader.
 * Does not validate a whole catalog, expression rules, assets, ownership or import permission.
 */
public record FamilyGeneSchema(RegistryFingerprint reference, ContentId familyId,
                               Set<ContentId> founders, Map<ContentId, GeneDefinition> genes) {
    public static final List<ContentId> HERITAGE_GENES = List.of(
            ContentId.parse("genesis:heritage_1"), ContentId.parse("genesis:heritage_2"),
            ContentId.parse("genesis:heritage_3"));
    public static final int HERITAGE_COPY_COUNT = 6;

    public FamilyGeneSchema {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(founders, "founders");
        Objects.requireNonNull(genes, "genes");
        if (reference.reference().version() != 2) {
            throw new IllegalArgumentException("Only gene schema v2 is supported");
        }
        var founderCopy = new TreeSet<ContentId>();
        founders.forEach(founder -> founderCopy.add(Objects.requireNonNull(founder, "founder")));
        if (founderCopy.size() != 3) throw new IllegalArgumentException("A v2 family requires three founders");
        founders = Collections.unmodifiableSortedSet(founderCopy);
        var geneCopy = new TreeMap<ContentId, GeneDefinition>();
        var heritage = new TreeSet<ContentId>();
        for (var entry : genes.entrySet()) {
            ContentId id = Objects.requireNonNull(entry.getKey(), "gene ID");
            GeneDefinition gene = Objects.requireNonNull(entry.getValue(), "gene definition");
            if (!id.equals(gene.id())) throw new IllegalArgumentException("Gene map key differs from definition ID: " + id);
            if (gene.kind() == GeneDefinition.Kind.HERITAGE) {
                heritage.add(id);
                if (!gene.variants().equals(founders)) {
                    throw new IllegalArgumentException("HERITAGE variants must equal this family's founders: " + id);
                }
            }
            geneCopy.put(id, gene);
        }
        if (!heritage.equals(Set.copyOf(HERITAGE_GENES))) {
            throw new IllegalArgumentException("Expected exactly HERITAGE_1..3 with kind HERITAGE");
        }
        genes = Collections.unmodifiableSortedMap(geneCopy);
    }

    /** Constructs a fully validated genotype; raw Genome construction alone is not validation. */
    public Genome createGenome(Map<ContentId, AllelePair> pairs) {
        Genome genome = new Genome(reference, familyId, pairs);
        validate(genome);
        return genome;
    }

    /** Validates the entire snapshot before callers spend resources or consume random choices. */
    public void validate(Genome genome) {
        Objects.requireNonNull(genome, "genome");
        if (!reference.equals(genome.geneSchema())) {
            throw new IllegalArgumentException("Genome requires a different registry ID, version or SHA-256");
        }
        if (!familyId.equals(genome.familyId())) throw new IllegalArgumentException("Genome belongs to another family");
        if (!genes.keySet().equals(genome.genes().keySet())) {
            throw new IllegalArgumentException("Genome must contain exactly this family's registered genes");
        }
        genome.genes().forEach((id, pair) -> {
            Set<ContentId> variants = genes.get(id).variants();
            if (!variants.contains(pair.first()) || !variants.contains(pair.second())) {
                throw new IllegalArgumentException("Unknown or misplaced variant at " + id + ": " + pair);
            }
        });
    }

    /** Positive counts only, summing to six; not pedigree percentages or historical hybrid roots. */
    public Map<ContentId, Integer> founderCopyCounts(Genome genome) {
        validate(genome);
        var result = new TreeMap<ContentId, Integer>();
        for (ContentId gene : HERITAGE_GENES) {
            AllelePair pair = genome.genes().get(gene);
            result.merge(pair.first(), 1, Integer::sum);
            result.merge(pair.second(), 1, Integer::sum);
        }
        return Collections.unmodifiableSortedMap(result);
    }
}
