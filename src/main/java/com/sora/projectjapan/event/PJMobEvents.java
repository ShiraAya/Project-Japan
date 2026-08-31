package com.sora.projectjapan.event;

import com.sora.projectjapan.worldgen.PJChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Restricts only ordinary natural spawning in Project Japan saves and removes
 * newly spawned wandering traders. Other explicit spawn paths remain usable.
 */
public final class PJMobEvents {
    private PJMobEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!isProjectJapanWorld(event.getLevel().getLevel())) return;

        MobSpawnType spawnType = event.getSpawnType();
        if (spawnType == MobSpawnType.NATURAL
                || spawnType == MobSpawnType.CHUNK_GENERATION) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof WanderingTrader)) return;
        if (isProjectJapanWorld(level)) {
            event.setCanceled(true);
        }
    }

    private static boolean isProjectJapanWorld(ServerLevel level) {
        return level.getServer().overworld().getChunkSource().getGenerator()
                instanceof PJChunkGenerator;
    }
}
