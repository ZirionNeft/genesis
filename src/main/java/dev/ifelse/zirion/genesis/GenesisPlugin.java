package dev.ifelse.zirion.genesis;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.ifelse.zirion.genesis.config.ConfigurationException;
import dev.ifelse.zirion.genesis.config.ConfigurationRuntime;
import dev.ifelse.zirion.genesis.config.RuntimeBootstrap;
import dev.ifelse.zirion.genesis.prototype.AnatomyProbeCommand;

import javax.annotation.Nonnull;

/** Entry point for Genesis, developed by Zirion and published by if-else.dev. */
public final class GenesisPlugin extends JavaPlugin {
    private ConfigurationRuntime runtimeConfiguration;

    public GenesisPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        if (Boolean.getBoolean("genesis.anatomyProbe")) {
            getCommandRegistry().registerCommand(new AnatomyProbeCommand());
            getLogger().atInfo().log("Genesis stage 0 anatomy probe enabled");
        }
        try {
            runtimeConfiguration = RuntimeBootstrap.load(getClass().getClassLoader(),
                    getDataDirectory().resolve(RuntimeBootstrap.CONFIG_FILENAME));
        } catch (ConfigurationException failure) {
            getLogger().atWarning().log("Genesis v0.7 definitions unavailable [%s] at %s; prototype remains available",
                    failure.code(), failure.path());
        }
    }

    @Override
    protected void start() {
        getLogger().atInfo().log("Genesis %s started; v0.7 definitions %s", getManifest().getVersion(),
                runtimeConfiguration == null ? "unavailable" : "loaded");
    }

    @Override
    protected void shutdown() {
        runtimeConfiguration = null;
        getLogger().atInfo().log("Genesis stopped");
    }
}
