package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One frozen content revision; historical revisions are distinct snapshots, not hot replacements. */
public final class GeneticsCatalog {
    private final ContentId id;
    private final int version;
    private final Map<ContentId, RigDefinition> rigs;
    private final Map<ContentId, FamilyDefinition> families;
    private final Map<ContentId, SpeciesDefinition> species;
    private final Map<ContentId, GeneticsRuleset> rulesets;

    private GeneticsCatalog(Builder builder) {
        id = builder.id;
        version = builder.version;
        rigs = snapshot(builder.rigs);
        families = snapshot(builder.families);
        species = snapshot(builder.species);
        rulesets = snapshot(builder.rulesets);
    }

    private static <T> Map<ContentId, T> snapshot(Map<ContentId, T> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }

    public static Builder builder(ContentId id, int version) {
        return new Builder(id, version);
    }

    public ContentId id() { return id; }
    public int version() { return version; }
    public Map<ContentId, RigDefinition> rigs() { return rigs; }
    public Map<ContentId, FamilyDefinition> families() { return families; }
    public Map<ContentId, SpeciesDefinition> species() { return species; }
    public Map<ContentId, GeneticsRuleset> rulesets() { return rulesets; }

    public static final class Builder {
        private final ContentId id;
        private final int version;
        private final Map<ContentId, RigDefinition> rigs = new TreeMap<>();
        private final Map<ContentId, FamilyDefinition> families = new TreeMap<>();
        private final Map<ContentId, SpeciesDefinition> species = new TreeMap<>();
        private final Map<ContentId, GeneticsRuleset> rulesets = new TreeMap<>();
        private GeneticsCatalog frozen;

        private Builder(ContentId id, int version) {
            this.id = Objects.requireNonNull(id, "id");
            if (version < 1) throw new IllegalArgumentException("Catalog version must be positive");
            this.version = version;
        }

        public Builder registerRig(String registrarNamespace, RigDefinition definition) {
            return register(rigs, registrarNamespace, definition.id(), definition);
        }

        public Builder registerFamily(String registrarNamespace, FamilyDefinition definition) {
            return register(families, registrarNamespace, definition.id(), definition);
        }

        public Builder registerSpecies(String registrarNamespace, SpeciesDefinition definition) {
            return register(species, registrarNamespace, definition.id(), definition);
        }

        public Builder registerRuleset(String registrarNamespace, GeneticsRuleset definition) {
            return register(rulesets, registrarNamespace, definition.reference().id(), definition);
        }

        private <T> Builder register(Map<ContentId, T> registry, String registrarNamespace, ContentId id, T definition) {
            if (frozen != null) throw new IllegalStateException("Catalog is already frozen");
            if (!id.namespace().equals(registrarNamespace)) {
                throw new IllegalArgumentException("Registrar cannot write namespace " + id.namespace());
            }
            if (registry.putIfAbsent(id, definition) != null) throw new IllegalArgumentException("Duplicate ID: " + id);
            return this;
        }

        public GeneticsCatalog freeze() {
            if (frozen != null) return frozen;
            for (FamilyDefinition family : families.values()) {
                GeneticsRuleset ruleset = rulesets.get(family.ruleset().id());
                if (ruleset == null || !ruleset.reference().equals(family.ruleset())) {
                    throw new IllegalStateException("Missing family ruleset/version: " + family.id());
                }
                if (!rigs.keySet().containsAll(family.supportedRigs())) {
                    throw new IllegalStateException("Missing family rig: " + family.id());
                }
            }
            for (SpeciesDefinition definition : species.values()) {
                FamilyDefinition family = families.get(definition.familyId());
                if (family == null || !family.supportedRigs().contains(definition.rigId())) {
                    throw new IllegalStateException("Species has unknown family or unsupported rig: " + definition.id());
                }
                try {
                    rulesets.get(family.ruleset().id()).validate(definition.starterGenome());
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("Invalid species starter genome: " + definition.id(), exception);
                }
            }
            frozen = new GeneticsCatalog(this);
            return frozen;
        }
    }
}
