package dev.ifelse.zirion.genesis.prototype.compatibility;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Opt-in console preparation of NEW frozen vanilla wolf fixtures, never adoption or recovery. */
public final class RoleFixtureCommand extends AbstractWorldCommand {
    private final RequiredArg<String> profileArg = withRequiredArg("profile", "server.genesis.fixture.profile", ArgTypes.STRING);
    private final RequiredArg<Double> xArg = withRequiredArg("x", "server.genesis.fixture.x", ArgTypes.DOUBLE);
    private final RequiredArg<Double> yArg = withRequiredArg("y", "server.genesis.fixture.y", ArgTypes.DOUBLE);
    private final RequiredArg<Double> zArg = withRequiredArg("z", "server.genesis.fixture.z", ArgTypes.DOUBLE);
    private final Set<RoleProbeProfile> attempted = EnumSet.of(RoleProbeProfile.FOX);
    private final HytaleLogger logger;
    private final UUID worldId;

    public RoleFixtureCommand(HytaleLogger logger, UUID worldId, Set<RoleProbeProfile> configured) {
        super("genesisrolefixture", "server.genesis.fixture.description");
        this.logger = logger;
        this.worldId = worldId;
        attempted.addAll(configured);
        requirePermission("genesis.prototype.compatibility.fixture.create");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
        if (context.sender() != ConsoleSender.INSTANCE || !store.isInThread() || !worldId.equals(world.getWorldConfig().getUuid())) {
            reply(context, "wrong_sender_world_or_thread"); return;
        }
        final RoleProbeProfile profile;
        try { profile = RoleProbeProfile.fromKey(profileArg.get(context)); }
        catch (IllegalArgumentException invalid) { reply(context, "unknown_profile"); return; }
        var position = new Vector3d(xArg.get(context), yArg.get(context), zArg.get(context));
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            reply(context, "invalid_position"); return;
        }
        // Reserve the entire attempt BEFORE asynchronous loading. No implicit retry, even on failure.
        if (!attempted.add(profile)) { reply(context, "configured_or_already_attempted"); return; }
        world.getChunkStore().getChunkSectionReferenceAsync(ChunkUtil.chunkCoordinate(position.x),
                ChunkUtil.chunkCoordinate(position.y), ChunkUtil.chunkCoordinate(position.z),
                GetChunkFlags.NO_GENERATE | GetChunkFlags.SET_TICKING).copy().orTimeout(15, TimeUnit.SECONDS)
                .whenCompleteAsync((section, failure) -> {
                    if (failure != null || section == null || !section.isValid()) {
                        reply(context, "section_load_failed_no_retry"); return;
                    }
                    try {
                        var plugin = NPCPlugin.get();
                        int source = plugin.getIndex(profile.sourceRole()), alias = plugin.getIndex(profile.probeRole());
                        if (source < 0 || alias < 0 || !profile.sourceRole().equals(plugin.getName(source))
                                || !profile.probeRole().equals(plugin.getName(alias))) {
                            reply(context, "role_unavailable_no_retry"); return;
                        }
                        var asset = ModelAsset.getAssetMap().getAsset(profile.modelId());
                        if (asset == null) { reply(context, "model_unavailable_no_retry"); return; }
                        // Native spawn allocates a fresh UUID. Frozen is present on the Holder before SPAWN.
                        var created = plugin.spawnEntity(store, source, position, null, Model.createRandomScaleModel(asset),
                                (npc, holder, entityStore) -> holder.ensureComponent(Frozen.getComponentType()), null);
                        if (created == null || created.left() == null || !created.left().isValid()) {
                            reply(context, "spawn_failed_no_retry"); return;
                        }
                        var identity = store.getComponent(created.left(), UUIDComponent.getComponentType());
                        if (identity == null) { reply(context, "spawn_identity_missing_no_retry"); return; }
                        logger.atInfo().log("Genesis fixture: result=created profile=%s world=%s entity=%s role=%s frozen=%s storeThread=%s thread=%s",
                                profile.key(), worldId, identity.getUuid(), created.right().getRoleName(),
                                store.getComponent(created.left(), Frozen.getComponentType()) != null,
                                store.isInThread(), Thread.currentThread().getName());
                        reply(context, "created_record_uuid_then_restart_with_creation_disabled");
                    } catch (RuntimeException error) {
                        logger.atWarning().log("Genesis fixture: result=error_no_retry profile=%s type=%s", profile.key(), error.getClass().getSimpleName());
                        reply(context, "error_no_retry_inspect_world");
                    }
                }, world);
        reply(context, "preparation_queued");
    }

    private static void reply(CommandContext context, String result) {
        context.sendMessage(Message.translation("server.genesis.fixture.result").param("result", result));
    }
}
