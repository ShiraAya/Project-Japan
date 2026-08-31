package com.sora.projectjapan.registry;

import com.mojang.serialization.Codec;
import com.sora.projectjapan.ProjectJapan;
import com.sora.projectjapan.worldgen.PJBiomeSource;
import com.sora.projectjapan.worldgen.PJChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class PJWorldgenRegistries {
    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, ProjectJapan.MOD_ID);
    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, ProjectJapan.MOD_ID);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> PROJECT_JAPAN_GENERATOR =
            CHUNK_GENERATORS.register("project_japan", () -> PJChunkGenerator.CODEC);
    public static final RegistryObject<Codec<? extends BiomeSource>> PROJECT_JAPAN_BIOME_SOURCE =
            BIOME_SOURCES.register("project_japan", () -> PJBiomeSource.CODEC);

    private PJWorldgenRegistries() {}

    public static void register(IEventBus bus) {
        CHUNK_GENERATORS.register(bus);
        BIOME_SOURCES.register(bus);
    }
}
