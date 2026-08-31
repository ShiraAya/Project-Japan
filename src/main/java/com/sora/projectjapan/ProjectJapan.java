package com.sora.projectjapan;

import com.mojang.logging.LogUtils;
import com.sora.projectjapan.event.PJMobEvents;
import com.sora.projectjapan.event.PJServerEvents;
import com.sora.projectjapan.registry.PJWorldgenRegistries;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ProjectJapan.MOD_ID)
public final class ProjectJapan {
    public static final String MOD_ID = "projectjapan";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ProjectJapan(FMLJavaModLoadingContext loadingContext) {
        IEventBus modBus = loadingContext.getModEventBus();
        PJWorldgenRegistries.register(modBus);
        MinecraftForge.EVENT_BUS.register(PJServerEvents.class);
        MinecraftForge.EVENT_BUS.register(PJMobEvents.class);
        LOGGER.info("Project Japan is loading");
    }
}
