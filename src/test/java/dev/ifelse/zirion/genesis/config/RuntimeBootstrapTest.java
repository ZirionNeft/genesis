package dev.ifelse.zirion.genesis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeBootstrapTest {
    @TempDir Path directory;

    private ClassLoader coherentResources() throws Exception {
        var sources = CatalogFixtures.bytes(CatalogFixtures.coherent());
        return new ClassLoader(getClass().getClassLoader()) {
            @Override public InputStream getResourceAsStream(String name) {
                for (var document : CatalogDocument.values()) {
                    if (document.resourcePath().equals(name)) return new ByteArrayInputStream(sources.get(document));
                }
                return super.getResourceAsStream(name);
            }
        };
    }

    @Test
    void missingOperatorConfigUsesPackagedDefaultsWithoutCreatingFiles() throws Exception {
        Path file = directory.resolve(RuntimeBootstrap.CONFIG_FILENAME);
        var runtime = RuntimeBootstrap.load(getClass().getClassLoader(), file);
        assertEquals(16, runtime.snapshot().config().lifeSparks().initialCradleCapacity());
        assertFalse(Files.exists(file));
    }

    @Test
    void validFullOverrideIsReadWithoutRewritingIt() throws Exception {
        var documents = CatalogFixtures.coherent();
        var config = documents.get(CatalogDocument.CONFIG);
        config.getAsJsonObject("breeding").addProperty("baseCooldownMinutes", 13);
        Path file = directory.resolve(RuntimeBootstrap.CONFIG_FILENAME);
        Files.writeString(file, config.toString()); byte[] expected = Files.readAllBytes(file);
        var runtime = RuntimeBootstrap.load(coherentResources(), file);
        assertEquals(13, runtime.snapshot().config().breeding().baseCooldownMinutes());
        assertArrayEquals(expected, Files.readAllBytes(file));
    }

    @Test
    void unreadableOrInvalidExistingFileCannotFallBackToDefaults() throws Exception {
        Path file = directory.resolve(RuntimeBootstrap.CONFIG_FILENAME); Files.writeString(file, "{}");
        assertThrows(ConfigurationException.class, () -> RuntimeBootstrap.load(coherentResources(), file));
        Files.delete(file); Files.createDirectory(file);
        assertEquals("INPUT", assertThrows(ConfigurationException.class,
                () -> RuntimeBootstrap.load(coherentResources(), file)).code());
    }

    @Test
    void brokenOperatorSymlinkIsNotTreatedAsAnAbsentConfig() throws Exception {
        Path file = directory.resolve(RuntimeBootstrap.CONFIG_FILENAME);
        Files.createSymbolicLink(file, directory.resolve("missing-target.json"));
        assertEquals("INPUT", assertThrows(ConfigurationException.class,
                () -> RuntimeBootstrap.load(coherentResources(), file)).code());
    }

    @Test
    void staleExpressionFingerprintCannotOverrideDistributedCatalogs() throws Exception {
        Path file = directory.resolve(RuntimeBootstrap.CONFIG_FILENAME);
        var config = CatalogFixtures.coherent().get(CatalogDocument.CONFIG);
        config.getAsJsonObject("genetics").getAsJsonObject("registries").getAsJsonObject("expressionRules")
                .addProperty("sha256", "dc824724ccd18c8f1999b931c98ed17cfb415fd9e9c8d03037134764764ef528");
        Files.writeString(file, config.toString());
        byte[] expected = Files.readAllBytes(file);
        assertEquals("REGISTRY_RELOAD_DENIED", assertThrows(ConfigurationException.class,
                () -> RuntimeBootstrap.load(getClass().getClassLoader(), file)).code());
        assertArrayEquals(expected, Files.readAllBytes(file));
    }
}
