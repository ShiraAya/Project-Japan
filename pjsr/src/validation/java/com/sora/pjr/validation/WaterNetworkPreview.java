package com.sora.pjr.validation;
import com.sora.pjr.core.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
/** Export actual generated water ownership at identical coordinates across releases. */
public final class WaterNetworkPreview {
 public static void main(String[] args)throws Exception {
  var e=WorldEngine.load();Path dir=Path.of(args[0]);Files.createDirectories(dir);
  tile(e,dir,"tokyo-bay",-8500,-3000,14000,24000,24);
  tile(e,dir,"tama",-2700,2500,500,5100,4);
  tile(e,dir,"arakawa",0,-1500,6400,2200,8);
 }
 static void tile(WorldEngine e,Path dir,String name,int x0,int z0,int x1,int z1,int step)throws Exception {
  var im=new BufferedImage((x1-x0)/step+1,(z1-z0)/step+1,BufferedImage.TYPE_INT_RGB);
  for(int j=0;j<im.getHeight();j++)for(int i=0;i<im.getWidth();i++) {
   var g=e.geometry(x0+i*step,z0+j*step);int rgb;
   if(g.kind()==Hydrology.Kind.OCEAN)rgb=0x7297a9;
   else if(g.wet())rgb=0x44a2cc;
   else {int sh=(int)Math.min(50,Math.max(0,g.groundY())*.15);rgb=((183-sh)<<16)|((197-sh)<<8)|(148-sh);}
   im.setRGB(i,j,rgb);
  }
  ImageIO.write(im,"png",dir.resolve(name+".png").toFile());
 }
}
