package com.sora.pjr.validation;
import com.sora.pjr.core.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
/** Same renderer can execute against the unmodified 0.1.7 JAR as a separate process. */
public final class RepairPreview {
 public static void main(String[] args)throws Exception{
  Path out=Path.of(args[0]);Files.createDirectories(out);WorldEngine e=WorldEngine.load();
  int[][] regions={{19000,-225000,23500,-221000},{-77300,36500,-71400,42500},{19500,-16400,33500,-5300},{-215500,89500,-207500,95500},{-7680,-9700,-7340,-9390},{-1400,-4650,-1000,-4260}};
  String[] names={"jusan","hamana","kasumigaura","kochi","iruma","sumida"};
  for(int n=0;n<regions.length;n++){
   int w=500,h=360;var b=regions[n];BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
   for(int j=0;j<h;j++)for(int i=0;i<w;i++){
    int x=b[0]+(int)((i+.5)*(b[2]-b[0])/w),z=b[1]+(int)((j+.5)*(b[3]-b[1])/h);Geometry g=e.geometry(x,z);int rgb;
    if(n>=4&&g.wet()){
      int d=Math.min(8,g.waterY()-g.groundY());rgb=((135-d*12)<<16)|((205-d*12)<<8)|(231-d*7);
    }else rgb=switch(g.kind()){case OCEAN->0x3a6687;case RIVER,LAKE->0x51b9d1;case DRY->0xc5d5a3;};
    image.setRGB(i,j,rgb);
   }
   ImageIO.write(image,"png",out.resolve(names[n]+".png").toFile());System.out.println(names[n]);
  }
  StringBuilder line=new StringBuilder("x,z,ground,water,kind\n");
  for(int z=2050;z<=2850;z++) {Geometry g=e.geometry(4197,z);line.append(4197+","+z+","+g.groundY()+","+g.waterY()+","+g.kind()+"\n");}
  Files.writeString(out.resolve("coast.csv"),line);
 }
}
