package com.sora.pjr.forge;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;

@Mod(PjrMod.ID)
public final class PjrMod {
  public static final String ID = "pjr";
  private static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS =
      DeferredRegister.create(Registries.CHUNK_GENERATOR, ID);
  private static final DeferredRegister<Codec<? extends BiomeSource>> BIOMES =
      DeferredRegister.create(Registries.BIOME_SOURCE, ID);

  static {
    GENERATORS.register("japan", () -> PjrChunkGenerator.CODEC);
    BIOMES.register("japan", () -> PjrBiomeSource.CODEC);
  }

  public PjrMod(FMLJavaModLoadingContext context) {
    GENERATORS.register(context.getModEventBus());
    BIOMES.register(context.getModEventBus());
  }
}
