package com.sora.pjr.roads;

import com.sora.pjr.core.*;
import java.util.*;

/** Entire corridor solved once from immutable PJR terrain, independent of chunk exploration order. */
public final class RoadPlan {
  public record Sample(double x,double y,double z,double station,double width,int forward,int reverse,boolean bridge) {}
  public record Unit(UUID id,List<Sample> samples,double station) {}
  public final List<Unit> units;
  private final Map<Long,List<Unit>> byChunk;
  public final double length;
  public RoadPlan(RoadCatalog catalog,WorldEngine terrain) {
    var points=catalog.points;var raw=new ArrayList<Sample>();double station=0;
    for(int i=0;i<points.size()-1;i++){
      var a=points.get(i);var b=points.get(i+1);double distance=Math.hypot(b.x()-a.x(),b.z()-a.z());
      if(distance<.01||distance>512)throw new IllegalStateException("Invalid source spacing");
      int steps=(int)Math.ceil(distance/2);
      for(int j=0;j<steps;j++){
        double t=j/(double)steps,x=a.x()+(b.x()-a.x())*t,z=a.z()+(b.z()-a.z())*t;
        double width=(a.forward()+a.reverse())*3.0+1.0;
        double dx=(b.x()-a.x())/distance,dz=(b.z()-a.z())/distance;
        double top=-Double.MAX_VALUE;boolean wet=false;
        for(double offset:new double[]{-width/2,0,width/2}){
          Geometry g=terrain.geometry((int)Math.floor(x-dz*offset),(int)Math.floor(z+dx*offset));
          wet|=g.wet();top=Math.max(top,g.wet()?g.waterY()+5:g.groundY()+1.25);
        }
        if(wet&&!a.bridge())throw new IllegalStateException("Road crosses PJR water without source bridge at "+x+","+z);
        raw.add(new Sample(x,top,z,station+distance*t,width,a.forward(),a.reverse(),a.bridge()));
      }
      station+=distance;
    }
    var end=points.get(points.size()-1);var last=raw.get(raw.size()-1);
    double endY=terrain.geometry((int)Math.floor(end.x()),(int)Math.floor(end.z())).groundY()+1.25;
    raw.add(new Sample(end.x(),endY,end.z(),station,last.width,last.forward,last.reverse,last.bridge));length=station;
    // Minimal upper envelope satisfying a 12% grade cap. No use of loaded block heights.
    double[] height=raw.stream().mapToDouble(Sample::y).toArray();
    for(int i=1;i<height.length;i++)height[i]=Math.max(height[i],height[i-1]-.12*(raw.get(i).station-raw.get(i-1).station));
    for(int i=height.length-2;i>=0;i--)height[i]=Math.max(height[i],height[i+1]-.12*(raw.get(i+1).station-raw.get(i).station));
    // Smooth only cross-section width over 24m; all original centreline anchors stay fixed.
    double[] widths=new double[raw.size()];
    for(int i=0;i<raw.size();i++){double sum=0,weight=0;for(var p:raw){double w=Math.max(0,12-Math.abs(p.station-raw.get(i).station));sum+=p.width*w;weight+=w;}widths[i]=sum/weight;}
    var solved=new ArrayList<Sample>();
    for(int i=0;i<height.length;i++){
      var s=raw.get(i);if(height[i]-s.y>12)throw new IllegalStateException("Pilot terrain adjustment exceeds 12 blocks");
      solved.add(new Sample(s.x,height[i],s.z,s.station,widths[i],s.forward,s.reverse,s.bridge));
    }
    // Bounded edit units, split at lane and bridge changes. Shared sample/tangent across cuts.
    var result=new ArrayList<Unit>();int from=0;
    for(int i=1;i<solved.size();i++){
      var first=solved.get(from);var s=solved.get(i);
      boolean boundary=i==solved.size()-1||s.station-first.station>=96||s.forward!=first.forward||s.reverse!=first.reverse||s.bridge!=first.bridge;
      if(boundary){
        var part=new ArrayList<>(solved.subList(from,i+1));
        // The last sample is a shared boundary; each side keeps its own cross-section.
        var e=part.get(part.size()-1);part.set(part.size()-1,new Sample(e.x,e.y,e.z,e.station,e.width,first.forward,first.reverse,first.bridge));
        UUID id=UUID.nameUUIDFromBytes((RoadCatalog.SNAPSHOT+":"+from+":"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result.add(new Unit(id,List.copyOf(part),first.station));from=i;
      }
    }
    if(result.size()>128)throw new IllegalStateException("Pilot unit budget exceeded");units=List.copyOf(result);
    var index=new HashMap<Long,List<Unit>>();
    for(var unit:units){
      var keys=new HashSet<Long>();
      for(var s:unit.samples){double pad=s.width/2+3;for(int x=(int)Math.floor((s.x-pad)/16);x<=Math.floor((s.x+pad)/16);x++)for(int z=(int)Math.floor((s.z-pad)/16);z<=Math.floor((s.z+pad)/16);z++)keys.add(Geo.key(x,z));}
      for(long key:keys)index.computeIfAbsent(key,k->new ArrayList<>()).add(unit);
    }
    var immutable=new HashMap<Long,List<Unit>>();index.forEach((k,v)->immutable.put(k,List.copyOf(v)));byChunk=Map.copyOf(immutable);
  }
  public List<Unit> inChunk(int x,int z){return byChunk.getOrDefault(Geo.key(x,z),List.of());}
  public boolean occupied(int x,int z,int padding){for(var u:inChunk(Math.floorDiv(x,16),Math.floorDiv(z,16)))for(var s:u.samples)if(Math.hypot(x-s.x,z-s.z)<s.width/2+padding+2)return true;return false;}
}
