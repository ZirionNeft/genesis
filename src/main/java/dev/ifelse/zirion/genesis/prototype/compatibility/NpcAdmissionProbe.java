package dev.ifelse.zirion.genesis.prototype.compatibility;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ifelse.zirion.genesis.core.control.AdoptionEvidence.*;
import dev.ifelse.zirion.genesis.core.control.WildAdoptionPolicy;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.wild.WildCandidateRecord.NpcIdentity;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only collection. Role/Frozen/reservation/probe marker never establish ownership or vanilla provenance. */
public final class NpcAdmissionProbe {
    private NpcAdmissionProbe() { }

    public static Set<WildAdoptionPolicy.Reason> inspect(World world, Store<EntityStore> store, Ref<EntityStore> ref,
                                                       boolean diagnosticTarget) {
        if (!store.isInThread()) throw new IllegalStateException("World thread required");
        var identity = store.getComponent(ref, UUIDComponent.getComponentType());
        var npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (identity == null || npc == null) throw new IllegalArgumentException("Existing NPC identity required");
        var key = new NpcIdentity(ContentId.parse("genesis:hytale_entity_store"), world.getWorldConfig().getUuid(), identity.getUuid());
        boolean lookup = ref.equals(world.getEntityStore().getRefFromUUID(identity.getUuid()));
        var facts = new NpcFacts(key, Optional.empty(), Origin.UNKNOWN, new Ownership(OwnerState.UNKNOWN, Optional.empty()),
                Gate.UNKNOWN, List.of(), lookup ? Gate.PASS : Gate.BLOCKED,
                npc.isReserved() ? Gate.BLOCKED : Gate.PASS, Gate.UNKNOWN, Gate.UNKNOWN, Gate.UNKNOWN, Gate.UNKNOWN,
                diagnosticTarget);
        return new WildAdoptionPolicy().inspect(facts);
    }
}
