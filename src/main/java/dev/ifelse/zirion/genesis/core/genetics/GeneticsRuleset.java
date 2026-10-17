package dev.ifelse.zirion.genesis.core.genetics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Frozen allele definitions and complete pair-expression tables, separate from effect/asset rules. */
public final class GeneticsRuleset {
    private final RulesetVersion reference;
    private final Map<ContentId, AlleleDefinition> alleles;
    private final Map<Locus, Map<AllelePair, ContentId>> expressions;

    private GeneticsRuleset(Builder builder) {
        reference = builder.reference;
        alleles = Collections.unmodifiableMap(new TreeMap<>(builder.alleles));
        EnumMap<Locus, Map<AllelePair, ContentId>> copy = new EnumMap<>(Locus.class);
        builder.expressions.forEach((locus, table) -> copy.put(locus, Map.copyOf(table)));
        expressions = Collections.unmodifiableMap(copy);
    }

    public static Builder builder(RulesetVersion reference) {
        return new Builder(reference);
    }

    public RulesetVersion reference() {
        return reference;
    }

    public Map<ContentId, AlleleDefinition> alleles() {
        return alleles;
    }

    /** All reachable expression IDs at a locus, in canonical order, including homozygous outputs. */
    public Set<ContentId> expressionOptions(Locus locus) {
        return Collections.unmodifiableSet(new TreeSet<>(expressions.get(Objects.requireNonNull(locus)).values()));
    }

    public void validate(Genome genome) {
        Objects.requireNonNull(genome, "genome");
        if (!reference.equals(genome.ruleset())) {
            throw new IllegalArgumentException("Genome requires another ruleset/version: " + genome.ruleset());
        }
        genome.loci().forEach((locus, pair) -> {
            requireAllele(alleles, locus, pair.first());
            requireAllele(alleles, locus, pair.second());
        });
    }

    /** Symbolic expression IDs; mapping them to morphology/stats is a separate responsibility. */
    public Map<Locus, ContentId> resolveExpressions(Genome genome) {
        validate(genome);
        EnumMap<Locus, ContentId> result = new EnumMap<>(Locus.class);
        genome.loci().forEach((locus, pair) -> result.put(locus, expressions.get(locus).get(pair)));
        return Collections.unmodifiableMap(result);
    }

    private static void requireAllele(Map<ContentId, AlleleDefinition> definitions, Locus locus, ContentId id) {
        AlleleDefinition allele = definitions.get(id);
        if (allele == null || allele.locus() != locus) {
            throw new IllegalArgumentException("Unknown or misplaced allele at " + locus + ": " + id);
        }
    }

    public static final class Builder {
        private final RulesetVersion reference;
        private final Map<ContentId, AlleleDefinition> alleles = new TreeMap<>();
        private final Map<Locus, Map<AllelePair, ContentId>> expressions = new EnumMap<>(Locus.class);
        private GeneticsRuleset frozen;

        private Builder(RulesetVersion reference) {
            this.reference = Objects.requireNonNull(reference, "reference");
            for (Locus locus : Locus.values()) expressions.put(locus, new HashMap<>());
        }

        /** The trusted content loader supplies the registrar namespace; this is not authentication. */
        public Builder registerAllele(String registrarNamespace, AlleleDefinition allele) {
            requireMutable();
            Objects.requireNonNull(allele, "allele");
            if (!allele.id().namespace().equals(registrarNamespace)) {
                throw new IllegalArgumentException("Registrar cannot write namespace " + allele.id().namespace());
            }
            if (alleles.putIfAbsent(allele.id(), allele) != null) {
                throw new IllegalArgumentException("Duplicate allele ID: " + allele.id());
            }
            return this;
        }

        public Builder registerExpression(Locus locus, AllelePair pair, ContentId expression) {
            requireMutable();
            Objects.requireNonNull(locus, "locus");
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(expression, "expression");
            requireAllele(alleles, locus, pair.first());
            requireAllele(alleles, locus, pair.second());
            if (expressions.get(locus).putIfAbsent(pair, expression) != null) {
                throw new IllegalArgumentException("Duplicate expression pair at " + locus + ": " + pair);
            }
            return this;
        }

        /** A failed validation leaves the builder editable; a successful freeze is final and idempotent. */
        public GeneticsRuleset freeze() {
            if (frozen != null) return frozen;
            for (Locus locus : Locus.values()) {
                var atLocus = new ArrayList<ContentId>();
                alleles.values().stream().filter(allele -> allele.locus() == locus)
                        .forEach(allele -> atLocus.add(allele.id()));
                if (atLocus.isEmpty()) throw new IllegalStateException("No alleles at " + locus);
                for (int first = 0; first < atLocus.size(); first++) {
                    for (int second = first; second < atLocus.size(); second++) {
                        AllelePair pair = new AllelePair(atLocus.get(first), atLocus.get(second));
                        if (!expressions.get(locus).containsKey(pair)) {
                            throw new IllegalStateException("Missing expression at " + locus + ": " + pair);
                        }
                    }
                }
            }
            frozen = new GeneticsRuleset(this);
            return frozen;
        }

        private void requireMutable() {
            if (frozen != null) throw new IllegalStateException("Ruleset is already frozen");
        }
    }
}
