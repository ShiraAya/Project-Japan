import com.sora.pjr.core.*;
import java.nio.file.*;import java.io.*;import java.util.*;
public class ScreenshotOutletAudit {
 public static void main(String[] args)throws Exception{
  WorldEngine e=WorldEngine.load();var h=e.hydrology();Path out=Path.of(args[0]);Files.createDirectories(out);
  int[][] points={{-104973,-45127},{-97840,-8123}};
  for(int[] p:points){
   var c=h.sample(p[0],p[1]);System.out.println("SCREENSHOT "+Arrays.toString(p)+" natural="+c.terrain()+" nearRiver="+c.river()+" shore="+c.shore());
   Map<String,River.Hit> rivers=new TreeMap<>();for(var seg:h.indexedSegmentsAt(p[0],p[1])){var v=seg.nearest(p[0],p[1]);rivers.merge(v.river().name(),v,(a,b)->a.distance()<b.distance()?a:b);}
   for(var v:rivers.values())System.out.println("NEAR "+v+" top="+v.river().top(v.station())+" gates="+v.river().gates());
   int n=401,step=2,x0=p[0]-400,z0=p[1]-400;int bad=0,maxExcess=0,raised=0,cut=0;
   try(var b=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("map"+p[0]+".bin"))))){
    b.writeInt(n);b.writeInt(step);b.writeInt(x0);b.writeInt(z0);
    for(int z=z0;z<z0+n*step;z+=step){for(int x=x0;x<x0+n*step;x+=step){
     var g=e.geometry(x,z);b.writeShort(g.naturalY());b.writeShort(g.groundY());b.writeShort(g.wet()?g.waterY():-30000);b.writeByte(g.kind().ordinal());
     if(g.wet())continue;raised=Math.max(raised,g.groundY()-g.naturalY());cut=Math.max(cut,g.naturalY()-g.groundY());
     for(int d=0;d<2;d++){var a=e.geometry(x+(d==0?1:0),z+(d==1?1:0));if(a.wet())continue;int jump=Math.abs(a.groundY()-g.groundY()),natural=Math.abs(a.naturalY()-g.naturalY());if(jump>natural+2)bad++;maxExcess=Math.max(maxExcess,jump-natural);}
    }if((z-z0)%200==0)System.out.println("MAP "+p[0]+" row="+((z-z0)/step));}
   }
   System.out.printf("FOCUS %d maxRaise=%d maxCut=%d artificialOver2=%d maxExcess=%d%n",p[0],raised,cut,bad,maxExcess);
  }
 }
}
