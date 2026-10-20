package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Acceptance tests for the eight September screenshot reports. */
public final class CoastJunctionValidation {
  private static long checks;
  private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception{verify(WorldEngine.load());}
  public static void verify(WorldEngine e)throws Exception{
    var data=e.hydrology().data();
    Map<String,River> rivers=new HashMap<>();for(var r:data.rivers())rivers.put(r.name(),r);
    for(int[] p:new int[][]{{20591,-223057},{33175,-4397},{-73191,40641}})
      require(e.geometry(p[0],p[1]).kind()!=Hydrology.Kind.OCEAN,"Screenshot inland ocean "+Arrays.toString(p));
    Lake hamana=data.lakes().stream().filter(l->l.name().equals("Hamana")).findFirst().orElseThrow();
    require(hamana.polygon().holes().size()==22,"Keep real Hamana islands and canals from W09");
    long lakeCells=0, fringeCells=0;
    for(int z=(int)hamana.polygon().bounds().minZ();z<=hamana.polygon().bounds().maxZ();z+=12)
      for(int x=(int)hamana.polygon().bounds().minX();x<=hamana.polygon().bounds().maxX();x+=12){
        double d=hamana.polygon().distance(x+.5,z+.5);
        if(d>120&&!hamana.polygon().contains(x+.5,z+.5))continue;
        Geometry g=e.geometry(x,z);
        if (hamana.polygon().contains(x+.5,z+.5) || z<41800)
          require(g.kind()!=Hydrology.Kind.OCEAN,"Hamana false ocean fringe "+x+","+z);
        if(hamana.polygon().contains(x+.5,z+.5))lakeCells++;else fringeCells++;
      }
    long linkSamples=0;
    for(String name:List.of("Hitachitone","Wani","Imagiriguchi")){
      River r=rivers.get(name);require(r!=null,"Missing connecting waterway "+name);
      for(double s=0;s<=r.totalLength();s+=.75){var p=r.at(s);Geometry g=e.geometry((int)Math.floor(p.x()),(int)Math.floor(p.z()));
        require(g.wet(),"Dry connecting waterway "+name+" "+s);
        require(g.waterY()==0&&g.waterTop()==0,"Unintended falls on lowland lake link "+name);
        linkSamples++;
      }
    }
    require(rivers.get("Hitachitone").gates().stream().anyMatch(g->g.lakeName().equals("外浪逆浦")),"Hitachitone crosses Sotonasakaura");
    require(rivers.get("Wani").sourceLake().equals("Kitaura")&&rivers.get("Wani").terminalLake().equals("外浪逆浦"),"Kitaura connected to Sotonasakaura");
    require(rivers.get("Imagiriguchi").sea(),"Hamana has a real open sea outlet");
    int junctions=0,minIruma=99,minSumida=99;
    for(River r:data.rivers()){
      if(r.terminalRiver().isEmpty()&&r.sourceRiver().isEmpty())continue;
      boolean end=!r.terminalRiver().isEmpty();
      double start=end?Math.max(0,r.totalLength()-128):0,finish=end?r.totalLength():Math.min(128,r.totalLength());
      Geometry last=null;
      for(double s=start;s<=finish;s+=.5){var p=r.at(s);Geometry g=e.geometry((int)Math.floor(p.x()),(int)Math.floor(p.z()));
        require(g.wet(),"Dry confluence centre "+r.name());
        if(p.width()>=Geo.horizontal(12)) require(g.waterY()-g.groundY()>=2,"Shallow blocking sill at "+r.name()+" "+s);
        if(last!=null)require(Math.abs(g.waterY()-last.waterY())<=2,"Water cliff at confluence "+r.name()+" "+s+" "+last+" -> "+g);
        if(r.name().equals("Iruma"))minIruma=Math.min(minIruma,g.waterY()-g.groundY());
        if(r.name().equals("Sumida"))minSumida=Math.min(minSumida,g.waterY()-g.groundY());
        last=g;
      }
      junctions++;
    }
    require(minIruma>=4,"Iruma confluence retains a navigable deep channel; no one-block sill");
    require(minSumida>=2,"Sumida bifurcation has no one-block blocking sill");
    // Sample the national coast independently at resource contour midpoints, then inspect
    // the actual adjacent wet/dry blocks. Do not infer quality from only a centreline height.
    int coastPairs=0,maxDepth=0,maxBankGap=0;Set<String> seen=new HashSet<>();
    try(var in=new DataInputStream(new GZIPInputStream(CoastJunctionValidation.class.getResourceAsStream("/assets/pjr/terrain/coast.pjrc.gz")))){
      require(in.readInt()==0x504a5243,"Coast resource");int n=in.readInt();
      for(int i=0;i<n;i++){
        double ax=in.readDouble(),az=in.readDouble(),bx=in.readDouble(),bz=in.readDouble();
        if(i%73!=0)continue;int cx=(int)Math.round((ax+bx)/2),cz=(int)Math.round((az+bz)/2);
        for(int dz=-20;dz<=20;dz++)for(int dx=-20;dx<=20;dx++){
          int x=cx+dx,z=cz+dz;Geometry g=e.geometry(x,z);if(g.kind()!=Hydrology.Kind.OCEAN)continue;
          // Estuary aprons are checked separately; unlike an ordinary beach they may
          // retain a short, fading channel at the shoreline.
          if(e.hydrology().sample(x,z).water().river()!=null)continue;
          for(int[] off:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}){Geometry land=e.geometry(x+off[0],z+off[1]);
            if(land.wet()||land.naturalY()>12||land.containment())continue;
            if(!seen.add(x+","+z+","+off[0]+","+off[1]))continue;
            coastPairs++;maxDepth=Math.max(maxDepth,-g.groundY());maxBankGap=Math.max(maxBankGap,land.groundY());
            require(-g.groundY()<=2,"First ocean cell too deep "+x+","+z+" depth="+-g.groundY());
            require(land.groundY()<=1,"Coastal lowland wall "+x+","+z+" bank="+land.groundY());
          }
        }
      }
    }
    require(coastPairs>1000,"National coastal interface coverage");
    String report="PJR coast and confluence validation PASS\nassertions="+checks+"\nhamanaLakeCells="+lakeCells+"\nhamanaFringeCells="+fringeCells+"\nlowlandLinkSamples="+linkSamples+"\njunctions="+junctions+"\nIrumaMinimumCentreDepth="+minIruma+"\nSumidaMinimumCentreDepth="+minSumida+"\ncoastalAdjacentPairs="+coastPairs+"\nmaxFirstOceanDepth="+maxDepth+"\nmaxLowlandBankGap="+maxBankGap+"\n";
    Files.writeString(Path.of("build/reports/coast-junction-validation.txt"),report);System.out.print(report);
  }
}
