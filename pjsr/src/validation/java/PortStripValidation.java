import com.sora.pjr.core.*;import java.nio.file.*;import java.util.*;
public class PortStripValidation{public static void main(String[]a)throws Exception{
 var t=new RasterTerrain();Map<String,double[]> regions=new HashMap<>();for(String l:Files.readAllLines(Path.of(a[1]))){if(l.isBlank()||l.startsWith("#"))continue;String[]f=l.split("\t");regions.put(f[0],new double[]{Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4])});}
 int total=0,interior=0,border=0,retainedBorderLand=0,fail=0,feedback=0;
 for(String line:Files.readAllLines(Path.of(a[0]))){if(line.startsWith("x,"))continue;String[]f=line.split(",");int x=Integer.parseInt(f[0]),z=Integer.parseInt(f[1]);double[] r=regions.get(f[2]);double lon=Geo.lon(x),lat=Geo.lat(z);double edge=Math.min(Math.min((lon-r[0])*Geo.DEG_LON,(r[2]-lon)*Geo.DEG_LON),Math.min((lat-r[1])*Geo.DEG_LAT,(r[3]-lat)*Geo.DEG_LAT));boolean land=t.sample(x,z).land();total++;
 if(edge>=512){interior++;if(land)fail++;}else{border++;if(land)retainedBorderLand++;}
 if(Math.hypot(x-5753,z-17026)<1000||Math.hypot(x+4212,z-19884)<1000){feedback++;if(land)throw new AssertionError("Screenshot fragment still dry "+x+","+z);}
 }
 if(fail!=0||feedback==0)throw new AssertionError("Interior harbour removal failed");
 System.out.println("PORT STRIP VALIDATION PASS\nmaskChangedNodes="+total+"\nfullAuthorityProbes="+interior+"\nfullAuthorityLandRemaining="+fail+"\nregionalBlendBandProbes="+border+"\nregionalBlendBandLandRetained="+retainedBorderLand+"\nscreenshotAreaProbes="+feedback+"\nThe existing 512-m regional transition intentionally blends with the national coastline; no claim that every changed border-mask pixel is an ocean block.");
}}
