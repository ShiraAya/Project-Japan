import com.sora.pjr.core.*;
import com.sora.pjr.forge.WorldRuntime;
public class StartupProbe {
 public static void main(String[] args) {
  System.out.println("java="+System.getProperty("java.runtime.version")+" scale="+Geo.SCALE+" maxBuildY="+(Geo.MAX_Y-1));
  long t=System.nanoTime();
  try {
   WorldEngine engine=WorldRuntime.engine();
   System.out.println("STARTUP PASS seconds="+(System.nanoTime()-t)*1e-9+" rivers="+engine.hydrology().data().rivers().size());
   if(args.length>0) {
    Terrain terrain=engine.hydrology().terrain();int best=Integer.MIN_VALUE,bx=0,bz=0;double bm=0;
    int cx=(int)Math.round(Geo.x(138.7274)),cz=(int)Math.round(Geo.z(35.3606));
    for(int z=cz-900;z<=cz+900;z+=8) for(int x=cx-900;x<=cx+900;x+=8) {
     var s=terrain.sample(x,z);if(s.ground()>best){best=s.ground();bx=x;bz=z;bm=s.metres();}
    }
    int mx=bx,mz=bz;
    for(int z=mz-12;z<=mz+12;z++) for(int x=mx-12;x<=mx+12;x++) {
     var s=terrain.sample(x,z);if(s.ground()>best){best=s.ground();bx=x;bz=z;bm=s.metres();}
    }
    var g=engine.geometry(bx,bz);var c=engine.column(bx,bz);
    System.out.printf("FUJI x=%d z=%d longitude=%.8f latitude=%.8f terrainMetres=%.4f naturalY=%d groundY=%d surfaceHeight=%d generationClamp=%d buildMax=%d headroom=%d%n",bx,bz,Geo.lon(bx),Geo.lat(bz),bm,best,g.groundY(),c.surfaceHeight(),Geo.MAX_Y-17,Geo.MAX_Y-1,Geo.MAX_Y-1-g.groundY());
    if(best>=Geo.MAX_Y-17 || g.groundY()!=best)throw new AssertionError("Fuji clipped");
   }
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
}
