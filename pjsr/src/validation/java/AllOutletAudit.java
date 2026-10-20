import com.sora.pjr.core.*;
import java.util.*;import java.io.*;import java.nio.file.*;
/** Sample the full dry slopes, including outer apron seams, around every modeled descending lake outlet. */
public class AllOutletAudit {
 public static void main(String[] args)throws Exception{
  WorldEngine e=WorldEngine.load();Hydrology h=e.hydrology();Path out=Path.of(args[0]);Files.createDirectories(out);
  long total=0,bad=0;int active=0,all=0,worst=0;
  try(var csv=Files.newBufferedWriter(out.resolve("all-outlets.csv"))){
   csv.write("river,lake,gateStation,dropWithin1536,dryPairs,excessOver2,maxAdjacentStep,maxExcess,worstX,worstZ,direction,naturalA,naturalB,groundA,groundB\n");
   for(var r:h.data().rivers())for(var gate:r.gates()){
    all++;double end=Math.min(r.totalLength(),gate.last()+1536);int drop=gate.waterY()-r.water(end);
    if(end-gate.last()<2||drop<=1)continue;active++;
    int samples=0,b=0,max=0,mex=0,wx=0,wz=0,wd=0,na=0,nb=0,ga=0,gb=0;
    for(double s=Math.max(0,gate.last()-32);s<=end;s+=24){
     var p=r.at(s);var a=r.at(s-2);var t=r.at(s+2);double len=Math.hypot(t.x()-a.x(),t.z()-a.z());if(len<.01)continue;
     double nx=-(t.z()-a.z())/len,nz=(t.x()-a.x())/len;
     for(int side=-1;side<=1;side+=2)for(int offset=0;offset<=768;offset+=12){
      double d=p.width()/2+.75+offset;int x=(int)Math.floor(p.x()+side*nx*d),z=(int)Math.floor(p.z()+side*nz*d);var g=e.geometry(x,z);if(g.wet())continue;
      for(int dir=0;dir<2;dir++){var other=e.geometry(x+(dir==0?1:0),z+(dir==1?1:0));if(other.wet())continue;
       samples++;int step=Math.abs(g.groundY()-other.groundY()),natural=Math.abs(g.naturalY()-other.naturalY()),excess=step-natural;
       if(excess>2)b++;max=Math.max(max,step);
       if(excess>mex){mex=excess;wx=x;wz=z;wd=dir;na=g.naturalY();nb=other.naturalY();ga=g.groundY();gb=other.groundY();}
      }
     }
    }
    total+=samples;bad+=b;worst=Math.max(worst,mex);
    csv.write(String.format(Locale.ROOT,"%s,%s,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",r.name(),gate.lakeName(),gate.last(),drop,samples,b,max,mex,wx,wz,wd,na,nb,ga,gb));csv.flush();
    if(active%20==0)System.out.printf("OUTLETS %d dryPairs=%d excessOver2=%d worstExcess=%d%n",active,total,bad,worst);
   }
  }
  System.out.printf("OUTLET SCAN COMPLETE gates=%d descendingOutlets=%d dryPairs=%d excessOver2=%d maxExcess=%d%n",all,active,total,bad,worst);
 }
}
