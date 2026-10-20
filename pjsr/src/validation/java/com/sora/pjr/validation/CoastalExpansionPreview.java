package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;

/** Final generated ground/water/kind, executable against both release JARs. */
public final class CoastalExpansionPreview {
  public static void main(String[] args)throws Exception {
    Path out=Path.of(args[0]);Files.createDirectories(out);WorldEngine e=WorldEngine.load();
    tile(e,out.resolve("oshima.bin"),139.70,41.32,141.27,42.73,96);
    tile(e,out.resolve("kii.bin"),135.04,33.38,136.38,34.33,64);
    tile(e,out.resolve("tokyo.bin"),139.65,35.505,139.94,35.710,12);
    tile(e,out.resolve("islands.bin"),144.8,43.28,149.15,45.75,160);
    StringBuilder routes=new StringBuilder("river,lon,lat\n");
    for(River r:e.hydrology().data().rivers())for(var p:r.points())
      routes.append(r.name()+","+Geo.lon(p.x())+","+Geo.lat(p.z())+"\n");
    Files.writeString(out.resolve("routes.csv"),routes);
  }
  private static void tile(WorldEngine e,Path file,double west,double south,double east,double north,int step)throws Exception {
    int x0=(int)Math.floor(Geo.x(west)),z0=(int)Math.floor(Geo.z(north));
    int width=((int)Geo.x(east)-x0)/step+1,height=((int)Geo.z(south)-z0)/step+1;
    int expected=20+7*width*height;var bytes=new ByteArrayOutputStream(expected);
    try(var out=new DataOutputStream(bytes)) {
      for(int n:new int[]{x0,z0,step,width,height})out.writeInt(n);
      for(int j=0;j<height;j++)for(int i=0;i<width;i++) {
        var g=e.geometry(x0+i*step,z0+j*step);
        out.writeShort(g.groundY());out.writeShort(g.wet()?g.waterTop():Short.MIN_VALUE);
        out.writeShort(g.naturalY());out.writeByte(g.kind().ordinal());
      }
    }
    if(bytes.size()!=expected)throw new IOException("Incomplete preview");
    Path temp=file.resolveSibling(file.getFileName()+".part");Files.write(temp,bytes.toByteArray());
    if(Files.size(temp)!=expected)throw new IOException("Incomplete output");
    Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);System.out.println(file.getFileName());e.releaseThreadCache();
  }
}
