package com.sora.pjr.core;

import java.nio.file.*;

/** Checks compact decoding separately from the deliberately reduced DEM sampling precision. */
public final class PrecisionTerrainValidation {
  public static void main(String[] args)throws Exception {
    PrecisionTerrain p=new PrecisionTerrain();int count=0,finite=0;double maxSourceError=0;
    for(String line:Files.readAllLines(Path.of("data/compact-dem-probes.tsv"))) {
      if(line.isBlank()||line.startsWith("#"))continue;
      String[] f=line.split("\\t");int x=Integer.parseInt(f[0]),z=Integer.parseInt(f[1]);double expected=Double.parseDouble(f[2]);
      double actual=p.elevation(x,z,Double.NaN),original=Double.parseDouble(f[3]);
      if(Double.isFinite(actual)!=Double.isFinite(expected)||Double.isFinite(expected)&&Math.abs(actual-expected)>1e-6)throw new AssertionError("Compact DEM decoder mismatch "+x+","+z+" expected="+expected+" actual="+actual);
      if(Double.isFinite(actual)){finite++;maxSourceError=Math.max(maxSourceError,Math.abs(actual-original));}
      count++;
    }
    if(count<100)throw new AssertionError("Insufficient independent DEM coverage");
    java.util.Properties format=new java.util.Properties();
    try(var in=PrecisionTerrainValidation.class.getResourceAsStream("/assets/pjr/terrain/dem-atlas/format.properties")){format.load(in);}
    PackedAtlas packs=new PackedAtlas("dem-atlas");long retained=0;int tiles=0;
    java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
    for(String line:Files.readAllLines(Path.of("data/compact-dem-node-hashes.tsv"))) {
      if(line.isBlank()||line.startsWith("#"))continue;String[] f=line.split("\\t");int z=Integer.parseInt(f[0]),x=Integer.parseInt(f[1]),y=Integer.parseInt(f[2]);short[] a;
      try(var in=new java.io.DataInputStream(new java.util.zip.GZIPInputStream(packs.open(z,x,y)))){a=CompactDemCodec.decode(in,Integer.parseInt(format.getProperty("size"+z)),Integer.parseInt(format.getProperty("quantum"+z)));}
      java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocate(a.length*2);bytes.asShortBuffer().put(a);
      if(!java.util.HexFormat.of().formatHex(digest.digest(bytes.array())).equals(f[3]))throw new AssertionError("Retained DEM node mismatch: "+z+"/"+x+"/"+y);
      retained+=a.length;tiles++;
    }
    if(tiles!=11324)throw new AssertionError("Compact DEM inventory changed");
    int coastCount=0;
    for(String line:Files.readAllLines(Path.of("data/precision-coast-probes.tsv"))) {
      if(line.isBlank()||line.startsWith("#"))continue;
      String[] f=line.split("\\t");int x=Integer.parseInt(f[1]),z=Integer.parseInt(f[2]),kind=Integer.parseInt(f[3]);
      var c=p.coast(x,z);
      if(c==null || (c.seaDistance()<0)!=(kind==1) || (c.waterDistance()<0)!=(kind!=0))
        throw new AssertionError("Coastal source-mask mismatch "+f[0]);
      coastCount++;
    }
    TiledRaster bounded=new TiledRaster("dem-atlas",256,1,128);int visited=0;
    try(var input=PrecisionTerrainValidation.class.getResourceAsStream("/assets/pjr/terrain/dem-atlas/index.tsv");
        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input))) {
      for(String line;(line=reader.readLine())!=null && visited<150;) {
        if(line.startsWith("#"))continue;String[] f=line.split("\\t");
        bounded.sample(Integer.parseInt(f[0]),Integer.parseInt(f[1])*256.+100,Integer.parseInt(f[2])*256.+100,0);visited++;
      }
    }
    if(bounded.cachedCount()!=128 || coastCount<100)throw new AssertionError("Cache/coverage bounds");
    String report="PJR precision atlas validation PASS\noriginalGsiRgbSamples="+count+"\nfiniteCompactProbes="+finite+"\ncoastalMaskSamples="+coastCount+"\nexactRetainedDemNodes="+retained+"\nvalidatedDemTiles="+tiles+"\nboundedDemCache=128/150 visited tiles\nruntimeDecoderMaximumErrorMetres=0.000001\nmaximumOriginalProbeDifferenceMetres="+maxSourceError+"\nCompact DEM is resampled: original-source differences are reported, not mislabelled as lossless.\nAll retained node hashes, independent interpolation expectations, original coastal classification and bounded caching verified.\n";
    Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/precision-atlas-validation.txt"),report);System.out.print(report);
  }
}
