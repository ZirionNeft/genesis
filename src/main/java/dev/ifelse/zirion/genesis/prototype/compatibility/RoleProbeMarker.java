package dev.ifelse.zirion.genesis.prototype.compatibility;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/** Serializable diagnostic evidence only: this component is neither ownership nor a Genesis creature record. */
public final class RoleProbeMarker implements Component<EntityStore> {
    public static final String ID = "genesis:compatibility_probe_v1";
    public static final BuilderCodec<RoleProbeMarker> CODEC = BuilderCodec.builder(RoleProbeMarker.class, RoleProbeMarker::new)
            .append(new KeyedCodec<>("Schema", Codec.INTEGER), (m, v) -> m.schema = v, m -> m.schema).add()
            .append(new KeyedCodec<>("Operation", Codec.STRING), (m, v) -> m.operation = v, m -> m.operation).add()
            .append(new KeyedCodec<>("World", Codec.STRING), (m, v) -> m.world = v, m -> m.world).add()
            .append(new KeyedCodec<>("Entity", Codec.STRING), (m, v) -> m.entity = v, m -> m.entity).add()
            .append(new KeyedCodec<>("Snapshot", Codec.STRING), (m, v) -> m.snapshot = v, m -> m.snapshot).add()
            .afterDecode(RoleProbeMarker::validate).build();

    private int schema = 1;
    private String operation;
    private String world;
    private String entity;
    private String snapshot;

    private RoleProbeMarker() { }

    public RoleProbeMarker(UUID operation, UUID world, UUID entity, String snapshot) {
        this.schema = 1;
        this.operation = operation.toString();
        this.world = world.toString();
        this.entity = entity.toString();
        this.snapshot = snapshot;
        validate();
    }

    private void validate() {
        if (schema != 1) throw new IllegalArgumentException("Unsupported diagnostic marker schema");
        // Hytale validates a factory-created default during registration. It must remain inert.
        if (operation == null && world == null && entity == null && snapshot == null) return;
        UUID.fromString(operation);
        UUID.fromString(world);
        new RoleProbeSnapshot(UUID.fromString(entity), snapshot);
    }

    public boolean isArmed() { return operation != null; }
    public UUID operationId() {
        if (!isArmed()) throw new IllegalStateException("Unarmed diagnostic marker");
        return UUID.fromString(operation);
    }
    public boolean matches(UUID worldId, UUID entityId) {
        return worldId.toString().equals(world) && entityId.toString().equals(entity);
    }
    public RoleProbeSnapshot baseline() {
        if (!isArmed()) throw new IllegalStateException("Unarmed diagnostic marker");
        return new RoleProbeSnapshot(UUID.fromString(entity), snapshot);
    }
    public String snapshotJson() { return snapshot; }

    @Override
    public RoleProbeMarker clone() {
        if (!isArmed()) return new RoleProbeMarker();
        return new RoleProbeMarker(operationId(), UUID.fromString(world), UUID.fromString(entity), snapshot);
    }
}
