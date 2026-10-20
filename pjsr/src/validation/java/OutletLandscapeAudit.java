import com.sora.pjr.core.*;
import java.nio.file.*;import java.io.*;import java.util.*;
public class OutletLandscapeAudit {
 static final int[][] FOCUS={{-80909,4068},{-32219,-15145},{-32924,15190}};
 static WorldEngine e; static Hydrology h;
 public static void main(String[] args)throws Exception{
  e=WorldEngine.load();h=e.hydrology();Path out=Path.of(args[0]);Files.createDirectories(out);
  for(int[] f:FOCUS){
   var near=h.sample(f[0],f[1]);System.out.println("FOCUS "+Arrays.toString(f)+" river="+(near.river()==null?"":near.river().river().name())+" lake="+near.shore());
   int n=321,step=2,x0=f[0]-n,z0=f[1]-n;
   int count=0,worst=0,wx=0,wz=0,wd=0;long excess=0;
   try(var b=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("map"+f[0]+".bin"))))){
    b.writeInt(n);b.writeInt(step);b.writeInt(x0);b.writeInt(z0);
    for(int z=z0;z<z0+n*step;z+=step)for(int x=x0;x<x0+n*step;x+=step){
     var g=e.geometry(x,z);b.writeShort(g.naturalY());b.writeShort(g.groundY());b.writeShort(g.wet()?g.waterY():-30000);b.writeByte(g.kind().ordinal());
     if(g.wet())continue;
     for(int d=0;d<2;d++){var a=e.geometry(x+(d==0?1:0),z+(d==1?1:0));if(a.wet())continue;count++;int jump=Math.abs(a.groundY()-g.groundY());int natural=Math.abs(a.naturalY()-g.naturalY());if(jump>natural+2)excess++;if(jump>worst){worst=jump;wx=x;wz=z;wd=d;}}
    }
   }
   System.out.printf("SLOPE x=%d dryPairs=%d maxAdjacentStep=%d artificialOver2=%d worst=%d,%d direction=%d%n",f[0],count,worst,excess,wx,wz,wd);
   dump(wx,wz);dump(wx+(wd==0?1:0),wz+(wd==1?1:0));
  }
 }
 static void dump(int x,int z){
  var c=h.sample(x,z);var g=e.geometry(x,z);System.out.println("POINT "+x+","+z+" "+g+" nearest="+c.river()+" shore="+c.shore());
  Map<River,River.Hit> nearest=new HashMap<>();
  for(var s:h.indexedSegmentsAt(x,z)){var v=s.nearest(x+.5,z+.5);nearest.merge(v.river(),v,(a,b)->a.distance()<b.distance()?a:b);}
  for(var hit:nearest.values()){var r=hit.river();System.out.println("RIVER "+r.name()+" station="+hit.station()+" distance="+hit.distance()+" width="+hit.width()+" top="+r.top(hit.station())+" bank="+r.bankWater(hit.station())+" gates="+r.gates());}
 }
}
