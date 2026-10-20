package com.sora.pjr.roads;
import net.minecraft.server.level.*;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.world.*;
import java.util.*;
/** Test-only access to SR's real splitter planner/transaction; no SR implementation copied. */
public final class PjsrNativeEditProbe {
 public static UUID split(ServerLevel level,RoadRecord road) throws Exception {
  var method=RoadSplitter.class.getDeclaredMethod("plan",ServerLevel.class,ServerPlayer.class,RoadRecord.class,double.class,RoadSlicing.Connection.class);method.setAccessible(true);
  var plan=method.invoke(null,level,null,road,road.mesh().length()/2,RoadSlicing.Connection.CONTINUOUS);
  var commit=RoadSplitter.class.getDeclaredMethod("commit",ServerLevel.class,ServerPlayer.class,plan.getClass());commit.setAccessible(true);commit.invoke(null,level,null,plan);
  var pairMethod=plan.getClass().getDeclaredMethod("pair");pairMethod.setAccessible(true);
  var pair=(RoadSplitGeometry.Pair)pairMethod.invoke(plan);return pair.second().id();
 }
 public static void repaint(ServerLevel level,RoadRecord road){
  var tag=new net.minecraft.nbt.CompoundTag();tag.putUUID("Id",road.id());
  tag.putInt("Signature",GantryTool.signature(road));tag.putBoolean("HideArrows",false);
  RoadData.get(level).editLaneLine(level,null,tag);
 }
}
