package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;

/** User-requested removal of Tokyo reclamation, compared with pre-overlay evidence. */
public final class TokyoBaseCoastValidation {
  private static long checks;
  private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception{
    WorldEngine e=WorldEngine.load();
    if(args.length>0){preview(e,Path.of(args[0]));return;}
    verify(e);
  }
  public static void verify(WorldEngine e)throws Exception{
    Terrain t=e.hydrology().terrain();
    for(String name:new String[]{"tokyo-bay","tokyo-bay-outer"})
      require(TokyoBaseCoastValidation.class.getResource("/assets/pjr/terrain/"+name+".pjrt.gz")==null,
          "Retired Tokyo resource still packaged: "+name);
    int baseline=0;
    for(String line:Files.readAllLines(Path.of("data/baseline-coastal-boundaries-0.1.9.csv"))){
      if(!line.startsWith("0,"))continue;
      String[] v=line.split(",");int x=Integer.parseInt(v[1]),z=Integer.parseInt(v[2]);var s=t.sample(x,z);
      require(s.land()==Boolean.parseBoolean(v[3])&&s.ground()==Integer.parseInt(v[4]),"Pre-reclamation baseline mismatch "+x+","+z);baseline++;
    }
    require(baseline>2000,"Tokyo pre-overlay baseline coverage");
    int samples=0;
    for(int z=-2500;z<=26000;z+=64)for(int x=-8500;x<=13000;x+=64){
      require(t.channelAuthority(x,z)==0&&!t.tidalChannel(x,z),"Surveyed Tokyo port overlay still active");
      require(!e.geometry(x,z).owner().startsWith("tidal:"),"Survey-only water owner remains");samples++;
    }
    require(!e.geometry(2319,1191).wet(),"Retired screenshot port canal remains");
    require(e.hydrology().data().rivers().size()==332&&e.hydrology().data().lakes().size()==205,"Retained river/lake records");
    String report="PJR Tokyo base coastline validation PASS\nretiredTokyoResources=2\ntokyoBoundaryBaselineMatches="+baseline+
        "\nregionalSamples="+samples+"\nsurveyedPortSamples=0\nscreenshotPortCanal=DRY\nassertions="+checks+
        "\nNational base coast restored; ordinary W05 rivers and existing lake links remain.\nNo graphical client or full fluid-tick simulation.\n";
    Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/tokyo-base-coast-validation.txt"),report);System.out.print(report);
  }
  private static void preview(WorldEngine e,Path path)throws Exception{
    Files.createDirectories(path.toAbsolutePath().getParent());int x0=-5000,z0=-2000,step=16,w=751,h=751;
    try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))){
      for(int v:new int[]{x0,z0,step,w,h})out.writeInt(v);
      for(int z=0;z<h;z++)for(int x=0;x<w;x++)out.writeByte(e.geometry(x0+x*step,z0+z*step).kind().ordinal());
    }
  }
}
