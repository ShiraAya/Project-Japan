package com.sora.pjr.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Source DEM detail and surveyed urban land. A coastline never creates inland water bodies. */
final class PrecisionTerrain {
  private final TiledRaster dem=new TiledRaster("dem-atlas",256,1,128);
  private final TiledRaster coast=new TiledRaster("urban-coast",512,2,64);
  private record Region(double west,double south,double east,double north) {
    double weight(double lon,double lat) {
      double edge=Math.min(Math.min((lon-west)*Geo.DEG_LON,(east-lon)*Geo.DEG_LON),
          Math.min((lat-south)*Geo.DEG_LAT,(north-lat)*Geo.DEG_LAT));
      return Geo.smooth(0,512,edge);
    }
  }
  private final List<Region> regions=new ArrayList<>();
  record Coast(double seaDistance,double waterDistance,double weight) {}
  private static final class LastCoast { int x,z;boolean valid;Coast value; }
  private final ThreadLocal<LastCoast> lastCoast=ThreadLocal.withInitial(LastCoast::new);
  PrecisionTerrain() {
    try(var raw=PrecisionTerrain.class.getResourceAsStream("/assets/pjr/terrain/urban-coast/regions.tsv")) {
      if(raw==null)throw new IOException("Missing urban coverage");
      try(var in=new BufferedReader(new InputStreamReader(raw,StandardCharsets.UTF_8))) {
        for(String line;(line=in.readLine())!=null;) {
          if(line.isBlank()||line.startsWith("#"))continue;
          String[] f=line.split("\\t");regions.add(new Region(Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4])));
        }
      }
    } catch(IOException e){throw new IllegalStateException("Cannot load urban coverage",e);}
  }
  private double weight(double lon,double lat) {
    double value=0;for(Region r:regions)value=Math.max(value,r.weight(lon,lat));return value;
  }
  double elevation(int x,int z,double fallback) {
    double lon=Geo.lon(x),lat=Geo.lat(z),mx=(lon+180)/360,my=TiledRaster.mercatorY(lat);
    double v=dem.sample(12,mx*(256<<12)-.5,my*(256<<12)-.5,0,fallback*2);
    double base=Double.isFinite(v)?v/2:fallback,w=weight(lon,lat);
    if(w>0) {
      v=dem.sample(14,mx*(256<<14)-.5,my*(256<<14)-.5,0,base*2);
      if(Double.isFinite(v))base=Double.isFinite(base)?base+(v/2-base)*w:v/2;
    }
    return base;
  }
  Coast coast(int x,int z) {
    LastCoast last=lastCoast.get();
    if(last.valid&&last.x==x&&last.z==z)return last.value;
    Coast result=sampleCoast(x,z);
    last.x=x;last.z=z;last.value=result;last.valid=true;
    return result;
  }
  private Coast sampleCoast(int x,int z) {
    double lon=Geo.lon(x),lat=Geo.lat(z),w=weight(lon,lat);
    if(w==0)return null;
    double px=(lon+180)/360*(512<<14)-.5,py=TiledRaster.mercatorY(lat)*(512<<14)-.5;
    double sea=coast.sample(14,px,py,0),water=coast.sample(14,px,py,1);
    if(!Double.isFinite(sea+water))return null;
    return new Coast(sea/8*Geo.SCALE,water/8*Geo.SCALE,w);
  }
}
