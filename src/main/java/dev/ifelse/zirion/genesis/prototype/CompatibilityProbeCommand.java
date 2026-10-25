package dev.ifelse.zirion.genesis.prototype;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.Objects;

/** Opt-in read-only diagnostics for an explicitly targeted NPC. Never creates a candidate, component or ownership claim. */
public final class CompatibilityProbeCommand extends AbstractPlayerCommand {
    private final HytaleLogger logger;

    public CompatibilityProbeCommand(HytaleLogger logger) {
        super("genesiscompat", "server.genesis.compat.description");
        this.logger = Objects.requireNonNull(logger, "logger");
        requirePermission("genesis.prototype.compatibility");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> player, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        if (!store.isInThread()) {
            logger.atWarning().log("Genesis compatibility: result=wrong_thread");
            context.sendMessage(Message.translation("server.genesis.compat.thread"));
            return;
        }
        Ref<EntityStore> target = TargetUtil.getTargetEntity(player, 8.0f, store);
        if (target == null || !target.isValid()) {
            logger.atInfo().log("Genesis compatibility: result=no_target world=%s", world.getWorldConfig().getUuid());
            context.sendMessage(Message.translation("server.genesis.compat.target"));
            return;
        }
        var npc = store.getComponent(target, NPCEntity.getComponentType());
        var identity = store.getComponent(target, UUIDComponent.getComponentType());
        var worldId = world.getWorldConfig().getUuid();
        if (npc == null || identity == null || identity.getUuid() == null || worldId == null) {
            logger.atInfo().log("Genesis compatibility: result=missing_npc_or_identity world=%s", worldId);
            context.sendMessage(Message.translation("server.genesis.compat.identityMissing"));
            return;
        }
        var persistent = store.getComponent(target, PersistentModel.getComponentType());
        var model = persistent == null ? null : persistent.getModelReference();
        String roleId = npc.getRoleName() == null ? "<none>" : npc.getRoleName();
        String modelId = model == null || model.getModelAssetId() == null ? "<none>" : model.getModelAssetId();
        boolean lookupMatches = target.equals(world.getEntityStore().getRefFromUUID(identity.getUuid()));
        logger.atInfo().log("Genesis compatibility: result=inspected world=%s entity=%s lookup=%s role=%s model=%s reserved=%s storage=%s storeThread=%s thread=%s ref=%s",
                worldId, identity.getUuid(), lookupMatches, roleId, modelId, npc.isReserved(),
                store.getResourceStorage().getClass().getName(), store.isInThread(), Thread.currentThread().getName(), target);
        context.sendMessage(Message.translation("server.genesis.compat.identity")
                .param("world", worldId.toString()).param("entity", identity.getUuid().toString())
                .param("lookup", Boolean.toString(lookupMatches)));
        context.sendMessage(Message.translation("server.genesis.compat.npc")
                .param("role", roleId)
                .param("model", modelId)
                .param("reserved", Boolean.toString(npc.isReserved())));
        context.sendMessage(Message.translation("server.genesis.compat.storage")
                .param("storage", store.getResourceStorage().getClass().getName()));
        context.sendMessage(Message.translation("server.genesis.compat.unverified"));
    }
}
