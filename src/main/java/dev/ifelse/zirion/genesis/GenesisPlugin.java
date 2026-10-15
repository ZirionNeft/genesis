package dev.ifelse.zirion.genesis;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.ifelse.zirion.genesis.prototype.AnatomyProbeCommand;

import javax.annotation.Nonnull;

/** Entry point for Genesis, developed by Zirion and published by if-else.dev. */
public final class GenesisPlugin extends JavaPlugin {

    public GenesisPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        if (Boolean.getBoolean("genesis.anatomyProbe")) {
            getCommandRegistry().registerCommand(new AnatomyProbeCommand());
            getLogger().atInfo().log("Genesis stage 0 anatomy probe enabled");
        }
    }

    @Override
    protected void start() {
        getLogger().atInfo().log("Genesis %s started", getManifest().getVersion());
    }

    @Override
    protected void shutdown() {
        getLogger().atInfo().log("Genesis stopped");
    }
}
