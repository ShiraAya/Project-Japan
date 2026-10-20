import com.sora.pjr.core.*;
import com.sora.pjr.forge.WorldRuntime;
import java.nio.file.*;
public class RiverGapProbe {
 public static void main(String[] args)throws Exception {
  var e=WorldRuntime.engine();var h=e.hydrology();var t=h.terrain();StringBuilder b=new StringBuilder("river,samples,dry,maxDryRunBlocks\n");
  for(var r:h.data().rivers()) {
   int n=0,dry=0,max=0,run=0;
   for(double s=0;s<=r.mouthDistance();s+=8) {
    var p=r.at(s);var c=h.sample((int)Math.floor(p.x()),(int)Math.floor(p.z()));n++;
    if(!c.water().wet()){dry++;run+=8;max=Math.max(max,run);}else run=0;
   }
   b.append(r.name()+","+n+","+dry+","+max+"\n");
   if(java.util.Set.of("Tama","Shonai","Kiso","Nagara","Ibi").contains(r.name()))System.out.println(r.name()+" samples="+n+" dry="+dry+" maxGap="+max);
  }
  Files.writeString(Path.of(args[0]),b);
  for(int[] p:new int[][]{{-4728,5058},{-4800,5000},{-196902,51817}}){var c=h.sample(p[0],p[1]);System.out.println(java.util.Arrays.toString(p)+" lon="+Geo.lon(p[0])+" lat="+Geo.lat(p[1])+" water="+c.water().kind()+" level="+c.water().level()+" authority="+t.channelAuthority(p[0],p[1])+" shore="+t.channelShoreDistance(p[0],p[1]));}
 }
}
