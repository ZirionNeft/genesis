package dev.ifelse.zirion.genesis.core.knowledge;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Public allowlist DTO: no genome, analysis, lineage weights, potential, blueprint hash, seed, extensions or full code. */
public record PublicPassport(KnowledgeSubject subject, Map<String, String> observedVisualTraits) {
    public PublicPassport {
        Objects.requireNonNull(subject);
        var copy = new TreeMap<String, String>();
        observedVisualTraits.forEach((slot, state) -> copy.put(Objects.requireNonNull(slot), Objects.requireNonNull(state)));
        observedVisualTraits = Collections.unmodifiableSortedMap(copy);
    }
}
