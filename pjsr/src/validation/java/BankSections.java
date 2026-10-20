import com.sora.pjr.core.*;import java.nio.file.*;import java.util.*;
public class BankSections {
 public static void main(String[]a)throws Exception {
  var e=WorldEngine.load();var h=e.hydrology();Path out=Path.of(a[0]);Files.createDirectories(out);
  for(int[]f:new int[][]{{-80903,4139},{-175276,71340},{-139137,-11680}}){
   River.Hit best=null;for(var seg:h.indexedSegmentsAt(f[0],f[1])){var q=seg.nearest(f[0],f[1]);if(best==null||q.distance()<best.distance())best=q;}
   var r=best.river();var p=r.at(best.station());var aa=r.at(best.station()-2);var b=r.at(best.station()+2);double length=Math.hypot(b.x()-aa.x(),b.z()-aa.z());double nx=-(b.z()-aa.z())/length,nz=(b.x()-aa.x())/length;
   StringBuilder csv=new StringBuilder("offset,x,z,natural,ground,water,kind\n");
   for(int d=-160;d<=160;d++){int x=(int)Math.floor(p.x()+nx*d),z=(int)Math.floor(p.z()+nz*d);var g=e.geometry(x,z);csv.append(d+","+x+","+z+","+g.naturalY()+","+g.groundY()+","+(g.wet()?g.waterY():-30000)+","+g.kind()+"\n");}
   Files.writeString(out.resolve("section-"+f[0]+".csv"),csv);System.out.println(r.name()+" @ "+p.x()+","+p.z());
  }
 }
}
