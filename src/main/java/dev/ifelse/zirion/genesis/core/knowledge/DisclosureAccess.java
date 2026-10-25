package dev.ifelse.zirion.genesis.core.knowledge;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trusted server authorization snapshot, never a client request. Ownership/permissions must be rechecked before delivery. */
public record DisclosureAccess(UUID viewerId, KnowledgeSubject subject, long revision, Set<Permission> permissions) {
    public enum Permission {
        READ_PUBLIC_PASSPORT, READ_ANALYSIS, READ_FULL_CODE, EXPORT_OWN_CODE,
        RECORD_OBSERVATION, RECORD_ANALYSIS
    }

    public DisclosureAccess {
        Objects.requireNonNull(viewerId); Objects.requireNonNull(subject);
        if (revision < 0) throw new IllegalArgumentException("Negative authorization revision");
        permissions = Set.copyOf(permissions);
    }
}
