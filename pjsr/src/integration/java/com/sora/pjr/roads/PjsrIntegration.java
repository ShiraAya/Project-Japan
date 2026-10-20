package com.sora.pjr.roads;
import com.sora.pjr.forge.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.SplineRoads;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Actual Forge dedicated-server lifecycle validation. Not included in the production JAR. */
@Mod.EventBusSubscriber(modid=PjrMod.ID)
public final class PjsrIntegration {
 static int checks;
 static void diff(Tag a,Tag b,String path){
  if(Objects.equals(a,b))return;
  if(a instanceof CompoundTag x&&b instanceof CompoundTag y){var keys=new TreeSet<>(x.getAllKeys());keys.addAll(y.getAllKeys());for(String k:keys)diff(x.get(k),y.get(k),path+"."+k);}
  else {String left=String.valueOf(a),right=String.valueOf(b);System.out.println("PJSR_RESTART_DIFF "+path+" "+left.substring(0,Math.min(300,left.length()))+" -> "+right.substring(0,Math.min(300,right.length())));}
 }
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 @SubscribeEvent public static void started(ServerStartedEvent event){
  if(!Boolean.getBoolean("pjsr.integration"))return;
  var server=event.getServer();server.execute(()->{
   var expectedPath=Path.of("pjsr-integration-expected.nbt");boolean restart=Files.exists(expectedPath);
   var report=Path.of(restart?"pjsr-integration-restart-result.txt":"pjsr-integration-result.txt");
   try{
    Files.writeString(report,"RUNNING\n");
    var level=server.overworld();check(level.getChunkSource().getGenerator() instanceof PjrChunkGenerator,"PJSR generator");
    var generator=(PjrChunkGenerator)level.getChunkSource().getGenerator();check(generator.roadsEnabled(),"snapshot codec enabled");
    var bridge=generator.roads();var state=RoadWorldState.get(level);var data=RoadData.get(level);
    var keys=new TreeSet<Long>();for(var unit:bridge.plan.units)for(var s:unit.samples())for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)keys.add(ChunkPos.asLong(((int)Math.floor(s.x())>>4)+dx,((int)Math.floor(s.z())>>4)+dz));
    var order=new ArrayList<>(keys);Collections.shuffle(order,new Random(71));
    for(long key:order){var p=new ChunkPos(key);level.getChunk(p.x,p.z);}
    for(int i=0;i<order.size();i++)state.drain();
    if(restart){
      var expected=NbtIo.readCompressed(expectedPath.toFile());
      check(data.index.roads.size()==expected.size(),"restart record count");
      for(String key:expected.getAllKeys()){
        var built=data.index.roads.get(UUID.fromString(key));check(built!=null,"restart native identity");
        diff(expected.getCompound(key),built.record.save(),key);
        check(built.record.save().equals(expected.getCompound(key)),"restart exact edited record "+key);
      }
      check(state.suppressed.contains(bridge.plan.units.get(5).id()),"restart tombstone");
      check(!data.index.roads.containsKey(bridge.plan.units.get(5).id()),"restart delete preserved");
      Files.writeString(report,"PASS checks="+checks+" nativeRecords="+data.index.roads.size()+" chunks="+order.size()+"\n");return;
    }
    check(data.index.roads.size()==bridge.plan.units.size(),"all units registered once");
    for(int i=1;i<bridge.plan.units.size();i++){
      var left=data.index.roads.get(bridge.plan.units.get(i-1).id()).mesh;
      var right=data.index.roads.get(bridge.plan.units.get(i).id()).mesh;
      var a=left.samples().get(left.samples().size()-1);var b=right.samples().get(0);
      check(a.center().distance(b.center())<1e-8,"native seam position");
      check(a.left().distance(b.left())<1e-8,"native seam tangent");
      check(Math.abs(a.halfWidth()-b.halfWidth())<1e-8,"native seam width");
      check(Math.abs(left.settings().options().ends().paintPhase()+left.length()-right.settings().options().ends().paintPhase())<1e-6,"paint phase seam");
    }
    int colliders=0;
    for(var unit:bridge.plan.units){
     var built=data.index.roads.get(unit.id());check(built!=null,"native SR record");check(built.record.settings().options().leftTraffic(),"left traffic");
     var restored=RoadRecord.load(built.record.save());if(!restored.save().equals(built.record.save())){
       var before=built.record.save();var after=restored.save();
       for(String key:before.getAllKeys())if(!Objects.equals(before.get(key),after.get(key)))System.out.println("PJSR_NBT_DIFF "+key+" before="+before.get(key)+" after="+after.get(key));
       throw new AssertionError("SR NBT roundtrip");
     }
     for(var sample:unit.samples()){
      BlockPos pos=BlockPos.containing(sample.x(),sample.y()-.15,sample.z());
      if(level.getBlockState(pos).is(SplineRoads.COLLIDER.get())){colliders++;check(!data.index.at(pos.asLong()).isEmpty(),"collider native ownership");}
     }
    }
    check(colliders>100,"physical SR road colliders");
    var editId=bridge.plan.units.get(1).id();PjsrNativeEditProbe.repaint(level,data.index.roads.get(editId).record);
    check(!data.index.roads.get(editId).record.settings().options().hideArrows(),"native paint edit");
    check(state.suppressed.contains(editId),"edit suppression");
    var splitId=bridge.plan.units.get(9).id();var splitOriginal=data.index.roads.get(splitId).record;
    var second=PjsrNativeEditProbe.split(level,splitOriginal);state.drain();
    check(data.index.roads.containsKey(second),"native splitter second half");
    check(state.suppressed.contains(splitId),"split base suppressed");
    check(data.index.roads.get(splitId).mesh.length()<splitOriginal.mesh().length(),"split first half");
    // Native deletion must trigger durable suppression, including a subsequent reload of our state.
    var target=bridge.plan.units.get(5);data.remove(level,null,target.id());check(!data.index.roads.containsKey(target.id()),"native SR delete");check(state.suppressed.contains(target.id()),"delete suppression immediate");
    var restoredState=RoadWorldState.load(state.save(new CompoundTag()));check(restoredState.suppressed.contains(target.id()),"suppression NBT roundtrip");
    for(var s:target.samples())state.markReady((int)Math.floor(s.x())>>4,(int)Math.floor(s.z())>>4);
    for(int i=0;i<20;i++)state.drain();check(!data.index.roads.containsKey(target.id()),"delete not resurrected");
    server.saveEverything(false,true,true);
    var expected=new CompoundTag();for(var entry:data.index.roads.entrySet())expected.put(entry.getKey().toString(),entry.getValue().record.save());
    NbtIo.writeCompressed(expected,expectedPath.toFile());
    Files.writeString(report,"PASS checks="+checks+" units="+bridge.plan.units.size()+" chunks="+order.size()+" colliderSamples="+colliders+"\n");
   }catch(Throwable t){t.printStackTrace();try{Files.writeString(report,"FAIL "+t+"\n");}catch(Exception ignored){}}
   finally{server.halt(false);}
  });
 }
}
