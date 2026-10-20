import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;
public class WaterShoreAudit{
 public static void main(String[] a)throws Exception{
  var e=WorldEngine.load();var h=e.hydrology();Path out=Path.of(a[0]);Files.createDirectories(out);
  StringBuilder csv=new StringBuilder("id,name,rawElevation,waterY,shoreCells,belowWater,over2Blocks,over8Blocks,minGround,p10Ground,medianGround,p90Ground,maxGround,dryShore,emergencySills,unsupportedFinalShore,maxShoreStep\n");
  var raw=RawGeography.load();
  for(int i=0;i<h.data().lakes().size();i++){
   var l=h.data().lakes().get(i);boolean isNew=i>=205;
   if(!isNew && !l.name().contains("小渋") && Math.hypot(l.representativeX()+80332,l.representativeZ()-3638)>12000)continue;
   var heights=new ArrayList<Integer>();int[] count={0,0,0,0,0,0,0};
   LakeLevels.dryShore(l.polygon(),c->{int x=(int)(c>>32),z=(int)c;int g=h.terrain().sample(x,z).ground();heights.add(g);var geometry=e.geometry(x,z);if(!geometry.wet()){count[3]++;if(geometry.containment())count[4]++;if(geometry.groundY()<l.waterY())count[5]++;
     for(int[] delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){var adjacent=e.geometry(x+delta[0],z+delta[1]);if(!adjacent.wet())count[6]=Math.max(count[6],Math.abs(geometry.groundY()-adjacent.groundY()));}}
     int d=l.waterY()-g;if(d>0)count[0]++;if(d>2)count[1]++;if(d>8)count[2]++;});Collections.sort(heights);int n=heights.size();
   if(a.length>1 && a[1].equals("verify") && isNew && (count[4]!=0 || count[5]!=0 || count[6]>1))throw new AssertionError("New lake shore regression "+l.name()+" "+Arrays.toString(count));
   csv.append(String.format(Locale.ROOT,"%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",l.id(),l.name(),raw.lakes().get(i).elevationMetres(),l.waterY(),n,count[0],count[1],count[2],heights.get(0),heights.get(n/10),heights.get(n/2),heights.get(n*9/10),heights.get(n-1),count[3],count[4],count[5],count[6]));
  }
  Files.writeString(out.resolve("shore-audit.csv"),csv);System.out.print(csv);
  System.out.println("SHORE AUDIT COMPLETE");
  StringBuilder riverCsv=new StringBuilder("river,sourceY,mouthY,sourceWidth,mouthWidth,parent,gateCount,samples,drySamples\n");
  long total=0;int reviewed=0;
  var names=new HashSet<String>();for(var r:raw.rivers().subList(332,356))names.add(r.name());names.add("小渋川");
  for(var r:h.data().rivers())if(names.contains(r.name())){
   int samples=0,dry=0;for(double station=0;station<=r.totalLength();station+=8){var point=r.at(station);samples++;if(!h.sample((int)Math.floor(point.x()),(int)Math.floor(point.z())).water().wet())dry++;}
   riverCsv.append(String.format(Locale.ROOT,"%s,%d,%d,%.3f,%.3f,%s,%d,%d,%d%n",r.name(),r.water(0),r.water(r.totalLength()),r.at(0).width(),r.at(r.totalLength()).width(),r.terminalRiver(),r.gates().size(),samples,dry));
   if(dry!=0)throw new AssertionError("Dry new river "+r.name());reviewed++;total+=samples;
  }
  if(reviewed!=25)throw new AssertionError("Incomplete river review");Files.writeString(out.resolve("new-river-audit.csv"),riverCsv);
  StringBuilder connections=new StringBuilder("lake_id,lake,riverGates,connectionStatus\n");
  for(var l:h.data().lakes().subList(205,221)){List<String> links=new ArrayList<>();for(var r:h.data().rivers())for(var g:r.gates())if(g.lakeId().equals(l.id()))links.add(r.name());connections.append(l.id()+","+l.name()+","+String.join(";",links)+","+(links.isEmpty()?"NO_MODELED_RIVER_CONTACT":"CONNECTED")+"\n");}
  Files.writeString(out.resolve("new-lake-connections.csv"),connections);
  System.out.println("RIVER AUDIT PASS reviewed="+reviewed+" samples="+total+" dry=0");
  if(a.length>1 && a[1].equals("verify"))System.out.println("NEW WATER AUDIT PASS: 24 added rivers, 16 added lakes, Koshubu river/lake; graded dry shores supported without emergency sills.");
  for(int z=2638;z<=4638;z+=250)for(int x=-81332;x<=-79332;x+=250){var c=h.sample(x,z);if(c.water().wet())System.out.printf("POINT %d,%d %s %s bed=%d water=%d ground=%d%n",x,z,c.water().kind(),c.water().owner(),c.water().bed(),c.water().level(),c.terrain().ground());}
  for(var r:h.data().rivers())if(r.name().contains("小渋"))System.out.println("RIVER "+r.name()+" "+r.totalLength()+" source="+r.sourceLake()+" target="+r.terminalLake());
 }
}
