package com.sora.pjr.roads;
import com.sora.pjr.forge.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid=PjrMod.ID)
public final class RoadCommands {
 @SubscribeEvent public static void register(RegisterCommandsEvent event){
  event.getDispatcher().register(Commands.literal("pjsr")
   .then(Commands.literal("info").executes(c->{
    c.getSource().sendSuccess(()->Component.literal("PJSR 0.1.0-alpha | 国道20号高尾试验段 | X:Y:Z=0.5:0.5:0.5 | © OpenStreetMap contributors, ODbL 1.0 | /pjsr visit"),false);return 1;}))
   .then(Commands.literal("visit").requires(s->s.hasPermission(2)).executes(c->{
    if(!(c.getSource().getLevel().getChunkSource().getGenerator() instanceof PjrChunkGenerator g)||!g.roadsEnabled())throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("请新建世界并选择 PJSR 日本道路版预设")).create();
    var p=g.roads().plan.units.get(4).samples().get(0);var player=c.getSource().getPlayerOrException();player.teleportTo(c.getSource().getLevel(),p.x(),p.y()+2,p.z(),0,0);return 1;
   })));
 }
}
