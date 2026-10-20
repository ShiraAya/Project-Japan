import com.sora.pjr.core.*;import java.nio.file.*;import java.io.*;
public class AllLakeShoreAudit{
 public static void main(String[] a)throws Exception{
  var e=WorldEngine.load();Path out=Path.of(a[0]);Files.createDirectories(out);long dry=0,sills=0,bad=0;int max=0,i=0;
  try(var csv=Files.newBufferedWriter(out.resolve("all-lake-shores.csv"))){csv.write("lake,dryShore,emergencySills,unsupported,maxAdjacentStep,excessOver2\n");
   for(var l:e.hydrology().data().lakes()){
    int[] c=new int[5];LakeLevels.dryShore(l.polygon(),key->{int x=(int)(key>>32),z=(int)key;var g=e.geometry(x,z);if(g.wet())return;c[0]++;if(g.containment())c[1]++;if(g.groundY()<l.waterY())c[2]++;
     for(int[] d:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}){var t=e.geometry(x+d[0],z+d[1]);if(t.wet())continue;int s=Math.abs(g.groundY()-t.groundY());c[3]=Math.max(c[3],s);if(s-Math.abs(g.naturalY()-t.naturalY())>2)c[4]++;}
    });
    csv.write(l.name()+","+c[0]+","+c[1]+","+c[2]+","+c[3]+","+c[4]+"\n");csv.flush();dry+=c[0];sills+=c[1];bad+=c[4];max=Math.max(max,c[3]);i++;if(i%40==0)System.out.println("LAKES "+i+" dry="+dry+" emergency="+sills+" excessOver2="+bad);
   }
  }System.out.printf("ALL LAKE SHORE COMPLETE lakes=%d dry=%d emergency=%d maxAdjacentStep=%d excessOver2=%d%n",i,dry,sills,max,bad);
 }
}
