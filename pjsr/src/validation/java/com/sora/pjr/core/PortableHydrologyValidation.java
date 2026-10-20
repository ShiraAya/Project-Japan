package com.sora.pjr.core;
import com.sora.pjr.forge.WorldRuntime;
import java.util.*;
import java.util.concurrent.*;
public final class PortableHydrologyValidation {
 public static void main(String[] args)throws Exception {
  var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
  List<Future<WorldEngine>> futures=new ArrayList<>();
  for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();return WorldRuntime.engine();}));
  long start=System.nanoTime();gate.countDown();var engine=futures.get(0).get();
  for(var future:futures)if(future.get()!=engine)throw new AssertionError("Multiple world models");pool.shutdown();
  var baked=PrecomputedHydrology.load();long profileNodes=0;int signatures=0;
  for(var river:engine.hydrology().data().rivers()) {
   var cached=baked.rivers.get(river.name());
   if(!cached.shape().matches(river.points()))throw new AssertionError("Geometry mismatch "+river.name());
   if(cached.profile().length!=river.profileSize())throw new AssertionError("Profile size");
   for(int i=0;i<river.profileSize();i++)if(river.water(i)!=cached.profile()[i])throw new AssertionError("Hydraulic profile changed");
   var points=new ArrayList<>(river.points());var p=points.get(0);
   points.set(0,new River.Point(Math.nextUp(p.x()),Math.nextDown(p.z()),p.station(),Math.nextUp(p.width())));
   if(!cached.shape().matches(points))throw new AssertionError("Harmless ULP rejected");
   points.set(0,new River.Point(p.x()+1,p.z(),p.station(),p.width()));
   if(cached.shape().matches(points))throw new AssertionError("One-block endpoint displacement accepted");
   points.set(0,new River.Point(p.x(),p.z(),p.station(),Double.NaN));
   try {cached.shape().matches(points);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
   signatures++;profileNodes+=river.profileSize();
  }
  System.out.println("PORTABLE HYDROLOGY PASS\njava="+System.getProperty("java.runtime.version")+"\nconcurrentCallers=8 sharedEngine=1\nriverSignatures="+signatures+" profileNodes="+profileNodes+"\nULPTolerance=PASS endpointDisplacementRejected=PASS nonFiniteRejected=PASS\nseconds="+(System.nanoTime()-start)*1e-9);
 }
}
