package dev.ifelse.zirion.genesis.core.wild;

import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Explicit balance input, never inferred from catalog defaults, mutation rates or uniform weights. */
public final class WildDistributionPolicy {
    public static final ContentId ARCANA = ContentId.parse("genesis:arcana");
    public static final ContentId AWAKE = ContentId.parse("genesis:awake");
    public static final ContentId QUIET = ContentId.parse("genesis:quiet");
    private static final Comparator<AllelePair> PAIR_ORDER = Comparator.comparing(AllelePair::first).thenComparing(AllelePair::second);

    /** Full unordered-pair table. Excluded pairs require an explicit zero, not an omitted entry. */
    public record Distribution(Map<AllelePair, BigDecimal> probabilities) {
        public Distribution {
            var copy = new TreeMap<AllelePair, BigDecimal>(PAIR_ORDER);
            Objects.requireNonNull(probabilities).forEach((pair, probability) -> {
                Objects.requireNonNull(pair); Objects.requireNonNull(probability);
                if (probability.scale() < -1024 || probability.scale() > 1024
                        || probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("Invalid wild pair probability");
                }
                copy.put(pair, probability.stripTrailingZeros());
            });
            if (copy.isEmpty() || copy.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException("Wild pair probabilities must sum exactly to one");
            }
            probabilities = Collections.unmodifiableSortedMap(copy);
        }

        AllelePair sample(double draw) {
            if (!Double.isFinite(draw) || draw < 0 || draw >= 1) throw new IllegalArgumentException("Draw outside [0,1)");
            BigDecimal exactDraw = new BigDecimal(draw), cumulative = BigDecimal.ZERO;
            for (var entry : probabilities.entrySet()) {
                cumulative = cumulative.add(entry.getValue());
                if (exactDraw.compareTo(cumulative) < 0) return entry.getKey();
            }
            throw new IllegalStateException("Incomplete probability table");
        }
    }

    /** ARCANA is conditional on the separate candidate-level awake gate; HERITAGE is never sampled. */
    public record Profile(Map<ContentId, Distribution> genes, Distribution awakenedArcana) {
        public Profile {
            var copy = new TreeMap<ContentId, Distribution>();
            Objects.requireNonNull(genes).forEach((gene, distribution) -> copy.put(Objects.requireNonNull(gene), Objects.requireNonNull(distribution)));
            genes = Collections.unmodifiableSortedMap(copy);
            Objects.requireNonNull(awakenedArcana);
        }
    }

    private final RegistryFingerprint geneSchema;
    private final Map<ContentId, Profile> founders;
    private final RegistryFingerprint reference;

    public WildDistributionPolicy(RulesetVersion id, RegistryFingerprint geneSchema, Map<ContentId, Profile> founders) {
        this.geneSchema = Objects.requireNonNull(geneSchema);
        var copy = new TreeMap<ContentId, Profile>();
        Objects.requireNonNull(founders).forEach((founder, profile) -> copy.put(Objects.requireNonNull(founder), Objects.requireNonNull(profile)));
        if (copy.isEmpty()) throw new IllegalArgumentException("No wild founder profiles");
        this.founders = Collections.unmodifiableSortedMap(copy);
        this.reference = new RegistryFingerprint(Objects.requireNonNull(id), fingerprint(id));
    }

    public RegistryFingerprint reference() { return reference; }
    public RegistryFingerprint geneSchema() { return geneSchema; }
    public Map<ContentId, Profile> founders() { return founders; }

    /** Partial founder coverage is allowed. Unconfigured species are unavailable, with no fallback. */
    public void validate(CatalogBundle catalogs) {
        if (!geneSchema.equals(catalogs.defaults().genetics().registries().geneSchema().fingerprint())) {
            throw new IllegalArgumentException("Wild policy requires another gene schema");
        }
        for (var entry : founders.entrySet()) {
            var form = catalogs.hybridGraph().form(entry.getKey());
            if (!form.roots().equals(Set.of(entry.getKey()))) throw new IllegalArgumentException("Wild species must be a founder");
            FamilyGeneSchema schema = catalogs.families().get(form.familyId());
            var expected = new TreeSet<>(schema.genes().keySet());
            expected.removeAll(FamilyGeneSchema.HERITAGE_GENES); expected.remove(ARCANA);
            if (!entry.getValue().genes().keySet().equals(expected)) throw new IllegalArgumentException("Incomplete or extra wild genes");
            entry.getValue().genes().forEach((gene, distribution) -> {
                var pairs = new TreeSet<>(PAIR_ORDER);
                for (ContentId a : schema.genes().get(gene).variants()) {
                    for (ContentId b : schema.genes().get(gene).variants()) pairs.add(new AllelePair(a, b));
                }
                if (!distribution.probabilities().keySet().equals(pairs)) throw new IllegalArgumentException("Incomplete or foreign pair at " + gene);
            });
            if (!schema.genes().get(ARCANA).variants().equals(Set.of(AWAKE, QUIET))
                    || !entry.getValue().awakenedArcana().probabilities().keySet().equals(Set.of(new AllelePair(AWAKE, AWAKE), new AllelePair(AWAKE, QUIET)))) {
                throw new IllegalArgumentException("Awakened ARCANA needs explicit awake/awake and awake/quiet weights");
            }
        }
    }

    /** Versioned binary fingerprint profile, deliberately separate from source-JSON and GEN1 hashing. */
    private String fingerprint(RulesetVersion id) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            text(out, "genesis:wild_distribution_binary_v1");
            text(out, id.id().toString()); out.writeInt(id.version());
            text(out, geneSchema.reference().id().toString()); out.writeInt(geneSchema.reference().version()); text(out, geneSchema.sha256());
            out.writeInt(founders.size());
            for (var entry : founders.entrySet()) {
                text(out, entry.getKey().toString()); out.writeInt(entry.getValue().genes().size());
                for (var gene : entry.getValue().genes().entrySet()) { text(out, gene.getKey().toString()); distribution(out, gene.getValue()); }
                distribution(out, entry.getValue().awakenedArcana());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void distribution(DataOutputStream out, Distribution distribution) throws IOException {
        out.writeInt(distribution.probabilities().size());
        for (var entry : distribution.probabilities().entrySet()) {
            text(out, entry.getKey().first().toString()); text(out, entry.getKey().second().toString());
            text(out, entry.getValue().toPlainString());
        }
    }

    static void text(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
    }
}
