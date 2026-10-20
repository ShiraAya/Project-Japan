package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;

/** Export final engine columns; also runs unchanged against the original 0.1.8 release JAR. */
public final class EstuaryReservoirPreview {
  public static void main(String[] args)throws Exception {
    Path out=Path.of(args[0]);Files.createDirectories(out);
    WorldEngine e=WorldEngine.load();
    tile(e,out.resolve("mouth.bin"),-40,1250,350,1590,1);
    tile(e,out.resolve("sameura.bin"),-211120,80040,-210560,80520,2);
    tile(e,out.resolve("kochi.bin"),-228000,86800,-201000,97000,48);
    River sumida=e.hydrology().data().rivers().stream().filter(r->r.name().equals("Sumida")).findFirst().orElseThrow();
    StringBuilder section=new StringBuilder("station,x,z,ground,water,kind\n");
    for(int k=-200;k<=50;k++) {
      River.Point p=sumida.at(sumida.totalLength()+Math.min(0,k));
      if(k>0) {var a=sumida.at(sumida.totalLength()-8);double dx=p.x()-a.x(),dz=p.z()-a.z(),d=Math.hypot(dx,dz);p=new River.Point(p.x()+dx/d*k,p.z()+dz/d*k,0,0);}
      Geometry g=e.geometry((int)Math.floor(p.x()),(int)Math.floor(p.z()));
      section.append(k+","+g.x()+","+g.z()+","+g.groundY()+","+g.waterY()+","+g.kind()+"\n");
    }
    Files.writeString(out.resolve("mouth-section.csv"),section);
    StringBuilder routes=new StringBuilder("river,lon,lat\n");
    for(River r:e.hydrology().data().rivers())for(var p:r.points())
      routes.append(r.name()+","+Geo.lon(p.x())+","+Geo.lat(p.z())+"\n");
    Files.writeString(out.resolve("routes.csv"),routes);
  }

  private static void tile(WorldEngine e,Path file,int x0,int z0,int x1,int z1,int step)throws Exception {
    var bytes=new ByteArrayOutputStream();
    int expected=20+7*((x1-x0)/step+1)*((z1-z0)/step+1);
    try(var out=new DataOutputStream(bytes)) {
      int width=(x1-x0)/step+1,height=(z1-z0)/step+1;
      for(int i:new int[]{x0,z0,step,width,height})out.writeInt(i);
      for(int j=0;j<height;j++)for(int i=0;i<width;i++) {
        Geometry g=e.geometry(x0+i*step,z0+j*step);
        out.writeShort(g.groundY());out.writeShort(g.wet()?g.waterTop():Short.MIN_VALUE);
        out.writeShort(g.naturalY());out.writeByte(g.kind().ordinal());
      }
    }
    if(bytes.size()!=expected)throw new IOException("Incomplete preview export");
    Path staged=file.resolveSibling(file.getFileName()+".part");
    Files.write(staged,bytes.toByteArray());
    if(Files.size(staged)!=expected)throw new IOException("Incomplete preview file");
    Files.move(staged,file,StandardCopyOption.REPLACE_EXISTING);
    System.out.println(file.getFileName());e.releaseThreadCache();
  }
}
