package dev.ifelse.zirion.genesis.core.control;

import dev.ifelse.zirion.genesis.core.control.AdoptionEvidence.*;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Pure wild-adoption preflight. Never claims a controller, spends resources or commits a creature. */
public final class WildAdoptionPolicy {
    /** Logical Genesis controller ID; not a Hytale asset, reservation or diagnostic component ID. */
    public static final ContentId GENESIS_CONTROLLER = ContentId.parse("genesis:primary_controller");

    public enum Reason {
        EXPLICIT_ADOPTION_REQUIRED, SUBJECT_MISMATCH, AUTHORIZATION_SCOPE_MISMATCH,
        NPC_REVISION_UNVERIFIED, AUTHORIZATION_REVISION_UNVERIFIED, STATE_CHANGED, AUTHORIZATION_CHANGED,
        ORIGIN_UNKNOWN, ORIGIN_NOT_WILD, EVIDENCE_CONFLICT, OWNER_UNKNOWN, OWNER_PRESENT,
        CONTROLLER_UNKNOWN, EXTERNAL_CONTROLLER, ALREADY_GENESIS, DIAGNOSTIC_TARGET,
        NPC_UNAVAILABLE, RESERVATION_NOT_CLEAR, BINDING_UNVERIFIED, RESTORATION_UNVERIFIED,
        INITIAL_AGE_UNRESOLVED, DURABLE_COMMIT_UNVERIFIED, PERMISSION_NOT_GRANTED, BEFORE_CHECKS_NOT_PASSED
    }

    public sealed interface Result permits Denied, Ready { }

    public record Denied(Set<Reason> reasons) implements Result {
        public Denied {
            if (reasons.isEmpty()) throw new IllegalArgumentException("Missing denial reason");
            reasons = immutable(reasons);
        }
    }

    /** A retained comparison baseline, NOT an authorization token or a durable transaction. */
    public static final class Ready implements Result {
        private final Request request;
        private final NpcFacts facts;
        private final Authorization authorization;

        private Ready(Request request, NpcFacts facts, Authorization authorization) {
            this.request = request; this.facts = facts; this.authorization = authorization;
        }
        public Request request() { return request; }
        public NpcFacts facts() { return facts; }
        public Authorization authorization() { return authorization; }
    }

    /** NPC-only diagnostics. An empty set does not check player intent/permission or authorize adoption. */
    public Set<Reason> inspect(NpcFacts facts) {
        Objects.requireNonNull(facts);
        var reasons = EnumSet.noneOf(Reason.class);
        if (facts.version().isEmpty()) reasons.add(Reason.NPC_REVISION_UNVERIFIED);
        switch (facts.origin()) {
            case VANILLA_WILD -> { }
            case UNKNOWN -> reasons.add(Reason.ORIGIN_UNKNOWN);
            case CONFLICT -> reasons.add(Reason.EVIDENCE_CONFLICT);
            default -> reasons.add(Reason.ORIGIN_NOT_WILD);
        }
        switch (facts.ownership().state()) {
            case UNOWNED -> { }
            case UNKNOWN -> reasons.add(Reason.OWNER_UNKNOWN);
            case OWNED -> reasons.add(Reason.OWNER_PRESENT);
            case CONFLICT -> reasons.add(Reason.EVIDENCE_CONFLICT);
        }
        if (facts.controllerCoverage() != Gate.PASS || facts.controllers().isEmpty()) reasons.add(Reason.CONTROLLER_UNKNOWN);
        var sources = new HashSet<ContentId>();
        var controllers = new HashSet<ContentId>();
        for (var claim : facts.controllers()) {
            if (!sources.add(claim.source())) reasons.add(Reason.EVIDENCE_CONFLICT);
            if (claim.state() == ClaimState.UNKNOWN) reasons.add(Reason.CONTROLLER_UNKNOWN);
            claim.primaryControllerId().ifPresent(id -> {
                controllers.add(id);
                reasons.add(id.equals(GENESIS_CONTROLLER) ? Reason.ALREADY_GENESIS : Reason.EXTERNAL_CONTROLLER);
            });
        }
        if (controllers.size() > 1) reasons.add(Reason.EVIDENCE_CONFLICT);
        if (facts.diagnosticTarget()) reasons.add(Reason.DIAGNOSTIC_TARGET);
        require(facts.available(), Reason.NPC_UNAVAILABLE, reasons);
        require(facts.unreserved(), Reason.RESERVATION_NOT_CLEAR, reasons);
        require(facts.bindingSupported(), Reason.BINDING_UNVERIFIED, reasons);
        require(facts.restorationSupported(), Reason.RESTORATION_UNVERIFIED, reasons);
        require(facts.initialAgeResolved(), Reason.INITIAL_AGE_UNRESOLVED, reasons);
        require(facts.durableCommitSupported(), Reason.DURABLE_COMMIT_UNVERIFIED, reasons);
        return immutable(reasons);
    }

    public Result begin(Request request, NpcFacts facts, Authorization authorization) {
        Objects.requireNonNull(request); Objects.requireNonNull(authorization);
        var reasons = EnumSet.noneOf(Reason.class);
        reasons.addAll(inspect(facts));
        if (request.intent() != Intent.ADOPT) reasons.add(Reason.EXPLICIT_ADOPTION_REQUIRED);
        if (!request.npc().equals(facts.npc())) reasons.add(Reason.SUBJECT_MISMATCH);
        if (!request.equals(authorization.request())) reasons.add(Reason.AUTHORIZATION_SCOPE_MISMATCH);
        if (authorization.version().isEmpty()) reasons.add(Reason.AUTHORIZATION_REVISION_UNVERIFIED);
        require(authorization.permission(), Reason.PERMISSION_NOT_GRANTED, reasons);
        require(authorization.beforeChecks(), Reason.BEFORE_CHECKS_NOT_PASSED, reasons);
        return reasons.isEmpty() ? new Ready(request, facts, authorization) : new Denied(reasons);
    }

    /** Re-read trusted inputs immediately before commit. Even an eligible ABA/restart requires a new preflight. */
    public Result revalidate(Ready before, NpcFacts freshFacts, Authorization freshAuthorization) {
        Objects.requireNonNull(before);
        var checked = begin(before.request, freshFacts, freshAuthorization);
        var reasons = EnumSet.noneOf(Reason.class);
        if (checked instanceof Denied denied) reasons.addAll(denied.reasons());
        if (!before.facts.equals(freshFacts)) reasons.add(Reason.STATE_CHANGED);
        if (!before.authorization.equals(freshAuthorization)) reasons.add(Reason.AUTHORIZATION_CHANGED);
        return reasons.isEmpty() ? checked : new Denied(reasons);
    }

    private static void require(Gate gate, Reason reason, Set<Reason> reasons) {
        if (gate != Gate.PASS) reasons.add(reason);
    }

    private static Set<Reason> immutable(Set<Reason> reasons) {
        var result = EnumSet.noneOf(Reason.class);
        result.addAll(reasons);
        return Collections.unmodifiableSet(result);
    }
}
