package com.sora.pjr.core;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exhaustive old/new decoder comparison using the same embedded compressed bytes. */
public final class CoastDecodeParity {
  public static void main(String[] args)throws Exception {
    long values=0;int tiles=0;long start=System.nanoTime();
    try(var old=new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()},null)) {
      var decoder=old.loadClass("com.sora.pjr.core.CoastTileCodec").getDeclaredMethod("decode",DataInputStream.class);
      decoder.setAccessible(true);PackedAtlas packs=new PackedAtlas("urban-coast");
      for(String line:Files.readAllLines(Path.of("src/main/resources/assets/pjr/terrain/urban-coast/index.tsv"))) {
        if(line.isBlank()||line.startsWith("#"))continue;
        String[] f=line.split("\\t");int x=Integer.parseInt(f[f.length-2]),y=Integer.parseInt(f[f.length-1]);
        byte[] compressed;try(var in=packs.open(14,x,y)){compressed=in.readAllBytes();}
        short[] expected,actual;
        try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed))))) {
          expected=(short[])decoder.invoke(null,in);
        }
        try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed))))) {
          actual=CoastTileCodec.decode(in);
        }
        if(!Arrays.equals(expected,actual))throw new AssertionError("Coast decoder changed "+x+","+y);
        values+=actual.length;tiles++;
        if(tiles%500==0)System.out.println("verifiedTiles="+tiles);
      }
    }
    System.out.println("PJR performance coast decoder parity PASS\ntiles="+tiles+"\nexactValues="+values+"\nelapsedSeconds="+(System.nanoTime()-start)/1e9);
  }
}
