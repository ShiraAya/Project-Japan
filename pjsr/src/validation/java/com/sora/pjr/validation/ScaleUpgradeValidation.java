package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;

/** Current release gates: physical scale, real hydraulic topology, coastal land and column contracts. */
public final class ScaleUpgradeValidation {
  private static long checks;
  private static void require(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
  public static void main(String[] args)throws Exception {
    long start=System.nanoTime();ScaleValidation.verify();
    WorldEngine engine=WorldEngine.load();WorldData data=engine.hydrology().data();
    double loadSeconds=(System.nanoTime()-start)/1e9;
    require(data.rivers().size()==389 && data.lakes().size()==221,"Expanded water selection retained");
    Map<String,River> rivers=new HashMap<>();for(River r:data.rivers())rivers.put(r.name(),r);
    long profiles=0;int gates=0,junctions=0,maskedCentres=0;
    for(River r:data.rivers()) {
      int previous=r.water(0);
      for(int s=1;s<r.profileSize();s++) {
        int current=r.water(s);require(current<=previous && previous-current<=1,"Invalid downstream profile "+r.name()+" @ "+s);previous=current;profiles++;
      }
      for(boolean source:new boolean[]{false,true}) {
        String parentName=source?r.sourceRiver():r.terminalRiver();if(parentName.isEmpty())continue;
        River parent=rivers.get(parentName);require(parent!=null,"Missing parent "+parentName);
        River.Point p=r.at(source?0:r.totalLength());River.Hit nearest=null;
        for(River.Segment segment:parent.segments()) {
          River.Hit h=segment.nearest(p.x(),p.z());if(nearest==null||h.distance()<nearest.distance())nearest=h;
        }
        require(nearest!=null&&nearest.distance()<.02,"Disconnected junction "+r.name());
        require(r.water(source?0:r.totalLength())==parent.water(nearest.station()),"Junction level "+r.name());junctions++;
      }
      for(River.Gate g:r.gates()) {
        for(double station:new double[]{g.first(),(g.first()+g.last())/2,g.last()})require(r.water(station)==g.waterY(),"Lake gate "+r.name());gates++;
      }
      for(int i=0;i<=24;i++) {
        River.Point p=r.at(r.totalLength()*i/24.);int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());
        Geometry g=engine.geometry(x,z);column(engine,x,z);
        require(g.wet(),"Dry river centre "+r.name()+" "+x+","+z);
      }
    }
    for(Lake l:data.lakes()) {
      Geometry g=engine.geometry(l.representativeX(),l.representativeZ());
      require(g.kind()==Hydrology.Kind.LAKE&&g.waterY()==l.waterY(),"Lake representative "+l.name());
      column(engine,l.representativeX(),l.representativeZ());
    }
    for(var raw:RawGeography.load().lakes())if(Double.isFinite(raw.elevationMetres())) {
      Lake l=data.lakes().stream().filter(v->v.id().equals(raw.id())).findFirst().orElseThrow();
      int reference=(int)Math.round(LakeReferences.elevation(raw.id(),raw.elevationMetres())*Geo.SCALE);
      int expected=LakeReferences.terrainSupportedLevel(raw.id())
          ?LakeLevels.estimate(raw.polygon(),engine.hydrology().terrain(),reference).level():reference;
      require(l.waterY()==expected,"Reviewed lake elevation "+raw.name());
    }
    int coastal=0;
    for(String line:Files.readAllLines(Path.of("data/precision-coast-probes.tsv"))) {
      if(line.isBlank()||line.startsWith("#"))continue;
      String[] f=line.split("\\t");int x=Integer.parseInt(f[1]),z=Integer.parseInt(f[2]);int kind=Integer.parseInt(f[3]);
      Terrain t=engine.hydrology().terrain();var sample=t.sample(x,z);Geometry g=engine.geometry(x,z);
      require(sample.land()==(kind!=1),"Coast source ownership "+f[0]+" @ "+x+","+z);
      if(kind==0 && g.wet())require(g.kind()==Hydrology.Kind.RIVER || g.kind()==Hydrology.Kind.LAKE,"Dry land became ocean outside reviewed port fragments "+f[0]);
      if(kind==2)require(g.kind()!=Hydrology.Kind.OCEAN,"Inland river classified as sea "+f[0]);
      require(!g.owner().startsWith("tidal:"),"Unexpected automatic canal "+f[0]);column(engine,x,z);coastal++;
    }
    Terrain t=engine.hydrology().terrain();
    for(double[] p:new double[][]{{138.7274,35.3606},{142.85,43.66},{133.55,33.56},{139.767125,35.681236}}) {
      int x=(int)Math.round(Geo.x(p[0])),z=(int)Math.round(Geo.z(p[1]));column(engine,x,z);
      require(t.sample(x,z).ground()<Geo.MAX_Y-32,"Vertical clearance");
    }
    LakeNetworkValidation.verify(engine);
    if(Boolean.getBoolean("pjr.preview"))UrbanCoastPreview.render(engine);
    String report="PJR 0.1.24 scale/precision/hydrology validation PASS\nscale=0.5:0.5:0.5\nrivers=389\nlakes=221\nprofiles="+profiles+"\ngates="+gates+"\njunctions="+junctions+"\ncoastalSourceProbes="+coastal+"\nsurveyVetoedLegacyCentreSamples="+maskedCentres+"\nengineLoadSeconds="+loadSeconds+"\nassertions="+checks+"\nelapsedSeconds="+(System.nanoTime()-start)/1e9+"\nNo graphical Minecraft client or full fluid-tick simulation.\n";
    Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/scale-precision-validation.txt"),report);System.out.print(report);
  }
  private static void column(WorldEngine e,int x,int z) {
    Column c=e.column(x,z);Geometry g=e.geometry(x,z);
    require(c.at(Geo.MIN_Y)==SurfacePainter.Material.BEDROCK,"Bedrock contract");
    require(c.at(g.groundY())!=SurfacePainter.Material.AIR,"Solid ground contract");
    require(c.at(c.surfaceHeight())==SurfacePainter.Material.AIR,"Surface contract");
    if(g.wet())require(c.at(g.waterY())==SurfacePainter.Material.WATER,"Water source contract");
  }
}
