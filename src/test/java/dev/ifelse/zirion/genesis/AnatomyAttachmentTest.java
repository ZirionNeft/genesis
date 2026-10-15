package dev.ifelse.zirion.genesis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnatomyAttachmentTest {
    @Test
    void packagedAttachmentsUseEmptySocketsAndChildGeometry() throws Exception {
        try (var jar = new JarFile(System.getProperty("genesis.pluginJar"))) {
            var asset = json(jar, "Server/Models/Genesis/Genesis_AnatomyProbe.json");
            var body = json(jar, "Common/" + asset.get("Model").getAsString());
            var roots = body.getAsJsonArray("nodes");
            assertEquals(1, roots.size(), "The body must not contain loose preview parts at its root");
            assertEquals("Origin", roots.get(0).getAsJsonObject().get("name").getAsString());
            Map<String, JsonObject> bodyNodes = new HashMap<>();
            collect(roots.get(0).getAsJsonObject(), bodyNodes);

            for (String slot : new String[]{"Head", "Tail"}) {
                var socket = bodyNodes.get(slot);
                assertNotNull(socket, slot);
                assertEmptyPiece(socket);
                var variants = asset.getAsJsonObject("RandomAttachmentSets").getAsJsonObject(slot.toLowerCase());
                for (var variant : variants.entrySet()) {
                    String path = "Common/" + variant.getValue().getAsJsonObject().get("Model").getAsString();
                    var attachment = json(jar, path).getAsJsonArray("nodes");
                    assertEquals(1, attachment.size(), path);
                    var root = attachment.get(0).getAsJsonObject();
                    assertEquals(slot, root.get("name").getAsString(), path);
                    assertEmptyPiece(root);
                    for (String axis : new String[]{"x", "y", "z"}) {
                        assertEquals(0, root.getAsJsonObject("position").get(axis).getAsDouble(), path);
                    }
                    var children = root.getAsJsonArray("children");
                    assertNotNull(children, path);
                    assertFalse(children.isEmpty(), path);
                    var geometry = children.get(0).getAsJsonObject();
                    assertEquals("box", geometry.getAsJsonObject("shape").get("type").getAsString(), path);
                    Map<String, JsonObject> nodes = new HashMap<>();
                    collect(root, nodes);
                    assertTrue(nodes.size() <= 255, path);
                }
            }
        }
    }

    private static void assertEmptyPiece(JsonObject node) {
        var shape = node.getAsJsonObject("shape");
        assertEquals("none", shape.get("type").getAsString(), node.get("name").getAsString());
        assertTrue(shape.getAsJsonObject("settings").get("isPiece").getAsBoolean());
    }

    private static void collect(JsonObject node, Map<String, JsonObject> nodes) {
        String name = node.get("name").getAsString();
        assertNull(nodes.put(name, node), "Duplicate node name: " + name);
        var children = node.getAsJsonArray("children");
        if (children != null) {
            children.forEach(child -> collect(child.getAsJsonObject(), nodes));
        }
    }

    private static JsonObject json(JarFile jar, String path) throws Exception {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, path);
        try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
