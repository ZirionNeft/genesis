package dev.ifelse.zirion.genesis.prototype;

import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/** Minimal client-side UI loading check for the development stand. */
final class AnatomyProbePage extends BasicCustomUIPage {
    private final Message summary;

    AnatomyProbePage(PlayerRef player, Message summary) {
        super(player, CustomPageLifetime.CanDismiss);
        this.summary = summary;
    }

    @Override
    public void build(@Nonnull UICommandBuilder builder) {
        builder.append("Genesis/AnatomyProbe.ui");
        builder.set("#Title.Text", Message.translation("server.genesis.probe.uiTitle"));
        builder.set("#Summary.Text", summary);
        builder.set("#Hint.Text", Message.translation("server.genesis.probe.uiHint"));
    }
}
