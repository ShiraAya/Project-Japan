package com.sora.pjr.forge;

import com.sora.pjr.core.SurfacePainter.Material;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockPalette {
  private BlockPalette() {}

  private static final BlockState[] STATES = new BlockState[Material.values().length];

  static {
    for (Material m : Material.values())
      STATES[m.ordinal()] =
          switch (m) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case BEDROCK -> Blocks.BEDROCK.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
            case DIRT -> Blocks.DIRT.defaultBlockState();
            case GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
            case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case PODZOL -> Blocks.PODZOL.defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
            case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
            case CLAY -> Blocks.CLAY.defaultBlockState();
            case SNOW_BLOCK -> Blocks.SNOW_BLOCK.defaultBlockState();
            case SNOW -> Blocks.SNOW.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case FALLING_WATER -> Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
          };
  }

  public static BlockState state(Material material) {
    return STATES[material.ordinal()];
  }
}
