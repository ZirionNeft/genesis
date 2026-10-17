package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.morphology.MorphologyRules;
import dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Trusted versioned archetype ties genetic/visual/stat definitions to an available asset manifest. */
public record ReproductionProfile(RulesetVersion reference, RulesetVersion family, RulesetVersion rig,
                                  RulesetVersion assetSet, PhenotypeRules phenotype, MorphologyRules morphology,
                                  Set<RulesetVersion> availableAppearances, Set<ExtensionPayload.Key> requiredExtensions) {
    public ReproductionProfile {
        Objects.requireNonNull(reference, "archetype");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(assetSet, "assetSet");
        Objects.requireNonNull(phenotype, "phenotype");
        Objects.requireNonNull(morphology, "morphology");
        availableAppearances = Set.copyOf(availableAppearances);
        requiredExtensions = Set.copyOf(requiredExtensions);
        if (!phenotype.geneticsReference().equals(morphology.geneticsReference()) || !rig.id().equals(morphology.rigId())) {
            throw new IllegalArgumentException("Archetype resolver references disagree");
        }
        if (!morphology.coverage().complete()) throw new IllegalArgumentException("Archetype visual layer has missing signatures");
        for (MorphologyRules.Appearance appearance : morphology.registeredAppearances()) {
            if (!availableAppearances.contains(new RulesetVersion(appearance.id(), appearance.version()))) {
                throw new IllegalArgumentException("Appearance missing from available asset manifest");
            }
        }
        var ids = new HashSet<>();
        for (ExtensionPayload.Key key : requiredExtensions) {
            if (!ids.add(key.extension().id())) throw new IllegalArgumentException("Conflicting required extension versions");
        }
    }
}
