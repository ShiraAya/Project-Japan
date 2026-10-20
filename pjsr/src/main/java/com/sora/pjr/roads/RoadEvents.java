package com.sora.pjr.roads;

import com.sora.pjr.forge.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=PjrMod.ID)
public final class RoadEvents {
  private static boolean enabled(ServerLevel level){return level.getChunkSource().getGenerator() instanceof PjrChunkGenerator g&&g.roadsEnabled();}
  @SubscribeEvent(priority=EventPriority.HIGHEST) public static void levelLoad(LevelEvent.Load event){if(event.getLevel() instanceof ServerLevel level&&enabled(level))RoadWorldState.initialize(level);}
  @SubscribeEvent public static void levelUnload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level)RoadWorldState.unload(level);}
  @SubscribeEvent public static void chunkLoad(ChunkEvent.Load event){if(event.getLevel() instanceof ServerLevel level&&enabled(level)){var p=event.getChunk().getPos();RoadWorldState.get(level).markReady(p.x,p.z);}}
  @SubscribeEvent public static void chunkRead(ChunkDataEvent.Load event){
    if(event.getLevel() instanceof ServerLevel level&&enabled(level)&&event.getData().getBoolean("PJSRGeneratedV1")){var p=event.getChunk().getPos();RoadWorldState.get(level).markGenerated(p.x,p.z);}
  }
  @SubscribeEvent public static void chunkSave(ChunkDataEvent.Save event){
    if(event.getLevel() instanceof ServerLevel level&&enabled(level)){var p=event.getChunk().getPos();if(RoadWorldState.get(level).generated.contains(com.sora.pjr.core.Geo.key(p.x,p.z)))event.getData().putBoolean("PJSRGeneratedV1",true);}
  }
  @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event){if(event.phase==TickEvent.Phase.END&&event.level instanceof ServerLevel level&&enabled(level))RoadWorldState.get(level).drain();}
}
