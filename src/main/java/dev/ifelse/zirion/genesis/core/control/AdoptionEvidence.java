package dev.ifelse.zirion.genesis.core.control;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.wild.WildCandidateRecord.NpcIdentity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal trusted collector inputs, never client DTOs or proof supplied by a namespaced ID. */
public final class AdoptionEvidence {
    private AdoptionEvidence() { }

    public enum Gate { PASS, BLOCKED, UNKNOWN }
    public enum Intent { ADOPT, OBSERVE, AUTOMATIC }
    public enum Origin { VANILLA_WILD, GENESIS, EXTERNAL, ADMIN_FIXTURE, IMPORTED, UNKNOWN, CONFLICT }
    public enum ClaimState { UNCLAIMED, CLAIMED, UNKNOWN }
    public enum OwnerState { UNOWNED, OWNED, UNKNOWN, CONFLICT }

    /** Collector/provider epoch and state revision, NOT an inspection counter or a timestamp. */
    public record StateVersion(UUID epoch, long revision) {
        public StateVersion {
            Objects.requireNonNull(epoch);
            if (revision < 0) throw new IllegalArgumentException("Negative authority revision");
        }
    }

    public record Request(UUID operationId, UUID actorId, NpcIdentity npc, Intent intent) {
        public Request {
            Objects.requireNonNull(operationId); Objects.requireNonNull(actorId);
            Objects.requireNonNull(npc); Objects.requireNonNull(intent);
        }
    }

    /** UNCLAIMED requires a positive authoritative observation, not an absent component. */
    public record ControlClaim(ContentId source, ClaimState state, Optional<ContentId> primaryControllerId) {
        public ControlClaim {
            Objects.requireNonNull(source); Objects.requireNonNull(state); Objects.requireNonNull(primaryControllerId);
            if ((state == ClaimState.CLAIMED) != primaryControllerId.isPresent()) {
                throw new IllegalArgumentException("Controller identity must match claim state");
            }
        }
    }

    public record Ownership(OwnerState state, Optional<UUID> ownerId) {
        public Ownership {
            Objects.requireNonNull(state); Objects.requireNonNull(ownerId);
            if ((state == OwnerState.OWNED) != ownerId.isPresent()) {
                throw new IllegalArgumentException("Owner identity must match observation state");
            }
        }
    }

    /** Every guard must be collected again before a durable adoption commit and ECS activation. */
    public record NpcFacts(NpcIdentity npc, Optional<StateVersion> version, Origin origin, Ownership ownership,
                           Gate controllerCoverage, List<ControlClaim> controllers, Gate available, Gate unreserved,
                           Gate bindingSupported, Gate restorationSupported, Gate initialAgeResolved,
                           Gate durableCommitSupported, boolean diagnosticTarget) {
        public NpcFacts {
            Objects.requireNonNull(npc); Objects.requireNonNull(version); Objects.requireNonNull(origin);
            Objects.requireNonNull(ownership); Objects.requireNonNull(controllerCoverage);
            controllers = controllers.stream().sorted(Comparator.comparing(ControlClaim::source)).toList();
            Objects.requireNonNull(available); Objects.requireNonNull(unreserved);
            Objects.requireNonNull(bindingSupported); Objects.requireNonNull(restorationSupported);
            Objects.requireNonNull(initialAgeResolved); Objects.requireNonNull(durableCommitSupported);
        }
    }

    /** Bound to the real actor/action/subject by the server, never by a request-supplied permission flag. */
    public record Authorization(Request request, Optional<StateVersion> version, Gate permission, Gate beforeChecks) {
        public Authorization {
            Objects.requireNonNull(request); Objects.requireNonNull(version);
            Objects.requireNonNull(permission); Objects.requireNonNull(beforeChecks);
        }
    }
}
