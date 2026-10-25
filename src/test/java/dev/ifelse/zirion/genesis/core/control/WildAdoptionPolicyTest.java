package dev.ifelse.zirion.genesis.core.control;

import dev.ifelse.zirion.genesis.core.control.AdoptionEvidence.*;
import dev.ifelse.zirion.genesis.core.control.WildAdoptionPolicy.*;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.wild.WildCandidateRecord.NpcIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WildAdoptionPolicyTest {
    private static final ContentId PROVIDER = ContentId.parse("test:npc_provider");
    private static final ContentId NATIVE = ContentId.parse("test:native_observer");
    private static final ContentId OTHER = ContentId.parse("other:observer");
    private static final StateVersion VERSION = new StateVersion(UUID.randomUUID(), 4);
    private static final NpcIdentity NPC = new NpcIdentity(PROVIDER, UUID.randomUUID(), UUID.randomUUID());
    private static final Request REQUEST = new Request(UUID.randomUUID(), UUID.randomUUID(), NPC, Intent.ADOPT);
    private final WildAdoptionPolicy policy = new WildAdoptionPolicy();

    @Test
    void readyRequiresTheCompleteTrustedPreflightAndCanBeRecheckedUnchanged() {
        var scene = new Scene();
        var facts = scene.facts();
        var authorization = authorization(REQUEST);
        var ready = assertInstanceOf(Ready.class, policy.begin(REQUEST, facts, authorization));
        assertEquals(REQUEST, ready.request());
        assertEquals(facts, ready.facts());
        assertEquals(authorization, ready.authorization());
        assertTrue(policy.inspect(facts).isEmpty());
        assertInstanceOf(Ready.class, policy.revalidate(ready, scene.facts(), authorization(REQUEST)));
    }

    @Test
    void inspectionOrAutomaticRequestsNeverBecomeAdoptionEvenWithPermission() {
        for (var intent : List.of(Intent.OBSERVE, Intent.AUTOMATIC)) {
            var request = new Request(REQUEST.operationId(), REQUEST.actorId(), NPC, intent);
            assertReasons(policy.begin(request, new Scene().facts(), authorization(request)), Reason.EXPLICIT_ADOPTION_REQUIRED);
        }
    }

    @Test
    void onlyVerifiedWildOriginQualifiesAndDiagnosticFixturesRemainExcluded() {
        for (var origin : Origin.values()) if (origin != Origin.VANILLA_WILD) {
            var scene = new Scene(); scene.origin = origin;
            assertInstanceOf(Denied.class, policy.begin(REQUEST, scene.facts(), authorization(REQUEST)));
        }
        var scene = new Scene(); scene.diagnostic = true;
        assertReasons(policy.begin(REQUEST, scene.facts(), authorization(REQUEST)), Reason.DIAGNOSTIC_TARGET);
    }

    @Test
    void unknownConflictingAndExistingOwnersDenyIncludingTheSamePlayer() {
        var scene = new Scene();
        scene.owner = new Ownership(OwnerState.UNKNOWN, Optional.empty());
        assertReasons(begin(scene), Reason.OWNER_UNKNOWN);
        scene.owner = new Ownership(OwnerState.CONFLICT, Optional.empty());
        assertReasons(begin(scene), Reason.EVIDENCE_CONFLICT);
        for (var owner : List.of(REQUEST.actorId(), UUID.randomUUID())) {
            scene.owner = new Ownership(OwnerState.OWNED, Optional.of(owner));
            assertReasons(begin(scene), Reason.OWNER_PRESENT);
        }
    }

    @Test
    void externalAndGenesisControllersDenyRatherThanTransferOrReadopt() {
        var scene = new Scene();
        scene.claims = List.of(claim(NATIVE, "other:pet_controller"));
        assertReasons(begin(scene), Reason.EXTERNAL_CONTROLLER);
        scene.claims = List.of(claim(NATIVE, WildAdoptionPolicy.GENESIS_CONTROLLER.toString()));
        assertReasons(begin(scene), Reason.ALREADY_GENESIS);
        // A diagnostic component or a Genesis-looking namespace is not controller ownership.
        scene.claims = List.of(claim(NATIVE, "genesis:compatibility_probe_v1"));
        assertReasons(begin(scene), Reason.EXTERNAL_CONTROLLER);
    }

    @Test
    void absentUnknownIncompleteAndContradictoryControllerEvidenceCannotPass() {
        var scene = new Scene(); scene.claims = List.of();
        assertReasons(begin(scene), Reason.CONTROLLER_UNKNOWN);
        scene.claims = List.of(new ControlClaim(NATIVE, ClaimState.UNKNOWN, Optional.empty()));
        assertReasons(begin(scene), Reason.CONTROLLER_UNKNOWN);
        scene.claims = List.of(unclaimed(NATIVE)); scene.coverage = Gate.UNKNOWN;
        assertReasons(begin(scene), Reason.CONTROLLER_UNKNOWN);
        scene.coverage = Gate.PASS; scene.claims = List.of(unclaimed(NATIVE), unclaimed(NATIVE));
        assertReasons(begin(scene), Reason.EVIDENCE_CONFLICT);
        scene.claims = List.of(claim(NATIVE, "one:controller"), claim(OTHER, "two:controller"));
        assertReasons(begin(scene), Reason.EXTERNAL_CONTROLLER, Reason.EVIDENCE_CONFLICT);
    }

    @Test
    void everyUnverifiedOrBlockedIntegrationPrerequisiteDenies() {
        for (var gate : List.of(Gate.UNKNOWN, Gate.BLOCKED)) {
            var scene = new Scene(); scene.available = gate; assertReasons(begin(scene), Reason.NPC_UNAVAILABLE);
            scene = new Scene(); scene.unreserved = gate; assertReasons(begin(scene), Reason.RESERVATION_NOT_CLEAR);
            scene = new Scene(); scene.binding = gate; assertReasons(begin(scene), Reason.BINDING_UNVERIFIED);
            scene = new Scene(); scene.restoration = gate; assertReasons(begin(scene), Reason.RESTORATION_UNVERIFIED);
            scene = new Scene(); scene.age = gate; assertReasons(begin(scene), Reason.INITIAL_AGE_UNRESOLVED);
            scene = new Scene(); scene.commit = gate; assertReasons(begin(scene), Reason.DURABLE_COMMIT_UNVERIFIED);
        }
    }

    @Test
    void unknownStateOrAuthorizationRevisionDoesNotMintAReadyBaseline() {
        var scene = new Scene(); scene.version = Optional.empty();
        assertReasons(begin(scene), Reason.NPC_REVISION_UNVERIFIED);
        var auth = new Authorization(REQUEST, Optional.empty(), Gate.PASS, Gate.PASS);
        assertReasons(policy.begin(REQUEST, new Scene().facts(), auth), Reason.AUTHORIZATION_REVISION_UNVERIFIED);
    }

    @Test
    void authorizationIsBoundToPlayerOperationProviderWorldEntityAndIntent() {
        var variants = List.of(
                new Request(UUID.randomUUID(), REQUEST.actorId(), NPC, Intent.ADOPT),
                new Request(REQUEST.operationId(), UUID.randomUUID(), NPC, Intent.ADOPT),
                new Request(REQUEST.operationId(), REQUEST.actorId(), new NpcIdentity(OTHER, NPC.worldId(), NPC.entityId()), Intent.ADOPT),
                new Request(REQUEST.operationId(), REQUEST.actorId(), new NpcIdentity(PROVIDER, UUID.randomUUID(), NPC.entityId()), Intent.ADOPT),
                new Request(REQUEST.operationId(), REQUEST.actorId(), new NpcIdentity(PROVIDER, NPC.worldId(), UUID.randomUUID()), Intent.ADOPT),
                new Request(REQUEST.operationId(), REQUEST.actorId(), NPC, Intent.OBSERVE));
        for (var other : variants) {
            assertReasons(policy.begin(REQUEST, new Scene().facts(), authorization(other)), Reason.AUTHORIZATION_SCOPE_MISMATCH);
        }
        var scene = new Scene(); scene.npc = new NpcIdentity(PROVIDER, UUID.randomUUID(), NPC.entityId());
        assertReasons(begin(scene), Reason.SUBJECT_MISMATCH);
    }

    @Test
    void permissionAndBeforeFailureOrUnavailabilityAreFailClosed() {
        for (var gate : List.of(Gate.UNKNOWN, Gate.BLOCKED)) {
            var auth = new Authorization(REQUEST, Optional.of(VERSION), gate, Gate.PASS);
            assertReasons(policy.begin(REQUEST, new Scene().facts(), auth), Reason.PERMISSION_NOT_GRANTED);
            auth = new Authorization(REQUEST, Optional.of(VERSION), Gate.PASS, gate);
            assertReasons(policy.begin(REQUEST, new Scene().facts(), auth), Reason.BEFORE_CHECKS_NOT_PASSED);
        }
    }

    @Test
    void revalidationRejectsChangedOwnerAbaRevisionAndCollectorRestart() {
        var ready = assertInstanceOf(Ready.class, begin(new Scene()));
        var scene = new Scene(); scene.owner = new Ownership(OwnerState.OWNED, Optional.of(UUID.randomUUID()));
        assertReasons(policy.revalidate(ready, scene.facts(), authorization(REQUEST)), Reason.OWNER_PRESENT, Reason.STATE_CHANGED);
        // Same visible facts after an external claim was acquired/released still has a newer state revision.
        scene = new Scene(); scene.version = Optional.of(new StateVersion(VERSION.epoch(), VERSION.revision() + 2));
        assertInstanceOf(Ready.class, begin(scene));
        assertReasons(policy.revalidate(ready, scene.facts(), authorization(REQUEST)), Reason.STATE_CHANGED);
        scene = new Scene(); scene.version = Optional.of(new StateVersion(UUID.randomUUID(), VERSION.revision()));
        assertReasons(policy.revalidate(ready, scene.facts(), authorization(REQUEST)), Reason.STATE_CHANGED);
        scene = new Scene(); scene.binding = Gate.UNKNOWN;
        assertReasons(policy.revalidate(ready, scene.facts(), authorization(REQUEST)), Reason.STATE_CHANGED, Reason.BINDING_UNVERIFIED);
    }

    @Test
    void revalidationRejectsPermissionRevocationAndEligibleAuthorizationAba() {
        var ready = assertInstanceOf(Ready.class, begin(new Scene()));
        var changed = new Authorization(REQUEST, Optional.of(VERSION), Gate.BLOCKED, Gate.PASS);
        assertReasons(policy.revalidate(ready, new Scene().facts(), changed), Reason.PERMISSION_NOT_GRANTED, Reason.AUTHORIZATION_CHANGED);
        changed = new Authorization(REQUEST, Optional.of(new StateVersion(VERSION.epoch(), VERSION.revision() + 2)), Gate.PASS, Gate.PASS);
        assertInstanceOf(Ready.class, policy.begin(REQUEST, new Scene().facts(), changed));
        assertReasons(policy.revalidate(ready, new Scene().facts(), changed), Reason.AUTHORIZATION_CHANGED);
    }

    @Test
    void claimsAreImmutableOrderIndependentAndInvalidInputsCannotDescribeOwnership() {
        var first = new Scene();
        var input = new ArrayList<>(List.of(unclaimed(OTHER), unclaimed(NATIVE)));
        first.claims = input; var facts = first.facts(); input.clear();
        assertEquals(2, facts.controllers().size());
        assertThrows(UnsupportedOperationException.class, () -> facts.controllers().clear());
        var second = new Scene(); second.claims = List.of(unclaimed(NATIVE), unclaimed(OTHER));
        assertEquals(facts, second.facts());
        assertThrows(UnsupportedOperationException.class, () -> policy.inspect(new SceneWithUnknownOrigin().facts()).clear());
        assertThrows(IllegalArgumentException.class, () -> new StateVersion(VERSION.epoch(), -1));
        assertThrows(IllegalArgumentException.class, () -> new Ownership(OwnerState.OWNED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Ownership(OwnerState.UNOWNED, Optional.of(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> new ControlClaim(NATIVE, ClaimState.CLAIMED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ControlClaim(NATIVE, ClaimState.UNKNOWN, Optional.of(OTHER)));
    }

    private Result begin(Scene scene) { return policy.begin(REQUEST, scene.facts(), authorization(REQUEST)); }
    private static Authorization authorization(Request request) { return new Authorization(request, Optional.of(VERSION), Gate.PASS, Gate.PASS); }
    private static ControlClaim unclaimed(ContentId source) { return new ControlClaim(source, ClaimState.UNCLAIMED, Optional.empty()); }
    private static ControlClaim claim(ContentId source, String controller) { return new ControlClaim(source, ClaimState.CLAIMED, Optional.of(ContentId.parse(controller))); }
    private static void assertReasons(Result result, Reason... reasons) {
        assertTrue(assertInstanceOf(Denied.class, result).reasons().containsAll(Set.of(reasons)), result.toString());
    }

    private static class Scene {
        NpcIdentity npc = NPC;
        Optional<StateVersion> version = Optional.of(VERSION);
        Origin origin = Origin.VANILLA_WILD;
        Ownership owner = new Ownership(OwnerState.UNOWNED, Optional.empty());
        Gate coverage = Gate.PASS, available = Gate.PASS, unreserved = Gate.PASS, binding = Gate.PASS,
                restoration = Gate.PASS, age = Gate.PASS, commit = Gate.PASS;
        List<ControlClaim> claims = List.of(unclaimed(NATIVE));
        boolean diagnostic;
        NpcFacts facts() {
            return new NpcFacts(npc, version, origin, owner, coverage, claims, available, unreserved, binding, restoration, age, commit, diagnostic);
        }
    }
    private static final class SceneWithUnknownOrigin extends Scene {
        SceneWithUnknownOrigin() { origin = Origin.UNKNOWN; }
    }
}
