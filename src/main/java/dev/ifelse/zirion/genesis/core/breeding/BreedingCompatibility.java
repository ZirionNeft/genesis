package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.content.GeneticsCatalog;

import java.util.Objects;

/** Pure family/rig/genome eligibility. Does not roll RNG, spend resources, or check age/ownership/kinship. */
public final class BreedingCompatibility {
    private BreedingCompatibility() {
    }

    public enum Level { SAME_ORIGINAL_SPECIES, SAME_FAMILY }

    public enum Reason {
        UNKNOWN_FAMILY, UNKNOWN_RIG, UNSUPPORTED_RIG, UNKNOWN_SPECIES,
        SPECIES_FAMILY_MISMATCH, SPECIES_RIG_MISMATCH, INVALID_GENOME,
        DIFFERENT_FAMILIES, INCOMPATIBLE_RIGS
    }

    public sealed interface Result permits Allowed, Rejected {}

    public record Allowed(Level level, double baseChance) implements Result {
        public Allowed {
            Objects.requireNonNull(level, "level");
            if (!Double.isFinite(baseChance) || baseChance < 0 || baseChance > 1) {
                throw new IllegalArgumentException("Base chance must be in [0, 1]");
            }
        }
    }

    public record Rejected(Reason reason) implements Result {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    public static Result evaluate(BreedingParent a, BreedingParent b, GeneticsCatalog catalog,
                                  CompatibilityPolicy policy) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(policy, "policy");
        Reason invalidA = invalidParent(Objects.requireNonNull(a, "parentA"), catalog);
        if (invalidA != null) return new Rejected(invalidA);
        Reason invalidB = invalidParent(Objects.requireNonNull(b, "parentB"), catalog);
        if (invalidB != null) return new Rejected(invalidB);
        if (!a.familyId().equals(b.familyId())) return new Rejected(Reason.DIFFERENT_FAMILIES);
        // Different rig IDs require an explicit supported mapping, not a guess based on family alone.
        if (!a.rigId().equals(b.rigId())) return new Rejected(Reason.INCOMPATIBLE_RIGS);
        boolean sameOriginalSpecies = a.speciesId().isPresent() && a.speciesId().equals(b.speciesId());
        return sameOriginalSpecies
                ? new Allowed(Level.SAME_ORIGINAL_SPECIES, policy.sameOriginalSpeciesChance())
                : new Allowed(Level.SAME_FAMILY, policy.sameFamilyChance());
    }

    private static Reason invalidParent(BreedingParent parent, GeneticsCatalog catalog) {
        var family = catalog.families().get(parent.familyId());
        if (family == null) return Reason.UNKNOWN_FAMILY;
        if (!catalog.rigs().containsKey(parent.rigId())) return Reason.UNKNOWN_RIG;
        if (!family.supportedRigs().contains(parent.rigId())) return Reason.UNSUPPORTED_RIG;
        if (parent.speciesId().isPresent()) {
            var species = catalog.species().get(parent.speciesId().get());
            if (species == null) return Reason.UNKNOWN_SPECIES;
            if (!species.familyId().equals(parent.familyId())) return Reason.SPECIES_FAMILY_MISMATCH;
            if (!species.rigId().equals(parent.rigId())) return Reason.SPECIES_RIG_MISMATCH;
        }
        try {
            catalog.rulesets().get(family.ruleset().id()).validate(parent.genome());
        } catch (IllegalArgumentException exception) {
            return Reason.INVALID_GENOME;
        }
        return null;
    }
}
