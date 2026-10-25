package dev.ifelse.zirion.genesis.core.genetics.v2;

import dev.ifelse.zirion.genesis.core.breeding.AlleleRandom;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Objects;
import java.util.TreeMap;

/** Pure v2 inheritance after an eligible successful attempt; no mutations, kinship gates or game state. */
public final class MendelianInheritance {
    public static final ContentId ALGORITHM = ContentId.parse("genesis:mendelian_gene_id_order_v2");

    private MendelianInheritance() {}

    /**
     * Natural ContentId order (namespace, then path), A then B at every gene.
     * Exactly two choices per gene, including homozygous pairs. New genes change
     * stream consumption; exact schema fingerprints and algorithm identity matter for replay.
     */
    public static Genome inherit(Genome parentA, Genome parentB, FamilyGeneSchema schema, AlleleRandom random) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(random, "random");
        schema.validate(parentA);
        schema.validate(parentB);
        var pairs = new TreeMap<ContentId, AllelePair>();
        for (ContentId gene : schema.genes().keySet()) {
            AllelePair a = parentA.genes().get(gene);
            AllelePair b = parentB.genes().get(gene);
            pairs.put(gene, new AllelePair(random.chooseSecond() ? a.second() : a.first(),
                    random.chooseSecond() ? b.second() : b.first()));
        }
        return schema.createGenome(pairs);
    }
}
