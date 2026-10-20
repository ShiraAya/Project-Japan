package com.sora.pjr.core;

import com.sora.pjr.validation.HydrologyFingerprint;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;

/** Build tool only: run against the pinned baseline JAR or with -Dpjr.compileHydrology=true. */
public final class BakeHydrology {
  public static void main(String[] args)throws Exception {
    long start=System.nanoTime();WorldData data=WorldEngine.load().hydrology().data();
    Path output=Path.of(args[0]);Files.createDirectories(output.toAbsolutePath().getParent());
    try(var out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(output))))) {
      out.writeInt(0x504a5248);out.writeInt(3);out.writeDouble(Geo.SCALE);
      for(String input:PrecomputedHydrology.INPUTS)out.write(PrecomputedHydrology.hashInput(input));
      out.writeInt(data.lakes().size());
      for(Lake l:data.lakes()) {
        out.writeUTF(l.id());out.writeInt(l.waterY());out.writeInt(l.maxDepth());out.writeDouble(l.area());
        out.writeDouble(l.bankWidth());out.writeInt(l.representativeX());out.writeInt(l.representativeZ());
      }
      out.writeInt(data.rivers().size());
      for(River r:data.rivers()) {
        out.writeUTF(r.name());out.writeUTF(r.sourceLake());out.writeUTF(r.terminalLake());
        out.writeUTF(r.sourceRiver());out.writeUTF(r.terminalRiver());out.writeBoolean(r.sea());
        out.writeDouble(r.mouthDistance());out.writeDouble(r.totalLength());HydraulicShape.write(out,r.points());
        out.writeInt(r.profileSize());
        out.writeShort(r.water(0));
        for(int i=0;i<r.profileSize();) {
          int value=r.water(i),end=i+1;while(end<r.profileSize()&&r.water(end)==value)end++;
          if(end<r.profileSize()&&r.water(end)!=value-1)throw new IllegalStateException("Profile drops by more than one block");
          int run=end-i;while(run>=128){out.writeByte((run&127)|128);run>>>=7;}out.writeByte(run);i=end;
        }
        out.writeInt(r.gates().size());
        for(var g:r.gates()){out.writeUTF(g.lakeId());out.writeUTF(g.lakeName());out.writeDouble(g.first());out.writeDouble(g.last());out.writeInt(g.waterY());}
      }
      out.writeInt(data.diagnostics().size());for(String s:data.diagnostics())out.writeUTF(s);
    }
    StringBuilder fingerprint=new StringBuilder();
    for(River r:data.rivers())fingerprint.append("river\t").append(r.name()).append('\t').append(HydrologyFingerprint.river(r)).append('\n');
    for(Lake l:data.lakes())fingerprint.append("lake\t").append(l.id()).append('\t').append(HydrologyFingerprint.lake(l)).append('\n');
    Files.writeString(Path.of(args[1]),fingerprint);
    System.out.println("Exact hydrology baked: "+Files.size(output)+" bytes; seconds="+(System.nanoTime()-start)/1e9);
  }
}
