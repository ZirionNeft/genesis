package dev.ifelse.zirion.genesis;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestTest {
    @Test
    void packagedManifestLoadsAndMatchesOnlyPinnedRelease() throws Exception {
        PluginManifest manifest;
        try (JarFile jar = new JarFile(System.getProperty("genesis.pluginJar"))) {
            var entry = jar.getJarEntry("manifest.json");
            assertNotNull(entry);
            String json;
            try (var input = jar.getInputStream(entry)) {
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (var reader = RawJsonReader.fromJsonString(json)) {
                manifest = PluginManifest.CODEC.decodeJson(reader, new ExtraInfo());
            }
        }
        assertNotNull(manifest);
        var range = manifest.getServerVersion();
        assertNotNull(range);
        var target = Semver.fromString(System.getProperty("genesis.hytaleVersion"));
        assertTrue(range.satisfies(target));
        assertFalse(range.satisfies(new Semver(target.getMajor(), target.getMinor(), target.getPatch() + 1)));
        assertFalse(range.satisfies(new Semver(target.getMajor(), target.getMinor() + 1, 0)));
        if (target.getPatch() > 0) {
            assertFalse(range.satisfies(new Semver(target.getMajor(), target.getMinor(), target.getPatch() - 1)));
        }
    }
}
