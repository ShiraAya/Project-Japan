package com.sora.pjr.validation;
import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;
/** Compare all lake identities with the separately executed previous release. */
public final class LakeNetworkValidation {
 private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {verify(WorldEngine.load());}
 public static void verify(WorldEngine engine)throws Exception {
  var data=engine.hydrology().data();Map<String,River> rivers=new HashMap<>();Map<String,Lake> lakes=new HashMap<>();
  for(var r:data.rivers())rivers.put(r.name(),r);
  for(var l:data.lakes()){lakes.put(l.id(),l);lakes.put(l.name(),l);}
  int added=0,declared=0;
  for(String line:Files.readAllLines(Path.of("data/waterway-expansion-0.1.12.tsv"))) {
   if(line.startsWith("river\t"))continue;String[] a=line.split("\t",-1);var r=rivers.get(a[0]);
   require(r!=null,"Missing audited waterway "+a[0]);added++;
   require(r.sourceLake().equals(a[2]) && r.terminalLake().equals(a[3]) && r.terminalRiver().equals(a[4]),"Stale waterway endpoints "+a[0]);
   for(String key:new String[]{a[2],a[3]})if(!key.isEmpty()) {
    var lake=lakes.get(key);require(lake!=null,"Missing referenced lake "+key);
    require(r.gates().stream().anyMatch(g->g.lakeId().equals(lake.id())),"Declared link has no physical lake crossing "+a[0]+" / "+key);declared++;
   }
  }
  require(data.rivers().size()==170+added+24+33 && data.lakes().size()==221,"Complete release network");
  require(rivers.get("Toyohira").sourceRiver().isEmpty(),"False 771 m Toyohira/Chitose source link");
  Map<String,Set<String>> oldIn=new HashMap<>(),oldOut=new HashMap<>(),newIn=new HashMap<>(),newOut=new HashMap<>();
  for(String line:Files.readAllLines(Path.of("data/baseline-lake-gates-0.1.11.csv"))) {
   if(line.startsWith("lake_id,"))continue;String[] a=line.split(",",-1);
   if(Boolean.parseBoolean(a[6]))oldIn.computeIfAbsent(a[0],k->new TreeSet<>()).add(a[2]);
   if(Boolean.parseBoolean(a[7]))oldOut.computeIfAbsent(a[0],k->new TreeSet<>()).add(a[2]);
  }
  for(var r:data.rivers())for(var g:r.gates()) {
   if(g.first()>2)newIn.computeIfAbsent(g.lakeId(),k->new TreeSet<>()).add(r.name());
   if(g.last()<r.totalLength()-2)newOut.computeIfAbsent(g.lakeId(),k->new TreeSet<>()).add(r.name());
  }
  StringBuilder csv=new StringBuilder("lake_id,lake,before_in,before_out,after_in,after_out,direct_ocean_contacts,incoming_rivers,outgoing_rivers\n");
  int connected=0,zeroIn=0,zeroOut=0,isolated=0,directSea=0;
  for(var l:data.lakes()) {
   var incoming=newIn.getOrDefault(l.id(),Set.of());var outgoing=newOut.getOrDefault(l.id(),Set.of());
   int sea=seaContacts(engine,l);if(sea>0)directSea++;
   if(!incoming.isEmpty()||!outgoing.isEmpty())connected++;else isolated++;
   if(incoming.isEmpty())zeroIn++;if(outgoing.isEmpty())zeroOut++;
   csv.append(String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%d,%d,%s,%s%n",l.id(),l.name(),oldIn.getOrDefault(l.id(),Set.of()).size(),oldOut.getOrDefault(l.id(),Set.of()).size(),incoming.size(),outgoing.size(),sea,String.join(";",incoming),String.join(";",outgoing)));
  }
  require(connected>=190,"Lake coverage expansion was not generated");
  int expanded=0;double meanRatio=0;
  for(String line:Files.readAllLines(Path.of("data/baseline-river-widths-0.1.11.csv"))) {
   if(line.startsWith("river,"))continue;String[] a=line.split(",",-1);var r=rivers.get(a[0]);double old=Double.parseDouble(a[4])*Geo.SCALE/0.375;double ratio=r.at(r.totalLength()*.8).width()/old;
   if(ratio>1.01)expanded++;meanRatio+=ratio;
  }
  require(expanded>=100,"Lower reaches remain globally restricted");
  require(meanRatio/170<=1.06,"Requested slight widening exceeded six percent mean at 80% progress");
  String report="PJR lake network validation PASS\noriginalRivers=170\naddedWaterways="+added+"\ntotalRivers="+data.rivers().size()+"\nreviewedLakes=221\nconnectedLakes="+connected+"\nzeroInletLakes="+zeroIn+"\nzeroOutletLakes="+zeroOut+"\nnoRiverContactLakes="+isolated+"\nlakesWithDirectOceanContact="+directSea+"\nverifiedDeclaredLakeEndpoints="+declared+"\nexistingRiversWiderAt80Percent="+expanded+"\nmeanWidthRatioAt80Percent="+(meanRatio/170)+"\nDirect ocean contacts describe generated geometry; zero river counts are not evidence of an endorheic natural lake.\n";
  Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/lake-network-validation.txt"),report);Files.writeString(Path.of("build/reports/lake-network-coverage.csv"),csv);System.out.print(report);
 }
 private static int seaContacts(WorldEngine e,Lake lake) {
  if(lake.waterY()!=0)return 0;var ring=lake.polygon().outer();Set<Long> found=new HashSet<>();
  for(int i=0;i<ring.size();i++) {
   int next=(i+1)%ring.size();double dx=ring.x(next)-ring.x(i),dz=ring.z(next)-ring.z(i);int steps=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)/3));
   for(int k=0;k<=steps;k++) {
    int x=(int)Math.floor(ring.x(i)+dx*k/steps),z=(int)Math.floor(ring.z(i)+dz*k/steps);
    for(int oz=-1;oz<=1;oz++)for(int ox=-1;ox<=1;ox++) {
     var g=e.geometry(x+ox,z+oz);if(g.kind()!=Hydrology.Kind.LAKE || !g.owner().equals(lake.id()))continue;
     for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(e.geometry(x+ox+d[0],z+oz+d[1]).kind()==Hydrology.Kind.OCEAN)found.add(Geo.key(x+ox,z+oz));
    }
   }
  }
  return found.size();
 }
}
