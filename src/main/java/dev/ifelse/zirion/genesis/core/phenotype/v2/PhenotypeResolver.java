package dev.ifelse.zirion.genesis.core.phenotype.v2;

import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.content.v2.FormIdentity;
import dev.ifelse.zirion.genesis.core.content.v2.FormResolver;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.GeneDefinition;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Internal full genetic potential, before age, training, role, condition or equipment. Not a public passport. */
public final class PhenotypeResolver {
    public record Result(ExpressionRules.References references, FormResolver.Result forms, StatValues stats,
                         Map<ContentId, String> geneStates, Map<String, String> visualStates,
                         Map<String, BigDecimal> utility, Optional<ContentId> innateSpellPotential) {
        public Result {
            geneStates = Collections.unmodifiableSortedMap(new TreeMap<>(geneStates));
            visualStates = Collections.unmodifiableSortedMap(new TreeMap<>(visualStates));
            utility = Collections.unmodifiableSortedMap(new TreeMap<>(utility));
        }
    }

    private final CatalogBundle catalogs;
    private final FormResolver forms;

    public PhenotypeResolver(CatalogBundle catalogs) {
        this.catalogs = java.util.Objects.requireNonNull(catalogs);
        this.forms = new FormResolver(catalogs.hybridGraph(), catalogs.families());
    }

    public Result resolve(FormIdentity identity, Genome genome) {
        var resolvedForms = forms.resolve(identity, genome); // checks every gene, exact pins and historical support first
        FamilyGeneSchema schema = catalogs.families().get(genome.familyId());
        ExpressionRules rules = catalogs.expressionRules();
        var states = new TreeMap<ContentId, String>();
        var visuals = new TreeMap<String, String>();
        var utility = new TreeMap<String, BigDecimal>();
        var percent = new EnumMap<Stat, BigDecimal>(Stat.class);
        var flat = new EnumMap<Stat, BigDecimal>(Stat.class);
        for (GeneDefinition gene : schema.genes().values()) {
            if (gene.kind() == GeneDefinition.Kind.HERITAGE) continue;
            ExpressionRules.PairRule rule = rules.genes().get(gene.id()).pairs().get(genome.genes().get(gene.id()));
            states.put(gene.id(), rule.state());
            rule.percentDelta().forEach((stat, delta) -> percent.merge(stat, delta, BigDecimal::add));
            rule.flatDelta().forEach((stat, delta) -> flat.merge(stat, delta, BigDecimal::add));
            visuals.putAll(rule.visual()); utility.putAll(rule.utility()); // unique ownership checked at freeze
        }
        var stats = new EnumMap<Stat, BigDecimal>(Stat.class);
        BigDecimal copies = BigDecimal.valueOf(FamilyGeneSchema.HERITAGE_COPY_COUNT);
        for (Stat stat : Stat.values()) {
            BigDecimal numerator = BigDecimal.ZERO;
            for (var entry : resolvedForms.founderCopyCounts().entrySet()) {
                numerator = numerator.add(rules.founderStats().get(entry.getKey()).get(stat).multiply(BigDecimal.valueOf(entry.getValue())));
            }
            BigDecimal multiplier = BigDecimal.ONE.add(percent.getOrDefault(stat, BigDecimal.ZERO))
                    .max(rules.policy().multiplierMin()).min(rules.policy().multiplierMax());
            // Keep the common denominator six until final rounding: no inexact 1/6 decimal approximation.
            BigDecimal adjusted = numerator.multiply(multiplier).add(flat.getOrDefault(stat, BigDecimal.ZERO).multiply(copies));
            BigDecimal minimum = rules.policy().minimum().get(stat).multiply(copies);
            int places = stat == Stat.SPEED_BPS ? rules.policy().speedRoundPlaces() : 0;
            stats.put(stat, adjusted.max(minimum).divide(copies, places, RoundingMode.HALF_UP));
        }
        Optional<ContentId> innate = "awake".equals(states.get(ExpressionRules.ARCANA))
                ? Optional.of(rules.spellMap().get(states.get(ExpressionRules.ELEMENT))) : Optional.empty();
        return new Result(rules.references(), resolvedForms, new StatValues(stats), states, visuals, utility, innate);
    }
}
