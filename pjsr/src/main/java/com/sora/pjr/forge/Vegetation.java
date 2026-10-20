package com.sora.pjr.forge;

import com.sora.pjr.core.*;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Sparse, seed-controlled vegetation. Every write stays inside the owning chunk. */
final class Vegetation {
  private Vegetation() {}

  static void decorate(WorldGenLevel level, ChunkAccess chunk, WorldEngine engine) {
    int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ();
    SplittableRandom random =
        new SplittableRandom(
            Geo.hash(
                level.getSeed() ^ Geo.key(chunk.getPos().x, chunk.getPos().z) ^ 0x504a52564547L));
    int x = ox + 3 + random.nextInt(10), z = oz + 3 + random.nextInt(10);
    Column centre = engine.column(x, z);
    double altitude = centre.geometry().groundY() * Geo.METRES_Y;
    double probability =
        altitude < 120 ? 0.18 : altitude < 700 ? 0.32 : altitude < 1700 ? 0.42 : 0.12;
    if (random.nextDouble() < probability && suitable(centre) && altitude < 2300)
      tree(level, engine, x, z, random, altitude > 850);
    for (int i = 0; i < 18; i++) {
      x = ox + random.nextInt(16);
      z = oz + random.nextInt(16);
      Column c = engine.column(x, z);
      if (!suitable(c)) continue;
      BlockPos pos = new BlockPos(x, c.geometry().groundY() + 1, z);
      BlockState plant =
          random.nextDouble() < 0.05
              ? flower(x,z,random).defaultBlockState()
              : Blocks.GRASS.defaultBlockState();
      if (level.isEmptyBlock(pos) && plant.canSurvive(level, pos))
        level.setBlock(pos, plant, Block.UPDATE_CLIENTS);
    }
  }

  private static Block flower(int x, int z, SplittableRandom random) {
    // Small coherent patches with some local variety; all are ordinary single-block flowers.
    int patch = (int)(Geo.noise(Math.floorDiv(x,48),Math.floorDiv(z,48),0x464c4f574552L)*FLOWERS.length);
    return FLOWERS[random.nextDouble()<0.7 ? patch : random.nextInt(FLOWERS.length)];
  }

  private static final Block[] FLOWERS = {
    Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY,
    Blocks.CORNFLOWER, Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.ORANGE_TULIP,
    Blocks.RED_TULIP
  };

  private static boolean suitable(Column c) {
    return !c.geometry().wet()
        && !c.geometry().containment()
        && !c.layers().snow()
        && (c.layers().top() == SurfacePainter.Material.GRASS
            || c.layers().top() == SurfacePainter.Material.PODZOL
            || c.layers().top() == SurfacePainter.Material.COARSE_DIRT);
  }

  private static void tree(
      WorldGenLevel level,
      WorldEngine engine,
      int x,
      int z,
      SplittableRandom random,
      boolean spruce) {
    int y = engine.column(x, z).geometry().groundY() + 1,
        height = (spruce ? 6 : 4) + random.nextInt(3);
    if (y + height + 1 >= Geo.MAX_Y) return;
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++) {
        Column c = engine.column(x + dx, z + dz);
        if (!suitable(c) || Math.abs(c.geometry().groundY() + 1 - y) > 1) return;
        for (int dy = 0; dy <= height + 1; dy++)
          if (!level.isEmptyBlock(new BlockPos(x + dx, y + dy, z + dz))) return;
      }
    BlockState leaves =
        (spruce ? Blocks.SPRUCE_LEAVES : Blocks.OAK_LEAVES)
            .defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, true);
    for (int dy = spruce ? 2 : height - 2; dy <= height; dy++) {
      int radius = dy == height ? 1 : 2;
      for (int dx = -radius; dx <= radius; dx++)
        for (int dz = -radius; dz <= radius; dz++)
          if (Math.abs(dx) + Math.abs(dz) <= radius + 1)
            level.setBlock(new BlockPos(x + dx, y + dy, z + dz), leaves, Block.UPDATE_CLIENTS);
    }
    BlockState trunk = (spruce ? Blocks.SPRUCE_LOG : Blocks.OAK_LOG).defaultBlockState();
    for (int dy = 0; dy < height; dy++)
      level.setBlock(new BlockPos(x, y + dy, z), trunk, Block.UPDATE_CLIENTS);
    level.setBlock(
        new BlockPos(x, y - 1, z), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
  }
}
