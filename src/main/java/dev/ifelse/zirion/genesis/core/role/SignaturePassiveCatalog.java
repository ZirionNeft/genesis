package dev.ifelse.zirion.genesis.core.role;

import dev.ifelse.zirion.genesis.core.content.v2.HybridGraph;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Frozen selection metadata. Descriptions are display data, never executable effect definitions. */
public record SignaturePassiveCatalog(ExpressionRules.References references, Set<ContentId> roles,
                                      Map<ContentId, Definition> forms) {
    public static final Set<ContentId> SUPPORTED_ROLES = Set.of(
            ContentId.parse("genesis:guard"), ContentId.parse("genesis:hunter"), ContentId.parse("genesis:scout"),
            ContentId.parse("genesis:mount"), ContentId.parse("genesis:hauler"), ContentId.parse("genesis:producer"),
            ContentId.parse("genesis:support"));

    public record Definition(ContentId formId, ContentId familyId, Set<ContentId> allowedRoles,
                             ContentId requiredRole, Map<String, String> descriptions) {
        public Definition {
            Objects.requireNonNull(formId); Objects.requireNonNull(familyId); Objects.requireNonNull(requiredRole);
            allowedRoles = Collections.unmodifiableSortedSet(new TreeSet<>(allowedRoles));
            descriptions = Collections.unmodifiableSortedMap(new TreeMap<>(descriptions));
            if (allowedRoles.isEmpty() || !allowedRoles.contains(requiredRole) || !SUPPORTED_ROLES.containsAll(allowedRoles)) {
                throw new IllegalArgumentException("Passive requires a registered role allowed by its form");
            }
            if (!descriptions.keySet().equals(Set.of("ru", "en"))
                    || descriptions.values().stream().anyMatch(text -> text == null || text.isBlank())) {
                throw new IllegalArgumentException("Passive needs nonblank Russian and English descriptions");
            }
        }
    }

    public SignaturePassiveCatalog {
        Objects.requireNonNull(references);
        roles = Collections.unmodifiableSortedSet(new TreeSet<>(roles));
        forms = Collections.unmodifiableSortedMap(new TreeMap<>(forms));
        if (!roles.equals(SUPPORTED_ROLES)) throw new IllegalArgumentException("Unsupported role set");
        forms.forEach((id, definition) -> {
            Objects.requireNonNull(definition);
            if (!id.equals(definition.formId())) throw new IllegalArgumentException("Passive form key differs from its definition");
        });
    }

    public void validate(HybridGraph graph) {
        if (!references.hybridGraph().equals(graph.reference()) || !forms.keySet().equals(graph.forms().keySet())) {
            throw new IllegalArgumentException("Passive catalog needs the exact graph and complete form coverage");
        }
        forms.forEach((id, definition) -> {
            if (!definition.familyId().equals(graph.form(id).familyId())) throw new IllegalArgumentException("Passive belongs to another family");
        });
    }
}
