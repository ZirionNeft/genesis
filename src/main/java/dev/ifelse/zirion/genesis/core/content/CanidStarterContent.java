package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.AlleleDefinition;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Executable draft for GDD D05; symbolic expressions, no gameplay stats or asset mappings. */
public final class CanidStarterContent {
    public static final ContentId FAMILY = id("canids");
    public static final ContentId RIG = id("quadruped_medium");
    public static final ContentId WOLF = id("forest_wolf");
    public static final ContentId FOX = id("fox");
    public static final RulesetVersion RULESET = new RulesetVersion(id("canids_draft"), 1);

    private record Row(Locus locus, String a, String b, String aa, String ab, String bb) {}

    private static final List<Row> ROWS = List.of(
            new Row(Locus.FRAME, "normal", "large", "normal", "large", "very_large"),
            new Row(Locus.HEAD, "wolf", "fox", "wolf", "wolf_fox", "fox"),
            new Row(Locus.LIMBS, "normal", "runner", "normal", "athletic", "runner"),
            new Row(Locus.APPENDAGE, "wolf_tail", "bushy_tail", "wolf_tail", "bushy_tail", "bushy_tail"),
            new Row(Locus.PIGMENT, "grey", "orange", "grey", "grey_orange", "orange"),
            new Row(Locus.COVER, "dense_fur", "short_fur", "dense_fur", "medium_fur", "short_fur"),
            new Row(Locus.MUSCLE, "strong", "normal", "strong", "balanced", "normal"),
            new Row(Locus.STAMINA, "normal", "high", "normal", "high", "high"),
            new Row(Locus.METABOLISM, "normal", "fast", "normal", "intermediate", "fast"),
            new Row(Locus.SENSE, "keen_smell", "night_vision", "keen_smell", "mixed_senses", "night_vision"),
            new Row(Locus.TEMPER, "loyal", "cautious", "loyal", "watchful", "cautious"),
            new Row(Locus.ARCANA, "dormant", "shadow_affinity", "dormant", "shadow_affinity", "shadow_affinity")
    );

    private CanidStarterContent() {
    }

    public static GeneticsCatalog create() {
        var builder = GeneticsRuleset.builder(RULESET);
        EnumMap<Locus, AllelePair> wolf = new EnumMap<>(Locus.class);
        EnumMap<Locus, AllelePair> fox = new EnumMap<>(Locus.class);
        for (Row row : ROWS) {
            ContentId a = allele(row, row.a());
            ContentId b = allele(row, row.b());
            builder.registerAllele("genesis", new AlleleDefinition(a, row.locus()));
            builder.registerAllele("genesis", new AlleleDefinition(b, row.locus()));
            builder.registerExpression(row.locus(), new AllelePair(a, a), expression(row, row.aa()));
            builder.registerExpression(row.locus(), new AllelePair(a, b), expression(row, row.ab()));
            builder.registerExpression(row.locus(), new AllelePair(b, b), expression(row, row.bb()));
            // FRAME follows the GDD normal/large example. Both starters have dormant ARCANA.
            wolf.put(row.locus(), row.locus() == Locus.FRAME ? new AllelePair(a, b) : new AllelePair(a, a));
            fox.put(row.locus(), row.locus() == Locus.FRAME || row.locus() == Locus.ARCANA
                    ? new AllelePair(a, a) : new AllelePair(b, b));
        }
        return GeneticsCatalog.builder(id("starter_draft"), 1)
                .registerRuleset("genesis", builder.freeze())
                .registerRig("genesis", new RigDefinition(RIG, 1))
                .registerFamily("genesis", new FamilyDefinition(FAMILY, 1, Set.of(RIG), RULESET))
                .registerSpecies("genesis", new SpeciesDefinition(WOLF, 1, FAMILY, RIG, new Genome(RULESET, wolf)))
                .registerSpecies("genesis", new SpeciesDefinition(FOX, 1, FAMILY, RIG, new Genome(RULESET, fox)))
                .freeze();
    }

    private static ContentId id(String path) {
        return new ContentId("genesis", path);
    }

    private static ContentId allele(Row row, String variant) {
        return id("allele/" + row.locus().name().toLowerCase(Locale.ROOT) + "/" + variant);
    }

    private static ContentId expression(Row row, String variant) {
        return id("expression/" + row.locus().name().toLowerCase(Locale.ROOT) + "/" + variant);
    }
}
