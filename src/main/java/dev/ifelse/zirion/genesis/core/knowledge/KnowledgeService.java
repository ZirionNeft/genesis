package dev.ifelse.zirion.genesis.core.knowledge;

import dev.ifelse.zirion.genesis.core.code.v2.GenesisBlueprint;
import dev.ifelse.zirion.genesis.core.code.v2.GenesisCodeCodec;
import dev.ifelse.zirion.genesis.core.code.v2.GeneticBlueprintResolver;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.knowledge.DisclosureAccess.Permission.*;

/** Pure, retained disclosure policy and fact-update proposals. No IO, world action, ownership lookup or durable commit. */
public final class KnowledgeService {
    /** Private analysis DTO, returned only through READ_ANALYSIS and never embedded in PublicPassport. */
    public record AnalysisView(KnowledgeSubject subject, Map<ContentId, AllelePair> knownGenes, boolean complete) {
        public AnalysisView {
            Objects.requireNonNull(subject);
            knownGenes = Collections.unmodifiableSortedMap(new TreeMap<>(knownGenes));
        }
    }

    /** Proposed CAS; repeating already recorded facts returns the same snapshot without consuming a revision. */
    public record Update(long expectedRevision, KnowledgeSnapshot next) {
        public Update { Objects.requireNonNull(next); }
        public boolean changed() { return next.revision() != expectedRevision; }
    }

    private final ServerConfig.Code policy;
    private final GenesisCodeCodec codec;
    private final GeneticBlueprintResolver resolver;

    public KnowledgeService(CatalogBundle catalogs, ServerConfig.Code policy) {
        this.policy = Objects.requireNonNull(policy);
        this.codec = new GenesisCodeCodec(policy.limits());
        this.resolver = new GeneticBlueprintResolver(Objects.requireNonNull(catalogs));
    }

    public KnowledgeSnapshot unknown(UUID viewerId, KnowledgeSubject subject, GenesisBlueprint blueprint) {
        resolver.resolve(blueprint);
        return new KnowledgeSnapshot(viewerId, subject, fingerprint(blueprint), 0, Set.of(), Set.of());
    }

    /** Caller must first prove these slots are actually visible on the accepted current-age model, not merely potential. */
    public Update recordObservation(GenesisBlueprint blueprint, KnowledgeSnapshot current, DisclosureAccess access, Set<String> visibleSlots) {
        require(access, RECORD_OBSERVATION);
        var phenotype = validate(blueprint, current, access);
        if (!phenotype.visualStates().keySet().containsAll(visibleSlots)) throw denied("Unsupported observable visual slot");
        var observed = new TreeSet<>(current.observedVisualSlots()); observed.addAll(visibleSlots);
        return update(current, observed, current.analyzedGenes());
    }

    /** Called only for a completed server-validated analysis action; no timing, cost or action is invented here. */
    public Update recordAnalysis(GenesisBlueprint blueprint, KnowledgeSnapshot current, DisclosureAccess access, Set<ContentId> analyzedGenes) {
        require(access, RECORD_ANALYSIS); validate(blueprint, current, access);
        if (!blueprint.genome().genes().keySet().containsAll(analyzedGenes)) throw denied("Unknown analyzed gene");
        var analyzed = new TreeSet<>(current.analyzedGenes()); analyzed.addAll(analyzedGenes);
        return update(current, current.observedVisualSlots(), analyzed);
    }

    public PublicPassport passport(GenesisBlueprint blueprint, KnowledgeSnapshot knowledge, DisclosureAccess access) {
        if (!policy.publicPassportEnabled()) throw denied("Public passport disabled");
        require(access, READ_PUBLIC_PASSPORT);
        var phenotype = validate(blueprint, knowledge, access);
        var visible = new TreeMap<String, String>();
        knowledge.observedVisualSlots().forEach(slot -> visible.put(slot, phenotype.visualStates().get(slot)));
        return new PublicPassport(knowledge.subject(), visible);
    }

    public AnalysisView analysis(GenesisBlueprint blueprint, KnowledgeSnapshot knowledge, DisclosureAccess access) {
        require(access, READ_ANALYSIS); validate(blueprint, knowledge, access);
        var genes = new TreeMap<ContentId, AllelePair>();
        knowledge.analyzedGenes().forEach(gene -> genes.put(gene, blueprint.genome().genes().get(gene)));
        return new AnalysisView(knowledge.subject(), genes, complete(blueprint, knowledge));
    }

    public String fullCode(GenesisBlueprint blueprint, KnowledgeSnapshot knowledge, DisclosureAccess access) {
        require(access, READ_FULL_CODE); validate(blueprint, knowledge, access);
        if (policy.visibility() == ServerConfig.Visibility.AFTER_ANALYSIS && !complete(blueprint, knowledge)) {
            throw denied("Full analysis required");
        }
        return codec.encode(blueprint);
    }

    /** EXPORT_OWN_CODE must come from a fresh authoritative ownership check, not a flag supplied by a player. */
    public String exportOwnCode(GenesisBlueprint blueprint, KnowledgeSnapshot knowledge, DisclosureAccess access) {
        if (!policy.ownerExportEnabled()) throw denied("Owner export disabled");
        require(access, EXPORT_OWN_CODE);
        return fullCode(blueprint, knowledge, access);
    }

    private PhenotypeResolver.Result validate(GenesisBlueprint blueprint, KnowledgeSnapshot knowledge, DisclosureAccess access) {
        Objects.requireNonNull(knowledge); Objects.requireNonNull(access);
        if (!knowledge.viewerId().equals(access.viewerId()) || !knowledge.subject().equals(access.subject())) throw denied("Knowledge scope mismatch");
        var phenotype = resolver.resolve(blueprint);
        if (!knowledge.blueprintSha256().equals(fingerprint(blueprint))) throw denied("Knowledge belongs to another genetic snapshot");
        if (!phenotype.visualStates().keySet().containsAll(knowledge.observedVisualSlots())
                || !blueprint.genome().genes().keySet().containsAll(knowledge.analyzedGenes())) throw denied("Invalid knowledge facts");
        return phenotype;
    }

    private static boolean complete(GenesisBlueprint blueprint, KnowledgeSnapshot knowledge) {
        return knowledge.analyzedGenes().equals(blueprint.genome().genes().keySet());
    }

    private static Update update(KnowledgeSnapshot current, Set<String> observed, Set<ContentId> analyzed) {
        if (observed.equals(current.observedVisualSlots()) && analyzed.equals(current.analyzedGenes())) return new Update(current.revision(), current);
        return new Update(current.revision(), new KnowledgeSnapshot(current.viewerId(), current.subject(), current.blueprintSha256(),
                Math.incrementExact(current.revision()), observed, analyzed));
    }

    private String fingerprint(GenesisBlueprint blueprint) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(codec.encodePayload(blueprint))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(DisclosureAccess access, DisclosureAccess.Permission permission) {
        if (!Objects.requireNonNull(access).permissions().contains(permission)) throw denied("Disclosure permission missing");
    }
    private static IllegalArgumentException denied(String reason) { return new IllegalArgumentException(reason); }
}
