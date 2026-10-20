package com.sora.pjr.validation;
import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
/** Identical final-column sampling for before/after binaries. */
public final class CoastalWaterPreview {
 public static void main(String[] args)throws Exception {
  WorldEngine e=WorldEngine.load();Path dir=Path.of(args[0]);Files.createDirectories(dir);
  tile(e,dir,"tokyo",-4652,-1620,6540,7980,16);
  tile(e,dir,"specks",1100,1550,2100,2700,2);
  tile(e,dir,"tama",-3500,3500,200,6350,6);
  tile(e,dir,"northern",-2800,-1550,6540,1600,8);
  tile(e,dir,"frame-east",5950,-550,6540,700,2);
  tile(e,dir,"frame-south",-2200,7050,-800,7980,3);
 }
 static void tile(WorldEngine e,Path dir,String name,int x0,int z0,int x1,int z1,int step)throws Exception {
  int w=(x1-x0)/step+1,h=(z1-z0)/step+1;
  try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve(name+".bin"))))) {
   for(int n:new int[]{x0,z0,step,w,h})out.writeInt(n);
   for(int j=0;j<h;j++)for(int i=0;i<w;i++) {
    Geometry g=e.geometry(x0+i*step,z0+j*step);
    out.writeByte(g.kind().ordinal());out.writeShort(g.groundY());out.writeShort(g.wet()?g.waterTop():Short.MIN_VALUE);
   }
  }
  e.releaseThreadCache();System.out.println(name);
 }
}
