package com.sora.pjr.roads;

import com.sora.pjr.core.Geo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, bounded regional resource. No world access and no network I/O. */
public final class RoadCatalog {
  public static final String SNAPSHOT="takao-r20-20261020-v1";
  public static final String HASH="8d5b6fa28ef1846398983e2377866022a27e9b99ced96f2a6fbffdf9750e8bfa";
  public record Point(String node,double x,double z,String way,int forward,int reverse,boolean bridge) {}
  public final List<Point> points;
  private RoadCatalog(List<Point> points) {this.points=List.copyOf(points);}
  public static RoadCatalog load() {
    try(var in=RoadCatalog.class.getResourceAsStream("/assets/pjr/roads/takao.tsv")) {
      if(in==null)throw new IOException("Missing PJSR regional road resource");
      byte[] bytes=in.readAllBytes();
      if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(HASH))throw new IOException("PJSR road snapshot checksum mismatch");
      var points=new ArrayList<Point>();
      for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")){
        if(line.isBlank()||line.startsWith("#"))continue;
        String[] f=line.split("\\t");if(f.length!=7)throw new IOException("Bad road row");
        double x=Geo.x(Double.parseDouble(f[1])),z=Geo.z(Double.parseDouble(f[2]));
        int forward=Integer.parseInt(f[4]),reverse=Integer.parseInt(f[5]);
        if(!Double.isFinite(x)||!Double.isFinite(z)||forward<1||reverse<0||forward+reverse>8)throw new IOException("Invalid road point");
        points.add(new Point(f[0],x,z,f[3],forward,reverse,f[6].equals("1")));
      }
      if(points.size()<2||points.size()>4096)throw new IOException("Pilot point budget");
      return new RoadCatalog(points);
    }catch(Exception e){throw new IllegalStateException("PJSR cannot load offline roads",e);}
  }
}
