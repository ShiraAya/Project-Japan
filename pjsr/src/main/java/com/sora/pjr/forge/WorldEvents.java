package com.sora.pjr.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PjrMod.ID)
public final class WorldEvents {
  private WorldEvents() {}

  private static boolean isPjr(ServerLevel level) {
    return level.getChunkSource().getGenerator() instanceof PjrChunkGenerator;
  }

  @SubscribeEvent
  public static void createSpawn(LevelEvent.CreateSpawnPosition event) {
    if (!(event.getLevel() instanceof ServerLevel level) || !isPjr(level)) return;
    level.getChunk(0, 0);
    int y = WorldRuntime.engine().column(0, 0).surfaceHeight();
    level.setDefaultSpawnPos(new BlockPos(0, y, 0), 0);
    event.setCanceled(true);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void naturalSpawn(MobSpawnEvent.SpawnPlacementCheck event) {
    if (isPjr(event.getLevel().getLevel())
        && (event.getSpawnType() == MobSpawnType.NATURAL
            || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION))
      event.setResult(Event.Result.DENY);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void trader(EntityJoinLevelEvent event) {
    if (!event.loadedFromDisk()
        && event.getEntity() instanceof WanderingTrader
        && event.getLevel() instanceof ServerLevel level
        && isPjr(level)) event.setCanceled(true);
  }
}
