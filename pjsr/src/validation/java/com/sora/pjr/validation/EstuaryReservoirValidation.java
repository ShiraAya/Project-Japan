package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;

/** Geographic acceptance of submerged mouths, Sameura relief and the second regional expansion. */
public final class EstuaryReservoirValidation {
  private static long checks;
  private static void require(boolean ok,String message) {
    checks++;
    if(!ok) throw new AssertionError(message);
  }

  public static void main(String[] args)throws Exception { verify(WorldEngine.load()); }

  public static void verify(WorldEngine e)throws Exception {
    Terrain terrain=new RasterTerrain();
    Map<String,River> names=new HashMap<>();
    for(River r:e.hydrology().data().rivers()) names.put(r.name(),r);
    int estuaries=0,offshore=0,fullyMerged=0,apron=0;
    for(River r:names.values()) {
      if(!r.sea())continue;
      estuaries++;
      for(double s=Math.max(0,r.totalLength()-384);s<=r.totalLength();s+=3) {
        River.Point p=r.at(s),a=r.at(Math.max(0,s-4)),b=r.at(Math.min(r.totalLength(),s+4));
        double dx=b.x()-a.x(),dz=b.z()-a.z(),length=Math.max(1,Math.hypot(dx,dz));
        double radius=p.width()/2+.75;
        for(int j=-(int)Math.ceil(radius+2);j<=radius+2;j+=2) {
          int x=(int)Math.floor(p.x()-dz/length*j),z=(int)Math.floor(p.z()+dx/length*j);
          Geometry g=e.geometry(x,z);
          if(terrain.sample(x,z).land() || g.kind()==Hydrology.Kind.LAKE)continue;
          offshore++;
          require(g.kind()==(terrain.tidalChannel(x,z)?Hydrology.Kind.RIVER:Hydrology.Kind.OCEAN),"Offshore river ownership capsule: "+r.name());
          require(g.waterY()==0 && g.waterTop()==0,"Estuary level: "+r.name());
          int shelf=e.hydrology().oceanBed(x,z);
          if(terrain.coastDistance(x,z)>=Geo.horizontal(48)) {
            fullyMerged++;
            require(g.groundY()==shelf,"Offshore excavation outlives blending apron: "+r.name()+" "+x+","+z);
          } else apron++;
          var top=e.column(x,z).layers().top();
          require(top==SurfacePainter.Material.SAND || top==SurfacePainter.Material.GRAVEL,"Estuary substrate");
          if(!terrain.tidalChannel(x,z) && -g.groundY()*Geo.METRES_Y<=80)
            require(top==SurfacePainter.Material.SAND,"Shallow offshore mouth should use shelf sand");
        }
      }
    }
    require(estuaries>100 && offshore>10000 && fullyMerged>1000 && apron>100,"National estuary coverage");

    Lake lake=e.hydrology().data().lakes().stream().filter(l->l.id().equals("W09:gc01_519")).findFirst().orElseThrow();
    require(lake.name().equals("早明浦湖") && lake.waterY()==128 && lake.basinDepth()==10,"Sameura identity, level and retained visual depth");
    int[] rim={0,0,0,0};
    LakeLevels.dryShore(lake.polygon(),key->{
      int x=(int)(key>>32),z=(int)key;
      Geometry g=e.geometry(x,z);
      if(g.wet())return;
      rim[0]++;
      boolean dam=Math.hypot(x+210790.0,z-80352.0)<=64;
      for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
        Geometry q=e.geometry(x+d[0],z+d[1]);
        if(q.wet()) {
          require(g.groundY()>=q.waterTop(),"Uncontained reservoir shore");
          continue;
        }
        int jump=Math.abs(g.groundY()-q.groundY());
        if(dam)rim[2]=Math.max(rim[2],jump);
        else {
          rim[1]=Math.max(rim[1],jump);
          require(jump<=4,"Spurious tall perimeter wall away from Sameura dam "+x+","+z);
        }
      }
      if(g.groundY()-g.naturalY()>4) {
        require(dam,"Reservoir raised wall away from dam");rim[3]++;
      }
    });
    require(rim[0]>11000 && rim[2]>=20,"Whole reservoir rim and genuine dam relief covered");
    require(terrain.sample(-210750,80295).ground()>=128,"Restored actual hillside beside dam");
    // The local patch must join the national terrain without a hard rectangular edge.
    int seamMax=0;
    for(int z=77300;z<=82100;z+=8)for(int x:new int[]{-214200,-209500})
      seamMax=Math.max(seamMax,Math.abs(terrain.sample(x-1,z).ground()-terrain.sample(x+1,z).ground()));
    for(int x=-214200;x<=-209500;x+=8)for(int z:new int[]{77300,82100})
      seamMax=Math.max(seamMax,Math.abs(terrain.sample(x,z-1).ground()-terrain.sample(x,z+1).ground()));
    require(seamMax<=4,"Hard local DEM rectangle seam");

    String[] newNames={"Tsutsumi","Kanita","Niida-Aomori","Miyako-Chiba","Shiizu","Tagoe",
      "Seto-Shizuoka","Ota-Shizuoka","Nikko","Inabe","Niyodo","Monobe","Shinjo-Aomori","Morito-Hayama","Shinjo-Kochi","Aki-Kochi"};
    double[] minKm={34,16,85,14,7,5,28,46,44,39,124,67,14,7,26,26};
    StringBuilder expansion=new StringBuilder();
    for(int i=0;i<newNames.length;i++) {
      River r=names.get(newNames[i]);
      require(r!=null,"Missing regional river "+newNames[i]);
      require(r.totalLength()*Geo.METRES_XZ/1000>=minKm[i],"Truncated route "+r.name());
      require(r.sea(),"Regional river failed to reach sea "+r.name());
      require(r.at(0).width()<=Geo.horizontal(4)+1e-8,"Broad regional headwaters "+r.name());
      require(r.water(r.totalLength())==0,"Regional outlet above sea "+r.name());
      for(double s=0;s<=r.totalLength();s+=4) {
        River.Point p=r.at(s);
        require(e.geometry((int)Math.floor(p.x()),(int)Math.floor(p.z())).wet(),"Dry regional centre "+r.name()+" "+s);
      }
      expansion.append(String.format(Locale.ROOT,"%s lengthKm=%.3f widthBlocks=%.3f depthCap=%d gates=%d%n",
        r.name(),r.totalLength()*Geo.METRES_XZ/1000,r.at(r.totalLength()).width(),RiverShapes.get(r.name()).maxChannelDepth(),r.gates().size()));
    }
    String report="PJR estuary reservoir and expansion validation PASS\nassertions="+checks+
      "\nestuaries="+estuaries+"\noffshoreSamples="+offshore+"\nfullyMergedShelfSamples="+fullyMerged+
      "\nestuaryApronSamples="+apron+"\nSameuraDryRimCells="+rim[0]+"\nSameuraMaxDryJumpAwayFromDam="+rim[1]+
      "\nSameuraMaxDamJump="+rim[2]+"\nSameuraRaisedDamCells="+rim[3]+"\nlocalDemMaxBoundaryJump="+seamMax+
      "\naddedRivers="+newNames.length+"\n"+expansion+
      "Model geometry and Minecraft columns; no graphical client or live fluid tick validation.\n";
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/estuary-reservoir-validation.txt"),report);
    System.out.print(report);
  }
}
