package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Compare all existing profiles and geometric attributes with a separately executed release JAR. */
public final class HydrologyFingerprint {
  public static String river(River r) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeUTF(r.name()); out.writeUTF(r.sourceLake()); out.writeUTF(r.terminalLake());
    out.writeUTF(r.sourceRiver()); out.writeUTF(r.terminalRiver()); out.writeBoolean(r.sea());
    out.writeDouble(r.mouthDistance()); out.writeDouble(r.totalLength());
    for (River.Point p : r.points()) {
      out.writeDouble(p.x()); out.writeDouble(p.z()); out.writeDouble(p.station()); out.writeDouble(p.width());
    }
    for (int s = 0; s < r.profileSize(); s++) out.writeInt(r.water(s));
    for (River.Gate g : r.gates()) {
      out.writeUTF(g.lakeId()); out.writeDouble(g.first()); out.writeDouble(g.last()); out.writeInt(g.waterY());
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
  }

  public static String lake(Lake l) {
    return l.waterY()+","+l.maxDepth()+","+l.basinDepth()+","+Double.toHexString(l.area());
  }

  public static void main(String[] args) throws Exception {
    WorldData data = WorldEngine.load().hydrology().data();
    StringBuilder text = new StringBuilder();
    for (River r : data.rivers()) text.append("river\t").append(r.name()).append('\t').append(river(r)).append('\n');
    for (Lake l : data.lakes()) text.append("lake\t").append(l.id()).append('\t').append(lake(l)).append('\n');
    Files.writeString(Path.of(args[0]), text);
    System.out.println("Fingerprinted " + data.rivers().size()+" rivers and "+data.lakes().size()+" lakes");
  }
}
