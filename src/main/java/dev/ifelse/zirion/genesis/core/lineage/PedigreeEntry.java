package dev.ifelse.zirion.genesis.core.lineage;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Identity/parents, separate from genes. A reference founder is an explicit population assumption. */
public record PedigreeEntry(UUID creatureId, Optional<UUID> parentA, Optional<UUID> parentB,
                            boolean referenceFounder) {
    public PedigreeEntry {
        Objects.requireNonNull(creatureId, "creatureId");
        Objects.requireNonNull(parentA, "parentA");
        Objects.requireNonNull(parentB, "parentB");
        if (parentA.filter(creatureId::equals).isPresent() || parentB.filter(creatureId::equals).isPresent()) {
            throw new IllegalArgumentException("An individual cannot be their own parent");
        }
        if (parentA.isPresent() && parentA.equals(parentB)) throw new IllegalArgumentException("Parents must be distinct");
        if (referenceFounder && (parentA.isPresent() || parentB.isPresent())) {
            throw new IllegalArgumentException("Reference founder cannot have recorded parents");
        }
    }

    public static PedigreeEntry founder(UUID id) {
        return new PedigreeEntry(id, Optional.empty(), Optional.empty(), true);
    }

    public static PedigreeEntry unknown(UUID id) {
        return new PedigreeEntry(id, Optional.empty(), Optional.empty(), false);
    }

    public static PedigreeEntry child(UUID id, UUID a, UUID b) {
        return new PedigreeEntry(id, Optional.of(a), Optional.of(b), false);
    }
}
