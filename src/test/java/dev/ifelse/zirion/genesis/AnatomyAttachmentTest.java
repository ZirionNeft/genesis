package dev.ifelse.zirion.genesis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnatomyAttachmentTest {
    @Test
    void packagedAnimationsTargetRigBonesRatherThanAttachmentPieces() throws Exception {
        try (var jar = new JarFile(System.getProperty("genesis.pluginJar"))) {
            var asset = json(jar, "Server/Models/Genesis/Genesis_AnatomyProbe.json");
            Map<String, JsonObject> bodyNodes = new HashMap<>();
            json(jar, "Common/" + asset.get("Model").getAsString()).getAsJsonArray("nodes")
                    .forEach(node -> collect(node.getAsJsonObject(), bodyNodes));
            for (var set : asset.getAsJsonObject("AnimationSets").entrySet()) {
                for (var animation : set.getValue().getAsJsonObject().getAsJsonArray("Animations")) {
                    var tracks = json(jar, "Common/" + animation.getAsJsonObject()
                            .get("Animation").getAsString()).getAsJsonObject("nodeAnimations");
                    for (String name : tracks.keySet()) {
                        var bone = bodyNodes.get(name);
                        assertNotNull(bone, "Animation target missing from rig: " + name);
                        assertFalse(bone.getAsJsonObject("shape").getAsJsonObject("settings")
                                .get("isPiece").getAsBoolean(), "Animated rig bone is an attachment piece: " + name);
                        for (var slot : asset.getAsJsonObject("RandomAttachmentSets").entrySet()) {
                            for (var variant : slot.getValue().getAsJsonObject().entrySet()) {
                                Map<String, JsonObject> partNodes = new HashMap<>();
                                json(jar, "Common/" + variant.getValue().getAsJsonObject().get("Model").getAsString())
                                        .getAsJsonArray("nodes").forEach(node -> collect(node.getAsJsonObject(), partNodes));
                                assertFalse(partNodes.containsKey(name), "Attachment shadows animated rig bone: " + name);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void packagedWholeControlMatchesModularRestGeometry() throws Exception {
        try (var jar = new JarFile(System.getProperty("genesis.pluginJar"))) {
            var asset = json(jar, "Server/Models/Genesis/Genesis_AnatomyProbe.json");
            var body = json(jar, "Common/" + asset.get("Model").getAsString());
            Map<String, Box> modular = new HashMap<>();
            Map<String, double[]> sockets = new HashMap<>();
            for (var root : body.getAsJsonArray("nodes")) {
                collectGeometry(root.getAsJsonObject(), new double[3], modular, sockets);
            }
            for (var selection : Map.of("head", "wolf", "tail", "fox").entrySet()) {
                var attachment = asset.getAsJsonObject("RandomAttachmentSets")
                        .getAsJsonObject(selection.getKey()).getAsJsonObject(selection.getValue());
                String socket = selection.getKey().equals("head") ? "Head" : "Tail";
                for (var root : json(jar, "Common/" + attachment.get("Model").getAsString())
                        .getAsJsonArray("nodes")) {
                    collectGeometry(root.getAsJsonObject(), sockets.get(socket), modular, new HashMap<>());
                }
            }
            var wholeAsset = json(jar, "Server/Models/Genesis/Genesis_AnatomyProbe_Whole.json");
            Map<String, Box> whole = new HashMap<>();
            for (var root : json(jar, "Common/" + wholeAsset.get("Model").getAsString())
                    .getAsJsonArray("nodes")) {
                collectGeometry(root.getAsJsonObject(), new double[3], whole, new HashMap<>());
            }
            assertEquals(modular.keySet(), whole.keySet(), "Both rendering paths need the same control geometry");
            for (var entry : modular.entrySet()) {
                Box actual = whole.get(entry.getKey());
                assertArrayEquals(entry.getValue().bounds(), actual.bounds(), 0.00001, entry.getKey());
                assertEquals(entry.getValue().textureLayout(), actual.textureLayout(), entry.getKey());
            }
        }
    }

    // Hytale Models compileNode/parseNode place children relative to the parent's
    // shape centre, which includes shape.offset, rather than just its bone origin.
    // This stand has no rest rotations; fail explicitly if that contract changes.
    private static void collectGeometry(JsonObject node, double[] parentCentre, Map<String, Box> boxes,
                                        Map<String, double[]> centres) {
        var orientation = node.getAsJsonObject("orientation");
        assertEquals(1, orientation.get("w").getAsDouble(), 0.00001, node.get("name").getAsString());
        double[] centre = new double[3];
        var shape = node.getAsJsonObject("shape");
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < axes.length; i++) {
            assertEquals(0, orientation.get(axes[i]).getAsDouble(), 0.00001);
            centre[i] = parentCentre[i] + node.getAsJsonObject("position").get(axes[i]).getAsDouble()
                    + shape.getAsJsonObject("offset").get(axes[i]).getAsDouble();
        }
        String name = node.get("name").getAsString();
        centres.put(name, centre);
        if (shape.get("type").getAsString().equals("box")) {
            double[] bounds = new double[6];
            for (int i = 0; i < axes.length; i++) {
                double half = shape.getAsJsonObject("settings").getAsJsonObject("size")
                        .get(axes[i]).getAsDouble() * shape.getAsJsonObject("stretch").get(axes[i]).getAsDouble() / 2;
                bounds[i] = centre[i] - half;
                bounds[i + 3] = centre[i] + half;
            }
            String semanticName = switch (name) {
                case "WolfHeadGeometry" -> "Head";
                case "FoxTailGeometry" -> "Tail";
                default -> name;
            };
            assertNull(boxes.put(semanticName, new Box(bounds, shape.getAsJsonObject("textureLayout"))), name);
        }
        var children = node.getAsJsonArray("children");
        if (children != null) {
            children.forEach(child -> collectGeometry(child.getAsJsonObject(), centre, boxes, centres));
        }
    }

    private record Box(double[] bounds, JsonObject textureLayout) {}

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
                var socket = bodyNodes.get(slot + "Socket");
                assertNotNull(socket, slot);
                assertEmptyPiece(socket);
                assertTrue(bodyNodes.get(slot).getAsJsonArray("children").contains(socket),
                        "Attachment socket must inherit its animated rig bone: " + slot);
                var variants = asset.getAsJsonObject("RandomAttachmentSets").getAsJsonObject(slot.toLowerCase());
                for (var variant : variants.entrySet()) {
                    String path = "Common/" + variant.getValue().getAsJsonObject().get("Model").getAsString();
                    var attachment = json(jar, path).getAsJsonArray("nodes");
                    assertEquals(1, attachment.size(), path);
                    var root = attachment.get(0).getAsJsonObject();
                    assertEquals(slot + "Socket", root.get("name").getAsString(), path);
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
