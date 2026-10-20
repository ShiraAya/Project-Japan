import com.sora.pjr.core.*;import java.nio.file.*;import java.util.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;
public class CoastSpurAudit {
 public static void main(String[]a)throws Exception {
  RasterTerrain t=new RasterTerrain();Path out=Path.of(a[0]);Files.createDirectories(out);
  BufferedImage map=new BufferedImage(800,800,BufferedImage.TYPE_INT_RGB);
  for(int py=0;py<800;py++)for(int px=0;px<800;px++) {
   int x=2945+(px-400)*6,z=3808+(py-400)*6;
   map.setRGB(px,py,t.sample(x,z).land()?0xc6d3a7:0x709eb3);
  }
  ImageIO.write(map,"png",out.resolve("coast-feedback.png").toFile());
  if(a.length<2)return;
  int total=0,land=0,near=0,nearLand=0;
  Map<String,double[]> bounds=new HashMap<>();for(String line:Files.readAllLines(Path.of(a[2]))){if(line.isBlank()||line.startsWith("#"))continue;String[]f=line.split("\t");bounds.put(f[0],new double[]{Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4])});}
  int boundary=0,boundaryLand=0;
  for(String line:Files.readAllLines(Path.of(a[1]))){if(line.startsWith("x,"))continue;String[] f=line.split(",");int x=Integer.parseInt(f[0]),z=Integer.parseInt(f[1]);double[] b=bounds.get(f[2]);double lon=Geo.lon(x),lat=Geo.lat(z);double edge=Math.min(Math.min((lon-b[0])*Geo.DEG_LON,(b[2]-lon)*Geo.DEG_LON),Math.min((lat-b[1])*Geo.DEG_LAT,(b[3]-lat)*Geo.DEG_LAT));boolean dry=t.sample(x,z).land();
   if(edge>=512){total++;if(dry)land++;}else{boundary++;if(dry)boundaryLand++;}
   if(Math.abs(x-2945)<2000&&Math.abs(z-3808)<2000){near++;if(dry)nearLand++;}
  }
  System.out.printf("fullAuthorityProbes=%d landRemaining=%d boundaryProbes=%d boundaryLand=%d screenshotAreaProbes=%d screenshotAreaLand=%d%n",total,land,boundary,boundaryLand,near,nearLand);
  if(land!=0||near<50||nearLand!=0)throw new AssertionError("Harbour spur removal failed");
  System.out.println("COAST SPUR AUDIT PASS");
 }
}
