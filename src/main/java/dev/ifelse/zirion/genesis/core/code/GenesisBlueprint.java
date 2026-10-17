package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Genetic/cosmetic recipe, never an instance passport or authorization to spawn. May be unresolved. */
public record GenesisBlueprint(Genome genome, RulesetVersion family, RulesetVersion rig,
                               RulesetVersion archetype, RulesetVersion assetSet,
                               long cosmeticSeed, List<ExtensionPayload> extensions) {
    public static final int MAX_EXTENSIONS = 64;

    public GenesisBlueprint {
        Objects.requireNonNull(genome, "genome");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(archetype, "archetype");
        Objects.requireNonNull(assetSet, "assetSet");
        if (extensions.size() > MAX_EXTENSIONS) throw new IllegalArgumentException("Too many extensions");
        extensions = extensions.stream().sorted(Comparator.comparing(value -> value.key().extension().id())).toList();
        var ids = new HashSet<ContentId>();
        for (ExtensionPayload extension : extensions) {
            if (!ids.add(extension.key().extension().id())) throw new IllegalArgumentException("Duplicate extension ID");
        }
    }
}
