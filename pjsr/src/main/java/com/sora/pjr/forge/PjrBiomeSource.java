package com.sora.pjr.forge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.*;

public final class PjrBiomeSource extends BiomeSource {
  public static final Codec<PjrBiomeSource> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Biome.CODEC.fieldOf("land_biome").forGetter(s -> s.land),
                      Biome.CODEC.fieldOf("ocean_biome").forGetter(s -> s.ocean),
                      Biome.CODEC.fieldOf("river_biome").forGetter(s -> s.river))
                  .apply(i, PjrBiomeSource::new));
  private final Holder<Biome> land, ocean, river;

  public PjrBiomeSource(Holder<Biome> land, Holder<Biome> ocean, Holder<Biome> river) {
    this.land = land;
    this.ocean = ocean;
    this.river = river;
  }

  @Override
  protected Codec<? extends BiomeSource> codec() {
    return CODEC;
  }

  @Override
  protected Stream<Holder<Biome>> collectPossibleBiomes() {
    return Stream.of(land, ocean, river);
  }

  @Override
  public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
    return switch (WorldRuntime.engine()
        .geometry(QuartPos.toBlock(x), QuartPos.toBlock(z))
        .kind()) {
      case OCEAN -> ocean;
      case RIVER, LAKE -> river;
      case DRY -> land;
    };
  }
}
