import com.sora.pjr.core.*;
import com.sora.pjr.forge.WorldRuntime;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class WaterFeedbackProbe {
 public static void main(String[] args)throws Exception {
  var e=WorldRuntime.engine();var hydro=e.hydrology();Path out=Path.of(args[1]);Files.createDirectories(out);
  if(args[0].equals("baseline")) {
   var river=hydro.data().rivers().stream().filter(r->r.name().equals("Tama")).findFirst().orElseThrow();
   double run=0,best=0,end=0;
   for(double s=0;s<=river.mouthDistance();s+=8){var p=river.at(s);if(!hydro.sample((int)Math.floor(p.x()),(int)Math.floor(p.z())).water().wet()){run+=8;if(run>best){best=run;end=s;}}else run=0;}
   var point=river.at(end-best/2);Files.writeString(Path.of(args[2]),(int)point.x()+","+(int)point.z()+",4\n");
   System.out.println("BASELINE Tama longest dry run="+best+" centre="+point.x()+","+point.z());
  }
  List<String> fixtures=Files.readAllLines(Path.of(args[2]));
  for(int n=0;n<fixtures.size();n++) {
   String[] f=fixtures.get(n).split(",");int cx=Integer.parseInt(f[0]),cz=Integer.parseInt(f[1]),step=Integer.parseInt(f[2]);var im=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);
   for(int iz=0;iz<512;iz++)for(int ix=0;ix<512;ix++) {
    int x=cx+(ix-256)*step,z=cz+(iz-256)*step;var c=hydro.sample(x,z);int color=switch(c.water().kind()) {case RIVER->0x4e9fc8;case LAKE->0x5b9dbb;case OCEAN->0x6e97ad;default->0xc5d2a9;};im.setRGB(ix,iz,color);
   }
   ImageIO.write(im,"png",out.resolve("view-"+n+".png").toFile());
  }
  if(args.length>3) {
   int probes=0,land=0;
   for(String l:Files.readAllLines(Path.of(args[3]))){if(l.startsWith("x,"))continue;String[] f=l.split(",");int x=Integer.parseInt(f[0]),z=Integer.parseInt(f[1]);probes++;if(hydro.terrain().sample(x,z).land())land++;}
   System.out.println("PORT probes="+probes+" land="+land);
   if(args[0].equals("final")&&land!=0)throw new AssertionError("Removed fragment still land");
  }
  System.out.println("WATER FEEDBACK "+args[0]+" PASS");
 }
}
