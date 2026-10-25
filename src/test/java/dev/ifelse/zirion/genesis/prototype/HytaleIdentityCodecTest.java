package dev.ifelse.zirion.genesis.prototype;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Artifact contract checks, not proof of UUID persistence through a live world's save/load. */
class HytaleIdentityCodecTest {
    @Test
    void uuidComponentRoundtripPreservesTheExistingIdAndUsesBinaryUuid() {
        var id = UUID.fromString("2cb881c6-8838-33a6-ab2a-c93dd3b598ff");
        var component = new UUIDComponent(id);
        var document = UUIDComponent.CODEC.encode(component, new ExtraInfo());
        assertTrue(document.get("UUID").isBinary());
        assertEquals(id, UUIDComponent.CODEC.decode(document, new ExtraInfo()).getUuid());
        assertEquals(id, component.getUuid());
    }

    @Test
    void missingUuidIsGeneratedDuringDecodeAndCannotBeUsedAsEvidenceOfContinuity() {
        var a = UUIDComponent.CODEC.decode(new BsonDocument(), new ExtraInfo());
        var b = UUIDComponent.CODEC.decode(new BsonDocument(), new ExtraInfo());
        assertNotNull(a.getUuid()); assertNotNull(b.getUuid());
        assertNotEquals(a.getUuid(), b.getUuid());
    }
}
