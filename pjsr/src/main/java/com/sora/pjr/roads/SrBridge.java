package com.sora.pjr.roads;

import com.sora.pjr.core.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

/** SR 1.0.15 adapter: uses its ribbon, raster, structures and native editable records. */
public final class SrBridge {
  public static final UUID WORLD_OWNER=new UUID(0,0);
  public final RoadPlan plan;
  public final Map<UUID,RoadRecord> records;
  private final Map<UUID,RoadRaster.Local> rasters;
  public SrBridge(WorldEngine terrain) {
    plan=new RoadPlan(RoadCatalog.load(),terrain);
    var global=new TreeMap<Double,RoadPlan.Sample>();for(var u:plan.units)for(var s:u.samples())global.put(s.station(),s);
    var rs=new LinkedHashMap<UUID,RoadRecord>();var raster=new HashMap<UUID,RoadRaster.Local>();
    for(var unit:plan.units){
      var first=unit.samples().get(0);int lanes=first.forward()+first.reverse();
      Style style=first.reverse()==0?(lanes==1?Style.O1_ONE:lanes==2?Style.O2_ONE:Style.O3_ONE):(lanes<=2?Style.O2_YELLOW:Style.O4_YELLOW);
      var options=RoadProfile.Options.DEFAULT.traffic(true).hideArrows(true).lanes(new RoadLanes.Counts(first.forward(),first.reverse()));
      // Dense furniture stays disabled. Bridge barriers/supports use the existing SR planner.
      options=options.infrastructure(options.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));
      var settings=new Settings(Mode.CURVE,style,first.width(),.5,.35,90,first.width(),unit.samples().get(unit.samples().size()-1).width(),first.bridge()?Structure.BRIDGE:Structure.GROUND,6,RampTurn.AUTO,options);
      var samples=new ArrayList<Sample>();
      for(var s:unit.samples()){
        var low=global.lowerEntry(s.station());var high=global.higherEntry(s.station());
        var a=low==null?s:low.getValue();var b=high==null?s:high.getValue();
        V tangent=new V(b.x()-a.x(),0,b.z()-a.z()).horizontalUnit();
        samples.add(new Sample(new V(s.x(),s.y(),s.z()),tangent.left(),s.station()-unit.station(),s.width()/2));
      }
      var a=samples.get(0);var b=samples.get(samples.size()-1);
      Node start=node(a),end=node(b);
      var record=new RoadRecord(unit.id(),WORLD_OWNER,position(start),position(end),start,end,settings,false,6,List.of(),0,41,null,samples,new RoadFurniture.Phase(unit.station(),1));
      var ground=new RoadStructures.Ground(){
        public boolean joined(V point){return false;}
        public double top(double x,double z,double y){return terrain.geometry((int)Math.floor(x),(int)Math.floor(z)).groundY()+1;}
        public boolean blocked(RoadStructures.Part part){
          if(!part.pier())return false;
          double pad=part.halfExtent();
          for(int x=(int)Math.floor(part.a().x()-pad);x<=Math.ceil(part.a().x()+pad);x++)for(int z=(int)Math.floor(part.a().z()-pad);z<=Math.ceil(part.a().z()+pad);z++)if(terrain.geometry(x,z).wet())return true;
          return false;
        }
      };
      if(first.bridge())record=record.structures(RoadStructures.plan(record.mesh(),ground,record.furniturePhase()));
      rs.put(unit.id(),record);raster.put(unit.id(),new RoadRaster.Local(record.mesh(),record.structures()));
    }
    records=Map.copyOf(rs);rasters=Map.copyOf(raster);
  }
  private static Node node(Sample s){V d=new V(s.left().z(),0,-s.left().x());return new Node(s.center(),Math.toDegrees(Math.atan2(-d.x(),d.z())),0);}
  private static BlockPos position(Node n){return BlockPos.containing(n.position().x(),n.position().y(),n.position().z());}
  public void write(ChunkAccess chunk,WorldEngine terrain,Set<UUID> suppressed){
    int cx=chunk.getPos().x,cz=chunk.getPos().z;
    var selected=plan.inChunk(cx,cz).stream().filter(u->!suppressed.contains(u.id())).toList();
    var body=new HashSet<RoadRaster.Cell>();
    var ranges=new HashMap<Long,int[]>();
    for(var unit:selected){
      var r=records.get(unit.id());var local=rasters.get(unit.id());
      for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++){
        var cells=local.deckColumn(x,z);if(cells.isEmpty())continue;
        int low=cells.keySet().stream().mapToInt(RoadRaster.Cell::y).min().orElseThrow();
        int high=cells.keySet().stream().mapToInt(RoadRaster.Cell::y).max().orElseThrow();
        boolean ground=r.settings().structure()==Structure.GROUND;
        var previous=ranges.get(Geo.key(x,z));
        if(previous==null)ranges.put(Geo.key(x,z),new int[]{low,high,ground?1:0});
        else {previous[0]=Math.min(previous[0],low);previous[1]=Math.max(previous[1],high);previous[2]&=ground?1:0;}
      }
      body.addAll(local.cellsInChunk(cx,cz));
    }
    // Clear once for the union, then write all owners. Adjacent units cannot erase each other.
    for(var entry:ranges.entrySet()){
      int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();var range=entry.getValue();var g=terrain.geometry(x,z);
      if(range[2]==1&&!g.wet()){
        if(range[0]-g.groundY()>13||g.groundY()-range[0]>8)throw new IllegalStateException("PJSR earthwork budget exceeded");
        for(int y=g.groundY()+1;y<range[0];y++)chunk.setBlockState(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),false);
      }
      for(int y=range[0];y<=range[1]+6;y++)chunk.setBlockState(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),false);
    }
    for(var cell:body){
      if(cell.y()<chunk.getMinBuildHeight()||cell.y()>=chunk.getMaxBuildHeight())throw new IllegalStateException("PJSR outside height bounds");
      chunk.setBlockState(new BlockPos(cell.x(),cell.y(),cell.z()),SplineRoads.COLLIDER.get().defaultBlockState(),false);
    }
    for(var unit:selected){var r=records.get(unit.id());for(int i=0;i<2;i++){
      BlockPos p=i==0?r.a():r.b();Node n=i==0?r.start():r.end();
      if((p.getX()>>4)!=cx||(p.getZ()>>4)!=cz)continue;
      var state=SplineRoads.NODE.get().defaultBlockState();chunk.setBlockState(p,state,false);
      var entity=new NodeEntity(p,state);entity.owner=WORLD_OWNER;entity.heightExplicit=true;entity.headingLocked=true;entity.apply(n);chunk.setBlockEntity(entity);
    }}
  }
}
