import com.sora.pjr.core.*;
import java.util.*;import java.nio.file.*;import java.io.*;
public class IncisionAudit {
 static WorldEngine e;static Hydrology h;
 static final int[][] FOCUS={{-81038,4194},{-175358,71342},{-139167,-11753}};
 public static void main(String[] a)throws Exception {
  e=WorldEngine.load();h=e.hydrology();Path out=Path.of(a[0]);Files.createDirectories(out);
  for(int[] f:FOCUS){
   River.Hit near=null;for(var s:h.indexedSegmentsAt(f[0],f[1])){var hit=s.nearest(f[0],f[1]);if(near==null||hit.distance()<near.distance())near=hit;}
   if(near==null)throw new AssertionError("No river at focus");
   var r=near.river();System.out.println("FOCUS "+Arrays.toString(f)+" "+r.name()+" station="+near.station()+" gates="+r.gates());
   StringBuilder csv=new StringBuilder("station,x,z,water,natural,side,offset,bankNatural,bankGround,bankWater,kind,nearestLake,lakeLevel,lakeDistance\n");
   for(double s=Math.max(0,near.station()-1000);s<Math.min(r.totalLength(),near.station()+1000);s+=8){
    var p=r.at(s);var aa=r.at(s-2);var b=r.at(s+2);double length=Math.hypot(b.x()-aa.x(),b.z()-aa.z()),nx=-(b.z()-aa.z())/length,nz=(b.x()-aa.x())/length;
    int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());int natural=h.terrain().sample(x,z).ground();
    for(int side:new int[]{-1,1})for(int offset:new int[]{1,4,8,16,32,64}){
     double d=p.width()/2+.75+offset;int bx=(int)Math.floor(p.x()+side*nx*d),bz=(int)Math.floor(p.z()+side*nz*d);
     var g=e.geometry(bx,bz);var c=h.sample(bx,bz);var l=c.shore();
     csv.append(String.format(Locale.ROOT,"%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%d,%.2f%n",s,x,z,r.water(s),natural,side,offset,g.naturalY(),g.groundY(),g.waterY(),g.kind(),l==null?"":l.lake().name(),l==null?0:l.lake().waterY(),l==null?99999:l.distance()));
    }
   }
   Files.writeString(out.resolve("focus-"+f[0]+".csv"),csv);
   int n=321,step=4,x0=f[0]-n*step/2,z0=f[1]-n*step/2;
   try(var d=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("map-"+f[0]+".bin"))))){d.writeInt(n);d.writeInt(step);d.writeInt(x0);d.writeInt(z0);for(int z=z0;z<z0+n*step;z+=step)for(int x=x0;x<x0+n*step;x+=step){var g=e.geometry(x,z);d.writeShort(g.naturalY());d.writeShort(g.groundY());d.writeShort(g.wet()?g.waterY():-30000);d.writeByte(g.kind().ordinal());}}
  }
  System.out.println("FOCUS COMPLETE");
  if(a.length>1&&a[1].equals("focus"))return;
  StringBuilder csv=new StringBuilder("river,samples,dryBankSamples,highBanksOver8,maxBankWall,worstX,worstZ,water,bank,nearLake\n");
  int rivers=0;long all=0,high=0;
  for(var r:h.data().rivers()){
   int samples=0,dry=0,bad=0,worst=0,wx=0,wz=0,wy=0,bg=0;String lake="";
   for(double s=0;s<r.totalLength();s+=32){samples++;var p=r.at(s);var a0=r.at(s-2);var b=r.at(s+2);double length=Math.hypot(b.x()-a0.x(),b.z()-a0.z());if(length==0)continue;
    double nx=-(b.z()-a0.z())/length,nz=(b.x()-a0.x())/length;
    for(int side:new int[]{-1,1}){
     double d=p.width()/2+2;int x=(int)Math.floor(p.x()+side*nx*d),z=(int)Math.floor(p.z()+side*nz*d);var g=e.geometry(x,z);if(g.wet())continue;
     // Compare only to adjacent actual water, not a non-local centreline owner.
     int local=Integer.MIN_VALUE;for(int[] v:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}}){var w=h.sample(x+v[0],z+v[1]).water();if(w.kind()==Hydrology.Kind.RIVER)local=Math.max(local,w.top());}
     if(local==Integer.MIN_VALUE)continue;dry++;int wall=g.groundY()-local;if(wall>8)bad++;
     if(wall>worst){worst=wall;wx=x;wz=z;wy=local;bg=g.groundY();var shore=h.sample(x,z).shore();lake=shore==null?"":shore.lake().name();}
    }
   }
   csv.append(String.format(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",r.name(),samples,dry,bad,worst,wx,wz,wy,bg,lake));
   rivers++;all+=dry;high+=bad;if(rivers%50==0)System.out.println("AUDIT "+rivers+"/389");
  }
  Files.writeString(out.resolve("all-river-banks.csv"),csv);System.out.println("BANK AUDIT COMPLETE rivers="+rivers+" dryBanks="+all+" wallsOver8="+high);
  if(a.length>1&&a[1].equals("verify")) {
   if(rivers!=389||all<500000||high!=0)throw new AssertionError("Incomplete or failed river bank regression");
   System.out.println("INCISION AUDIT PASS");
  }
 }
}
