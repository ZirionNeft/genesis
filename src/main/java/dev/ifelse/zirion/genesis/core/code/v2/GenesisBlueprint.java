package dev.ifelse.zirion.genesis.core.code.v2;

import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.content.v2.FormIdentity;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Full hidden genetic recipe. Neither a public passport, an instance record nor authorization to spawn. */
public record GenesisBlueprint(ExpressionRules.References references, ContentId historicalForm, Genome genome,
                               long visualSeed, List<OpaqueExtension> extensions) {
    public record OpaqueExtension(RegistryFingerprint definition, int schemaVersion,
                                  ExtensionPayload.Kind kind, ImmutableBytes payload) {
        public OpaqueExtension {
            Objects.requireNonNull(definition); Objects.requireNonNull(kind); Objects.requireNonNull(payload);
            if (schemaVersion < 1) throw new IllegalArgumentException("Invalid extension schema version");
        }
    }

    public GenesisBlueprint {
        Objects.requireNonNull(references); Objects.requireNonNull(historicalForm); Objects.requireNonNull(genome);
        if (!references.geneSchema().equals(genome.geneSchema())) throw new IllegalArgumentException("Blueprint and genome pins differ");
        extensions = extensions.stream().sorted(Comparator.comparing(value -> value.definition().reference().id())).toList();
        var ids = new HashSet<ContentId>();
        for (var extension : extensions) {
            if (!ids.add(extension.definition().reference().id())) throw new IllegalArgumentException("Duplicate extension ID");
        }
    }

    public FormIdentity form() { return new FormIdentity(references.hybridGraph(), historicalForm); }
}
