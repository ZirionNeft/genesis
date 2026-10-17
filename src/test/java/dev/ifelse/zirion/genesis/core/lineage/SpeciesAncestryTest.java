package dev.ifelse.zirion.genesis.core.lineage;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpeciesAncestryTest {
    private static final ContentId WOLF = ContentId.parse("genesis:forest_wolf");
    private static final ContentId FOX = ContentId.parse("genesis:fox");

    @Test
    void gddHybridAndBackcrossProduceHalfAndThreeQuarterAncestry() {
        var wolf = SpeciesAncestry.original(WOLF);
        var fox = SpeciesAncestry.original(FOX);
        var hybrid = SpeciesAncestry.childOf(wolf, fox);
        assertEquals(Map.of(WOLF, new BigDecimal("0.5"), FOX, new BigDecimal("0.5")), hybrid.shares());
        assertEquals(BigDecimal.ZERO, hybrid.unknownShare());
        assertEquals(hybrid, SpeciesAncestry.childOf(fox, wolf));
        var backcross = SpeciesAncestry.childOf(hybrid, wolf);
        assertEquals(Map.of(WOLF, new BigDecimal("0.75"), FOX, new BigDecimal("0.25")), backcross.shares());
        assertEquals(wolf, SpeciesAncestry.childOf(wolf, wolf));
    }

    @Test
    void unknownAncestryIsPreservedInsteadOfRenormalizingTheKnownPart() {
        var wolf = SpeciesAncestry.original(WOLF);
        var partial = SpeciesAncestry.childOf(wolf, SpeciesAncestry.unknown());
        assertEquals(Map.of(WOLF, new BigDecimal("0.5")), partial.shares());
        assertEquals(new BigDecimal("0.5"), partial.unknownShare());
        var child = SpeciesAncestry.childOf(partial, wolf);
        assertEquals(Map.of(WOLF, new BigDecimal("0.75")), child.shares());
        assertEquals(new BigDecimal("0.25"), child.unknownShare());
        assertEquals(SpeciesAncestry.unknown(), SpeciesAncestry.childOf(SpeciesAncestry.unknown(), SpeciesAncestry.unknown()));
    }

    @Test
    void requiresNonnegativeSharesWithAnExactTotalOfOne() {
        assertThrows(IllegalArgumentException.class, () -> new SpeciesAncestry(Map.of(), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SpeciesAncestry(Map.of(WOLF, new BigDecimal("1.1")), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SpeciesAncestry(Map.of(WOLF, new BigDecimal("-0.1")), new BigDecimal("1.1")));
        assertThrows(IllegalArgumentException.class, () -> new SpeciesAncestry(Map.of(WOLF, BigDecimal.ONE), new BigDecimal("0.1")));
        assertThrows(IllegalArgumentException.class, () -> new SpeciesAncestry(Map.of(WOLF, new BigDecimal("0.9")), BigDecimal.ZERO));
    }

    @Test
    void snapshotsAreImmutableAndDecimalRepresentationsAreCanonical() {
        var source = new HashMap<ContentId, BigDecimal>();
        source.put(WOLF, new BigDecimal("1.000"));
        source.put(FOX, new BigDecimal("0.00"));
        var ancestry = new SpeciesAncestry(source, new BigDecimal("0.00"));
        source.clear();
        assertEquals(SpeciesAncestry.original(WOLF), ancestry);
        assertThrows(UnsupportedOperationException.class, () -> ancestry.shares().clear());
    }
}
