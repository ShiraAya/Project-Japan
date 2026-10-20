package com.sora.pjr.forge;

import com.sora.pjr.core.*;
import java.util.EnumSet;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Uses Minecraft's public section setter, which owns block and ticking counters. */
public final class ChunkWriter {
  private ChunkWriter() {}

  public static ChunkAccess fill(ChunkAccess chunk, WorldEngine engine) {
    int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
    for (int z = 0; z < 16; z++)
      for (int x = 0; x < 16; x++) {
        Column c = engine.column(ox + x, oz + z);
        int top = Math.min(chunk.getMaxBuildHeight() - 1, c.surfaceHeight() - 1);
        for (int y = chunk.getMinBuildHeight(); y <= top; y++) {
          LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
          section.setBlockState(x, Math.floorMod(y, 16), z, BlockPalette.state(c.at(y)), false);
        }
      }
    Heightmap.primeHeightmaps(
        chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
    return chunk;
  }
}
