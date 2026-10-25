package dev.ifelse.zirion.genesis.config;

import dev.ifelse.zirion.genesis.core.config.ServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Startup reads only. Never creates/replaces config files or falls back after a configured-file failure. */
public final class RuntimeBootstrap {
    public static final String CONFIG_FILENAME = "genesis-config.json";

    private RuntimeBootstrap() {}

    public static ConfigurationRuntime load(ClassLoader resources, Path configFile) {
        Objects.requireNonNull(resources); Objects.requireNonNull(configFile);
        var catalogs = new CatalogLoader().loadBundled(resources);
        ServerConfig config = catalogs.defaults();
        // NOFOLLOW_LINKS distinguishes a genuinely absent file from a broken operator-supplied symlink.
        if (!Files.notExists(configFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                config = new ConfigLoader().load(Files.newInputStream(configFile), CONFIG_FILENAME);
            } catch (IOException exception) {
                throw new ConfigurationException("INPUT", CONFIG_FILENAME + "#", "Existing configuration cannot be read");
            }
        }
        return new ConfigurationRuntime(catalogs, config);
    }
}
