package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exact-column checks and exports for the user's seven 0.1.12 screenshots. */
public final class HarbourFeedbackValidation {
  private static long checks;
  private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    var e=WorldEngine.load();
    if(args.length>0)export(e,Path.of(args[0]));else verify(e);
  }
  public static void verify(WorldEngine e)throws Exception {
    var t=e.hydrology().terrain();
    for(int[] p:new int[][]{{464,7814},{2292,7928},{3680,9088}})
      require(e.geometry(p[0],p[1]).kind()==Hydrology.Kind.OCEAN,"Screenshot offshore speck retained "+Arrays.toString(p));
    int wetPairs=0,maxBedStep=0,expanded=0;
    for(int[] c:new int[][]{{5971,-60},{3000,1600},{-850,4400}}) {
      for(int z=c[1]-256;z<=c[1]+256;z+=2)for(int x=c[0]-256;x<=c[0]+256;x+=2) {
        var a=e.geometry(x,z);
        if(!a.wet() || a.waterY()!=0 || t.channelAuthority(x,z)!=1)continue;
        if(t.sample(x,z).land() && !t.tidalChannel(x,z) && t.mappedRiver(a.owner())) {
          require(t.channelShoreDistance(x,z)<=2,"Excessive surveyed-bank widening");expanded++;
        }
        for(int[] off:new int[][]{{1,0},{0,1}}) {
          var b=e.geometry(x+off[0],z+off[1]);
          if(!b.wet() || b.waterY()!=0 || t.channelAuthority(b.x(),b.z())!=1)continue;
          int diff=Math.abs(a.groundY()-b.groundY());maxBedStep=Math.max(maxBedStep,diff);wetPairs++;
          require(diff<=1,"River/sea bed seam at "+x+","+z+": "+diff);
        }
      }
    }
    require(wetPairs>10000,"River mouth bed coverage");
    StringBuilder report=new StringBuilder("PJR harbour feedback validation PASS\n");
    report.append("offshoreSpeckSites=3\nwetAdjacentPairs="+wetPairs+"\nmaxBedStep="+maxBedStep+"\nslightBankExpansionSamples="+expanded+"\n");
    for(String id:List.of("W09:gc01_539","W09:gc01_545","W09:gc01_507")) {
      Lake l=e.hydrology().data().lakes().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();
      int[] rim={0,0,0};
      LakeLevels.dryShore(l.polygon(),key->{int x=(int)(key>>32),z=(int)key;var g=e.geometry(x,z);if(g.wet())return;rim[0]++;if(g.containment()){rim[1]++;rim[2]=Math.max(rim[2],g.groundY()-g.naturalY());}});
      report.append(l.name()+" dryRim="+rim[0]+" containment="+rim[1]+" maxRaise="+rim[2]+"\n");
    }
    // The centre marked in screenshot six is on land near the Edo mouth, far
    // east of the Imperial Palace; a broad circle is not an identifiable river trace.
    require(!e.geometry(5772,290).wet(),"Unexpected water at screenshot-six marker");
    report.append("screenshotSixMarker=DRY\nassertions="+checks+"\nNo client renderer or fluid tick simulation.\n");
    Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/harbour-feedback-validation.txt"),report);System.out.print(report);
  }
  public static void export(WorldEngine e,Path dir)throws Exception {
    Files.createDirectories(dir);
    tile(e,dir,"specks",-200,7400,4200,9500,4);
    tile(e,dir,"edo",5400,-450,6650,750,2);
    tile(e,dir,"arakawa",2650,1250,3500,2100,2);
    tile(e,dir,"nojiri",-292800,155780,-292250,156310,1);
    tile(e,dir,"otsuru",-314700,153920,-314150,154450,1);
    tile(e,dir,"manno",-200350,63000,-199800,63530,1);
  }
  private static void tile(WorldEngine e,Path dir,String name,int x0,int z0,int x1,int z1,int step)throws Exception {
    int w=(x1-x0)/step+1,h=(z1-z0)/step+1;
    try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve(name+".bin"))))) {
      for(int a:new int[]{x0,z0,step,w,h})out.writeInt(a);
      for(int j=0;j<h;j++)for(int i=0;i<w;i++) {
        var g=e.geometry(x0+i*step,z0+j*step);
        out.writeByte(g.kind().ordinal());out.writeShort(g.groundY());out.writeShort(g.waterY());out.writeShort(g.naturalY());out.writeBoolean(g.containment());
      }
    }
    e.releaseThreadCache();
  }
}
