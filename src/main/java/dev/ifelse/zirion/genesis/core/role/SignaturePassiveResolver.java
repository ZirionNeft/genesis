package dev.ifelse.zirion.genesis.core.role;

import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.content.v2.FormIdentity;
import dev.ifelse.zirion.genesis.core.content.v2.FormResolver;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;

import java.util.Objects;
import java.util.Optional;

/** Internal genetic/role applicability only. Never grants a role, activates a passive or changes genetic potential. */
public final class SignaturePassiveResolver {
    public static final RulesetVersion ALGORITHM = new RulesetVersion(ContentId.parse("genesis:signature_passive_selection"), 2);
    public enum Match { ROLE_MATCHED, NO_ROLE_SELECTED, OTHER_ROLE_SELECTED }

    public record Result(RulesetVersion algorithm, ExpressionRules.References references, FormResolver.Result forms,
                         SignaturePassiveCatalog.Definition definition, Optional<ContentId> selectedRole, Match match) {
        public Result {
            Objects.requireNonNull(algorithm); Objects.requireNonNull(references); Objects.requireNonNull(forms);
            Objects.requireNonNull(definition); Objects.requireNonNull(selectedRole); Objects.requireNonNull(match);
        }

        /** Selection is necessary but insufficient for an actual age/training/rig/context-gated effect. */
        public Optional<SignaturePassiveCatalog.Definition> applicablePassive() {
            return match == Match.ROLE_MATCHED ? Optional.of(definition) : Optional.empty();
        }
    }

    private final SignaturePassiveCatalog catalog;
    private final FormResolver forms;

    public SignaturePassiveResolver(CatalogBundle catalogs) {
        Objects.requireNonNull(catalogs);
        this.catalog = catalogs.signaturePassives();
        this.forms = new FormResolver(catalogs.hybridGraph(), catalogs.families());
    }

    /** Every gene and pin is validated even when no role is selected; history never selects the passive. */
    public Result resolve(FormIdentity identity, Genome genome, Optional<ContentId> selectedRole) {
        Objects.requireNonNull(selectedRole);
        var resolved = forms.resolve(identity, genome);
        selectedRole.ifPresent(role -> {
            if (!catalog.roles().contains(role)) throw new IllegalArgumentException("Unknown selected role");
        });
        var definition = catalog.forms().get(resolved.effectiveSignatureForm().id());
        Match match = selectedRole.isEmpty() ? Match.NO_ROLE_SELECTED
                : selectedRole.orElseThrow().equals(definition.requiredRole()) ? Match.ROLE_MATCHED : Match.OTHER_ROLE_SELECTED;
        return new Result(ALGORITHM, catalog.references(), resolved, definition, selectedRole, match);
    }
}
