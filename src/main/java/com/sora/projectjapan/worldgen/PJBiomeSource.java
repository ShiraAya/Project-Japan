package com.sora.projectjapan.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public final class PJBiomeSource extends BiomeSource {
    public static final Codec<PJBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Biome.CODEC.fieldOf("land_biome").forGetter(source -> source.landBiome),
            Biome.CODEC.fieldOf("ocean_biome").forGetter(source -> source.oceanBiome),
            Biome.CODEC.fieldOf("river_biome").forGetter(source -> source.riverBiome)
    ).apply(instance, PJBiomeSource::new));

    private final Holder<Biome> landBiome;
    private final Holder<Biome> oceanBiome;
    private final Holder<Biome> riverBiome;

    public PJBiomeSource(Holder<Biome> landBiome, Holder<Biome> oceanBiome,
                         Holder<Biome> riverBiome) {
        this.landBiome = landBiome;
        this.oceanBiome = oceanBiome;
        this.riverBiome = riverBiome;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(landBiome, oceanBiome, riverBiome);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ,
                                       Climate.Sampler sampler) {
        int blockX = QuartPos.toBlock(quartX);
        int blockZ = QuartPos.toBlock(quartZ);
        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(blockX, blockZ);
        LakeData.LakeSample lake = LakeData.sampleWorld(blockX, blockZ);
        if (lake.rendersWaterOn(terrain)) return riverBiome;
        // The anti-aliased national land mask can cross a sea-level lagoon. Its generated
        // spit remains a land biome so the world map shows a real separating shore, not two water
        // colours occupying the same place.
        if (lake.coastalBarrier(terrain)) return landBiome;
        if (terrain.land() && RiverData.isRiverWaterWorld(blockX, blockZ)) return riverBiome;
        if (!terrain.land()) return oceanBiome;
        return landBiome;
    }
}
