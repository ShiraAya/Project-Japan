package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;

/** Acceptance of the two missing river regions, coastal land and open water passages. */
public final class CoastalExpansionValidation {
  private static long checks;
  private static void require(boolean ok,String message) {
    checks++;if(!ok)throw new AssertionError(message);
  }
  public static final String[] RIVERS={
    "Shiribeshitoshibetsu","Yurappu","Matsukura","Kameda","Kunebetsu","Ono-Hokkaido","Hekirichi",
    "Shiriuchi","Fukushima-Hokkaido","Amanogawa","Assabu","Torizaki","Otoshibe",
    "Kumano","Arida","Hidaka","Hiki","Tonda","Koza","Nachi","Choshi-Mie","Akaba"
  };
  public static final double[][] LAND={
    {139.775,35.625},{139.793,35.647},{139.7789,35.6544},{139.792,35.633},
    {139.828,35.645},{139.835,35.624},{139.803,35.604},{139.808,35.592},
    {139.812,35.535},{145.85,44.15},{147.883,45.227},{148.012,45.101},
    {146.751,43.805},{146.135,43.483}
  };
  private static final String[] LAND_NAMES={"Odaiba","Toyosu","Harumi","Ariake","Shinkiba","Wakasu",
    "Central-inner","Central-outer","Haneda-D","Kunashir-retained","Iturup-Kurilsk","Iturup-Baransky","Shikotan","Habomai-Shibotsu"};
  public static final double[][] WATER={
    {139.780,35.647},{139.809,35.613},{139.824,35.611},{139.800,35.5975},
    {139.805,35.536},{139.7983,35.6597},
    {146.5,44.25},{147.1,44.10},{146.9472,45.10745},{148.74658,44.88568}
  };

  public static void main(String[] args)throws Exception {verify(WorldEngine.load());}
  public static void verify(WorldEngine e)throws Exception {
    WorldData data=e.hydrology().data();Terrain terrain=new RasterTerrain();
    Map<String,River> names=new HashMap<>();for(var r:data.rivers())names.put(r.name(),r);
    require(names.size()==RawGeography.load().rivers().size() && data.lakes().size()==205,"Complete selected network / 205 lakes");
    double[] minKm={70,25,14,15,14,25,20,28,7,23,35,15,12,150,84,135,82,45,47,9,10,11};
    StringBuilder report=new StringBuilder("PJR coastal expansion validation PASS\n");
    int wetCentres=0,bankContacts=0;
    for(int i=0;i<RIVERS.length;i++) {
      River r=names.get(RIVERS[i]);require(r!=null,"Missing peninsula river "+RIVERS[i]);
      require(r.totalLength()*Geo.METRES_XZ/1000>=minKm[i],"Truncated river "+r.name());
      require(r.sea() && r.water(r.totalLength())==0,"Missing sea outlet "+r.name());
      require(r.at(0).width()<=1.500001,"Overwide source "+r.name());
      for(double station=0;station<r.totalLength();station+=8) {
        var p=r.at(station);int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());
        require(e.geometry(x,z).wet(),"Dry centre "+r.name()+" @ "+station);wetCentres++;
      }
      require(r.at(r.totalLength()*.95).width()>r.at(r.totalLength()*.3).width(),"No downstream widening "+r.name());
      for(int k=1;k<20;k++) {
        double s=r.totalLength()*k/20;var p=r.at(s);var a=r.at(s-4);var b=r.at(s+4);
        double dx=b.x()-a.x(),dz=b.z()-a.z(),len=Math.hypot(dx,dz);
        double radius=Hydrology.radius(new River.Hit(r,s,0,p.width()));
        for(int offset=-(int)Math.ceil(radius+4);offset<=radius+4;offset++) {
          int x=(int)Math.floor(p.x()-dz/len*offset),z=(int)Math.floor(p.z()+dx/len*offset);Geometry g=e.geometry(x,z);
          if(g.wet())continue;
          for(int[] n:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            var next=e.geometry(x+n[0],z+n[1]);if(!next.wet())continue;
            require(g.groundY()>=next.waterTop(),"Uncontained new bank "+r.name());bankContacts++;
          }
        }
      }
      report.append(String.format(Locale.ROOT,"river=%s lengthKm=%.3f mouthWidth=%.3f gates=%d%n",r.name(),r.totalLength()*Geo.METRES_XZ/1000,r.at(r.totalLength()).width(),r.gates().size()));
    }
    // Tokyo reclamation acceptance was withdrawn in 0.1.14. The other islands remain.
    for(int i=9;i<LAND.length;i++) {
      int x=(int)Math.round(Geo.x(LAND[i][0])),z=(int)Math.round(Geo.z(LAND[i][1]));
      require(terrain.sample(x,z).land(),"Mapped land absent "+LAND_NAMES[i]);
      require(!e.geometry(x,z).wet(),"Expected island land is submerged "+LAND_NAMES[i]);
      report.append("land=").append(LAND_NAMES[i]).append(" groundY=").append(e.geometry(x,z).groundY()).append('\n');
    }
    for(int i=6;i<WATER.length;i++) {
      double[] p=WATER[i];
      int x=(int)Math.round(Geo.x(p[0])),z=(int)Math.round(Geo.z(p[1]));
      require(!terrain.sample(x,z).land() || terrain.tidalChannel(x,z),"Water passage filled "+Arrays.toString(p));
      require(e.geometry(x,z).kind()==(terrain.tidalChannel(x,z)?Hydrology.Kind.RIVER:Hydrology.Kind.OCEAN),"Water passage misclassified "+Arrays.toString(p));
    }
    int shorelineContacts=0;int x0=(int)Geo.x(139.68),x1=(int)Geo.x(139.94),z0=(int)Geo.z(35.70),z1=(int)Geo.z(35.51);
    for(int z=z0;z<z1;z+=5)for(int x=x0;x<x1;x+=5) {
      if(terrain.sample(x,z).land() || terrain.coastDistance(x,z)>5)continue;
      for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++) {
        int px=x+dx,pz=z+dz;var g=e.geometry(px,pz);
        if(g.kind()!=Hydrology.Kind.OCEAN)continue;
        // Inland ownership includes tidal river water. Only actual dry columns
        // define a beach; a river/sea class seam must not restart a one-block shelf.
        boolean contact=!e.geometry(px+1,pz).wet() || !e.geometry(px-1,pz).wet()
            || !e.geometry(px,pz+1).wet() || !e.geometry(px,pz-1).wet();
        if(!contact)continue;
        var context=e.hydrology().sample(px,pz);
        if(context.water().river()!=null)continue;
        require(g.groundY()==-1,"Coast begins with a deep vertical seabed "+px+","+pz);shorelineContacts++;
      }
    }
    require(shorelineContacts>1000,"Coastline acceptance coverage");
    int baselineRivers=0,baselineLakes=0,recomputed=0;
    Map<String,Lake> lakes=new HashMap<>();for(var l:data.lakes())lakes.put(l.id(),l);
    for(String line:Files.readAllLines(Path.of("data/baseline-hydrology-0.1.9.tsv"))) {
      String[] v=line.split("\t");
      if(v[0].equals("river")) {
        require(names.containsKey(v[1]),"Existing river removed "+v[1]);baselineRivers++;
        if(!HydrologyFingerprint.river(names.get(v[1])).equals(v[2])) {recomputed++;report.append("recomputedExistingProfile=").append(v[1]).append('\n');}
      } else {
        require(HydrologyFingerprint.lake(lakes.get(v[1])).equals(v[2]),"Existing lake attributes changed "+v[1]);baselineLakes++;
      }
    }
    require(baselineRivers==148 && baselineLakes==205,"Full previous release comparison");
    int boundaryNodes=0;
    for(String line:Files.readAllLines(Path.of("data/baseline-coastal-boundaries-0.1.9.csv"))) {
      if(line.startsWith("region,")) continue;
      // Tokyo now returns to the same national raster as the 0.1.9 baseline.
      String[] v=line.split(",");int x=Integer.parseInt(v[1]),z=Integer.parseInt(v[2]);var value=terrain.sample(x,z);
      require(value.land()==Boolean.parseBoolean(v[3]) && value.ground()==Integer.parseInt(v[4]),"Local grid edge differs from baseline "+x+","+z);boundaryNodes++;
    }
    report.append("boundaryBaselineMatches=").append(boundaryNodes).append("\n");
    report.append("newRivers=22\nwetCentres=").append(wetCentres).append("\nbankContacts=").append(bankContacts)
      .append("\nshorelineContacts=").append(shorelineContacts).append("\nretainedRivers=148\nretainedLakeAttributes=205\nrecomputedProfiles=").append(recomputed)
      .append("\nassertions=").append(checks).append("\nNot a client rendering or fluid-tick test.\n");
    Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/coastal-expansion-validation.txt"),report);
    System.out.print(report);e.releaseThreadCache();
  }
}
