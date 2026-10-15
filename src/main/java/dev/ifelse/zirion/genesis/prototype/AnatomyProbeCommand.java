package dev.ifelse.zirion.genesis.prototype;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

/** Opt-in development command for the stage 0 visual experiment, not a gameplay API. */
public final class AnatomyProbeCommand extends AbstractPlayerCommand {
    private static final String ROLE = "Genesis_AnatomyProbe";
    private static final Set<String> PARTS = Set.of("wolf", "fox");
    private static final Set<String> COATS = Set.of("grey", "orange");

    private final RequiredArg<String> head = withRequiredArg(
            "head", "server.genesis.probe.head", ArgTypes.STRING);
    private final RequiredArg<String> tail = withRequiredArg(
            "tail", "server.genesis.probe.tail", ArgTypes.STRING);
    private final RequiredArg<String> coat = withRequiredArg(
            "coat", "server.genesis.probe.coat", ArgTypes.STRING);
    private final RequiredArg<Float> scale = withRequiredArg(
            "scale", "server.genesis.probe.scale", ArgTypes.FLOAT);
    private final FlagArg whole = withFlagArg("whole", "server.genesis.probe.whole");
    private final FlagArg walk = withFlagArg("walk", "server.genesis.probe.walk");
    private final FlagArg move = withFlagArg("move", "server.genesis.probe.move");
    private final FlagArg ui = withFlagArg("ui", "server.genesis.probe.ui");

    public AnatomyProbeCommand() {
        super("genesisprobe", "server.genesis.probe.description");
        requirePermission("genesis.prototype.anatomy");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> player, @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        String headId = head.get(context);
        String tailId = tail.get(context);
        String coatId = coat.get(context);
        float modelScale = scale.get(context);
        boolean useWhole = whole.get(context);
        if (!PARTS.contains(headId) || !PARTS.contains(tailId) || !COATS.contains(coatId)
                || (modelScale != 1.0f && modelScale != 1.25f)) {
            context.sendMessage(Message.translation("server.genesis.probe.invalid"));
            return;
        }
        // A single fixed hybrid is the control specimen for comparing the two rendering paths.
        if (useWhole && (!headId.equals("wolf") || !tailId.equals("fox"))) {
            context.sendMessage(Message.translation("server.genesis.probe.wholeCombination"));
            return;
        }
        Ref<EntityStore> target = TargetUtil.getTargetEntity(player, 8.0f, store);
        if (target == null || !target.isValid()) {
            context.sendMessage(Message.translation("server.genesis.probe.target"));
            return;
        }
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        UUIDComponent identity = store.getComponent(target, UUIDComponent.getComponentType());
        if (npc == null || !ROLE.equals(npc.getRoleName()) || identity == null
                || npc.getRole() == null || store.getComponent(target, ModelComponent.getComponentType()) == null) {
            context.sendMessage(Message.translation("server.genesis.probe.target"));
            return;
        }
        String assetId = ROLE + (useWhole ? "_Whole" : "") + (coatId.equals("orange") ? "_Orange" : "");
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(assetId);
        if (asset == null) {
            context.sendMessage(Message.translation("server.genesis.probe.missingAsset").param("id", assetId));
            return;
        }
        Model model = Model.createScaledModel(asset, modelScale,
                useWhole ? Map.of() : Map.of("head", headId, "tail", tailId));
        // Persist the asset ID, scale and selected attachment IDs, not an arbitrary transient Model.
        store.putComponent(target, PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
        store.putComponent(target, ModelComponent.getComponentType(), new ModelComponent(model));
        npc.getRole().updateMotionControllers(target, model, model.getBoundingBox(), store);
        boolean moving = move.get(context);
        StateSupport.get(target, store).setState(target, moving ? "Move" : "Idle", null, store);
        String animation = moving ? "Movement" : walk.get(context) ? "Walk" : "Idle";
        if (moving) {
            AnimationUtils.stopAnimation(target, AnimationSlot.Action, true, store);
        } else {
            AnimationUtils.playAnimation(target, AnimationSlot.Action, animation, true, store);
        }
        Message summary = Message.translation("server.genesis.probe.applied")
                .param("id", identity.getUuid().toString()).param("head", headId).param("tail", tailId)
                .param("coat", coatId).param("scale", Float.toString(modelScale))
                .param("model", assetId).param("animation", animation);
        context.sendMessage(summary);
        Player playerComponent = store.getComponent(player, Player.getComponentType());
        if (ui.get(context) && playerComponent != null) {
            playerComponent.getPageManager().openCustomPage(player, store, new AnatomyProbePage(playerRef, summary));
        }
    }
}
