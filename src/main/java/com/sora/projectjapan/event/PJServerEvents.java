package com.sora.projectjapan.event;

import com.sora.projectjapan.ProjectJapan;
import com.sora.projectjapan.worldgen.PJChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class PJServerEvents {
    private PJServerEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof PJChunkGenerator)) return;
        overworld.getChunk(0, 0);
        int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        BlockPos tokyo = new BlockPos(0, y, 0);
        overworld.setDefaultSpawnPos(tokyo, 0.0F);
        ProjectJapan.LOGGER.info("ProjectJapan spawn fixed to Tokyo at {}", tokyo);
    }
}
