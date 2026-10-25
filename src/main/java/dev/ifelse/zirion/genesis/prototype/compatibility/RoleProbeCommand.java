package dev.ifelse.zirion.genesis.prototype.compatibility;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Console-only experiment on one explicitly configured frozen fixture; never gameplay adoption. */
public final class RoleProbeCommand extends AbstractWorldCommand {
    private final RequiredArg<String> action = withRequiredArg("action", "server.genesis.roleProbe.action", ArgTypes.STRING);
    private final OptionalArg<Integer> sectionX = withOptionalArg("sectionX", "server.genesis.roleProbe.sectionX", ArgTypes.INTEGER);
    private final OptionalArg<Integer> sectionY = withOptionalArg("sectionY", "server.genesis.roleProbe.sectionY", ArgTypes.INTEGER);
    private final OptionalArg<Integer> sectionZ = withOptionalArg("sectionZ", "server.genesis.roleProbe.sectionZ", ArgTypes.INTEGER);
    private final OptionalArg<String> afterLoad = withOptionalArg("then", "server.genesis.roleProbe.then", ArgTypes.STRING);
    private final ComponentType<EntityStore, RoleProbeMarker> markerType;
    private final HytaleLogger logger;
    private final UUID worldId;
    private final UUID entityId;
    private final RoleProbeProfile profile;

    public RoleProbeCommand(ComponentType<EntityStore, RoleProbeMarker> markerType, HytaleLogger logger,
                            UUID worldId, UUID entityId, RoleProbeProfile profile) {
        super(profile.commandName(), "server.genesis.roleProbe.description");
        this.markerType = markerType;
        this.logger = logger;
        this.worldId = worldId;
        this.entityId = entityId;
        this.profile = profile;
        requirePermission("genesis.prototype.compatibility.role");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
        if (context.sender() != ConsoleSender.INSTANCE) { reply(context, "console_only"); return; }
        if (!store.isInThread() || !worldId.equals(world.getWorldConfig().getUuid())) {
            reply(context, "wrong_world_or_thread"); return;
        }
        if (action.get(context).equals("load")) {
            loadSection(context, world);
            return;
        }
        executeFixture(context, world, store, action.get(context));
    }

    private void executeFixture(CommandContext context, World world, Store<EntityStore> store, String command) {
        if (!store.isInThread() || !worldId.equals(world.getWorldConfig().getUuid())) {
            reply(context, "wrong_world_or_thread"); return;
        }
        Ref<EntityStore> ref = world.getEntityStore().getRefFromUUID(entityId);
        if (ref == null || !ref.isValid()) { reply(context, "fixture_not_loaded"); return; }
        var identity = store.getComponent(ref, UUIDComponent.getComponentType());
        var npc = store.getComponent(ref, NPCEntity.getComponentType());
        var dirty = store.getComponent(ref, Dirty.getComponentType());
        if (command.equals("admission") && identity != null && entityId.equals(identity.getUuid()) && npc != null) {
            var reasons = NpcAdmissionProbe.inspect(world, store, ref, true);
            logger.atInfo().log("Genesis admission probe: result=denied_npc_preflight world=%s entity=%s reasons=%s storeThread=%s thread=%s",
                    worldId, entityId, reasons, store.isInThread(), Thread.currentThread().getName());
            reply(context, "admission_denied_npc_preflight");
            return;
        }
        if (identity == null || !entityId.equals(identity.getUuid()) || npc == null || npc.getRole() == null
                || npc.isReserved() || dirty == null || store.getComponent(ref, Frozen.getComponentType()) == null) {
            reply(context, "fixture_guard_failed"); return;
        }
        try {
            run(context, store, ref, npc, dirty, command);
        } catch (RuntimeException failure) {
            logger.atWarning().log("Genesis role probe: result=error world=%s entity=%s type=%s",
                    worldId, entityId, failure.getClass().getSimpleName());
            reply(context, "error_evidence_retained");
        }
    }

    private void run(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, NPCEntity npc, Dirty dirty, String command) {
        var marker = store.getComponent(ref, markerType);
        if (command.equals("status")) {
            if (marker != null && !marker.matches(worldId, entityId)) { reply(context, "marker_scope_mismatch"); return; }
            report(store, ref, npc, marker, "status");
            reply(context, marker == null ? "unarmed" : "armed");
            return;
        }
        if (npc.getRole().isRoleChangeRequested()) { reply(context, "role_change_pending"); return; }
        if (command.equals("begin")) {
            if (marker != null || !profile.sourceRole().equals(npc.getRoleName()) || !hasExpectedModel(store, ref)) {
                reply(context, "baseline_guard_failed"); return;
            }
            roleIndex(profile.sourceRole());
            roleIndex(profile.probeRole());
            var snapshot = serialize(store, ref);
            marker = new RoleProbeMarker(UUID.randomUUID(), worldId, entityId, snapshot.toJson());
            store.putComponent(ref, markerType, marker);
            dirty.markDirty();
            report(store, ref, npc, marker, "begun");
            reply(context, "begun");
            return;
        }
        if (marker == null || !marker.matches(worldId, entityId)) { reply(context, "marker_scope_mismatch"); return; }
        if (marker.baseline().profile() != profile) { reply(context, "marker_profile_mismatch"); return; }
        if (command.equals("verify")) {
            if (!hasExpectedModel(store, ref)) { reply(context, "transition_guard_failed"); return; }
            var changed = marker.baseline().differencesInRole(serialize(store, ref), npc.getRoleName());
            if (!changed.isEmpty()) {
                logger.atWarning().log("Genesis role probe: result=snapshot_mismatch entity=%s components=%s", entityId, changed);
                reply(context, "snapshot_mismatch_evidence_retained"); return;
            }
            report(store, ref, npc, marker, "verified_snapshot_equal_in_role");
            reply(context, "verified");
            return;
        }
        if (command.equals("enter") || command.equals("return")) {
            String expected = command.equals("enter") ? profile.sourceRole() : profile.probeRole();
            String destination = command.equals("enter") ? profile.probeRole() : profile.sourceRole();
            if (!expected.equals(npc.getRoleName()) || !hasExpectedModel(store, ref)) {
                reply(context, "transition_guard_failed"); return;
            }
            report(store, ref, npc, marker, "queue_" + destination);
            // Keep the existing model/scale/attachments. Each alias inherits its own native source role.
            RoleChangeSystem.requestRoleChange(ref, npc.getRole(), roleIndex(destination), false, store);
            reply(context, "queued");
            return;
        }
        if (command.equals("finish")) {
            if (!profile.sourceRole().equals(npc.getRoleName()) || !hasExpectedModel(store, ref)) {
                reply(context, "restore_role_first"); return;
            }
            var changed = marker.baseline().differences(serialize(store, ref));
            if (!changed.isEmpty()) {
                logger.atWarning().log("Genesis role probe: result=snapshot_mismatch entity=%s components=%s", entityId, changed);
                reply(context, "snapshot_mismatch_evidence_retained"); return;
            }
            store.removeComponent(ref, markerType);
            dirty.markDirty();
            report(store, ref, npc, marker, "finished_snapshot_equal_marker_removed");
            reply(context, "finished");
            return;
        }
        reply(context, "unknown_action");
    }

    private void loadSection(CommandContext context, World world) {
        Integer x = sectionX.get(context), y = sectionY.get(context), z = sectionZ.get(context);
        if (x == null || y == null || z == null) { reply(context, "section_coordinates_required"); return; }
        String next = afterLoad.get(context) == null ? "status" : afterLoad.get(context);
        if (!java.util.Set.of("status", "begin", "enter", "return", "verify", "finish", "admission").contains(next)) {
            reply(context, "unsupported_after_load_action"); return;
        }
        // Load an existing section through the native API; never generate terrain or force an unload.
        // The requested action uses a fresh UUID lookup in the SAME world callback before natural unload.
        // Never join a future on tick. Timeout does not cancel the underlying native load.
        world.getChunkStore().getChunkSectionReferenceAsync(x, y, z, GetChunkFlags.NO_GENERATE | GetChunkFlags.SET_TICKING)
                .copy().orTimeout(15, TimeUnit.SECONDS).whenCompleteAsync((section, failure) -> {
                    if (failure != null || section == null || !section.isValid()) {
                        reply(context, "section_load_failed"); return;
                    }
                    logger.atInfo().log("Genesis role probe: result=section_loaded world=%s entity=%s section=%s,%s,%s storeThread=%s thread=%s",
                            worldId, entityId, x, y, z, world.getEntityStore().getStore().isInThread(), Thread.currentThread().getName());
                    executeFixture(context, world, world.getEntityStore().getStore(), next);
                }, world);
        reply(context, "section_load_queued");
    }

    private static int roleIndex(String id) {
        int index = NPCPlugin.get().getIndex(id);
        if (index < 0 || !id.equals(NPCPlugin.get().getName(index))) throw new IllegalStateException("Role unavailable");
        return index;
    }

    private boolean hasExpectedModel(Store<EntityStore> store, Ref<EntityStore> ref) {
        var persistent = store.getComponent(ref, PersistentModel.getComponentType());
        var rendered = store.getComponent(ref, ModelComponent.getComponentType());
        return persistent != null && persistent.getModelReference() != null
                && profile.modelId().equals(persistent.getModelReference().getModelAssetId())
                && rendered != null && rendered.getModel() != null && profile.modelId().equals(rendered.getModel().getModelAssetId());
    }

    private static BsonDocument serialize(Store<EntityStore> store, Ref<EntityStore> ref) {
        return EntityStore.REGISTRY.serialize(store.copyEntity(ref));
    }

    private void report(Store<EntityStore> store, Ref<EntityStore> ref, NPCEntity npc, RoleProbeMarker marker, String result) {
        logger.atInfo().log("Genesis role probe: result=%s world=%s entity=%s operation=%s role=%s ref=%s storeThread=%s thread=%s",
                result, worldId, entityId, marker == null ? "<none>" : marker.operationId(), npc.getRoleName(), ref,
                store.isInThread(), Thread.currentThread().getName());
    }

    private static void reply(CommandContext context, String result) {
        context.sendMessage(Message.translation("server.genesis.roleProbe.result").param("result", result));
    }
}
