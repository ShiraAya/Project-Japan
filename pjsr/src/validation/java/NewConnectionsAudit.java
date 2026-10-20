import com.sora.pjr.core.*;
import java.util.*;import java.nio.file.*;import java.io.*;
/** Reproducible all-new-route sampling: endpoints, water continuity and adjacent dry-bank walls. */
public class NewConnectionsAudit {
 public static void main(String[] args)throws Exception {
  var engine=WorldEngine.load();var h=engine.hydrology();Set<String> names=new HashSet<>(Files.readAllLines(Path.of(args[0])));Path out=Path.of(args[1]);Files.createDirectories(out);
  long sampled=0,missing=0,walls=0;int done=0;StringBuilder csv=new StringBuilder("river,samples,dryCentreSamples,adjacentBanksOver8,maxAdjacentBank,firstLake,lastLake\n");
  for(var r:h.data().rivers())if(names.contains(r.name())) {
   int n=0,dry=0,bad=0,max=0;
   for(double s=0;s<=r.totalLength();s+=8) {
    var p=r.at(s);int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());var g=engine.geometry(x,z);n++;
    if(!g.wet()){dry++;continue;}
    var a=r.at(s-2);var b=r.at(s+2);double len=Math.hypot(b.x()-a.x(),b.z()-a.z());if(len<.01)continue;
    double nx=-(b.z()-a.z())/len,nz=(b.x()-a.x())/len;
    for(int side:new int[]{-1,1}) {
     double distance=p.width()/2+2;int bx=(int)Math.floor(p.x()+side*nx*distance),bz=(int)Math.floor(p.z()+side*nz*distance);var bank=engine.geometry(bx,bz);if(bank.wet())continue;
     int top=Integer.MIN_VALUE;for(int[] q:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}}){var wet=h.sample(bx+q[0],bz+q[1]).water();if(wet.kind()==Hydrology.Kind.RIVER)top=Math.max(top,wet.top());}
     if(top==Integer.MIN_VALUE)continue;int height=bank.groundY()-top;max=Math.max(max,height);if(height>8)bad++;
    }
   }
   sampled+=n;missing+=dry;walls+=bad;done++;
   csv.append(r.name()+","+n+","+dry+","+bad+","+max+","+(r.gates().isEmpty()?"":r.gates().get(0).lakeName())+","+(r.gates().isEmpty()?"":r.gates().get(r.gates().size()-1).lakeName())+"\n");
   System.out.println("NEW RIVER "+r.name()+" samples="+n+" dryCentre="+dry+" wallsOver8="+bad+" maxWall="+max);
  }
  Files.writeString(out.resolve("new-rivers.csv"),csv);if(done!=33||missing!=0||walls!=0)throw new AssertionError("New river regression: "+done+"/33, dry="+missing+", walls="+walls);
  System.out.println("NEW CONNECTIONS PASS rivers="+done+" samples="+sampled+" dryCentre=0 adjacentWallsOver8=0");
 }
}
