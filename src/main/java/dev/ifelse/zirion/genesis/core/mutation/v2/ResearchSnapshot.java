package dev.ifelse.zirion.genesis.core.mutation.v2;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Objects;
import java.util.UUID;

/** Trusted player/family research state captured for one operation, not a client claim or mutable counter. */
public record ResearchSnapshot(UUID playerId, ContentId familyId, long revision, Tier tier, boolean focused,
                               Origin origin, int qualifyingSuccesses, boolean firstDiscoveryAwarded) {
    public enum Tier { BASE, DISCOVERY, MASTERY }
    public enum Origin { BREEDING, ADMIN, IMPORT }
    public ResearchSnapshot {
        Objects.requireNonNull(playerId); Objects.requireNonNull(familyId); Objects.requireNonNull(tier); Objects.requireNonNull(origin);
        if (revision < 0 || qualifyingSuccesses < 0 || qualifyingSuccesses == Integer.MAX_VALUE && !firstDiscoveryAwarded) throw new IllegalArgumentException("Invalid research state");
    }
}
