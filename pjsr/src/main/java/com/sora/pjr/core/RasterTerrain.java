package com.sora.pjr.core;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** Immutable rasters, land-aware interpolation, no process-wide query cache. */
public final class RasterTerrain implements Terrain {
  private final int width, height;
  private final byte[] land;
  private final byte[] elevationMask;
  private final short[] elevation;
  private final Coastline coast;
  private final LocalElevation local;
  private final CoastalRegions coastal;
  private final PrecisionTerrain precision;

  public RasterTerrain() {
    BufferedImage h = load("japan_height.png"), l = load("japan_land_ocean.png"), original = load("japan_land_hd.png");
    width = h.getWidth();
    height = h.getHeight();
    if (width != 8192 || height != 6656 || l.getWidth() != width || l.getHeight() != height)
      throw new IllegalStateException("PJR raster dimensions do not match the projection manifest");
    elevation = new short[width * height];
    land = new byte[width * height];
    elevationMask = new byte[width * height];
    int[] row = new int[width];
    for (int y = 0; y < height; y++) {
      h.getRaster().getSamples(0, y, width, 1, 0, row);
      for (int x = 0; x < width; x++) elevation[y * width + x] = (short) row[x];
      l.getRaster().getSamples(0, y, width, 1, 0, row);
      for (int x = 0; x < width; x++) land[y * width + x] = (byte) row[x];
      original.getRaster().getSamples(0,y,width,1,0,row);
      for(int x=0;x<width;x++) elevationMask[y*width+x]=(byte)row[x];
    }
    h.flush();
    l.flush();
    original.flush();
    coast = new Coastline();
    local = new LocalElevation();
    coastal = new CoastalRegions();
    precision = new PrecisionTerrain();
  }

  @Override public boolean elevationSupported(int x,int z) {
    if(Double.isFinite(precision.elevation(x,z,Double.NaN)))return sample(x,z).land();
    CoastalRegions.Region region=coastal.at(x,z);
    if(region!=null && region.blend(x,z)>0) {
      if(!sample(x,z).land()) return false;
      if(region.elevationSupported(x,z)) return true;
    }
    double px=(Geo.lon(x)-122)/32*(width-1), pz=(46-Geo.lat(z))/26*(height-1);
    if(px<0||pz<0||px>=width-1||pz>=height-1)return false;
    int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz;
    double a=(elevationMask[iz*width+ix]&255)*(1-tx)+(elevationMask[iz*width+ix+1]&255)*tx;
    double b=(elevationMask[(iz+1)*width+ix]&255)*(1-tx)+(elevationMask[(iz+1)*width+ix+1]&255)*tx;
    return a*(1-tz)+b*tz>=127.5;
  }

  @Override public boolean tidalChannel(int x,int z) {
    CoastalRegions.Region region=coastal.at(x,z);
    return region!=null && region.tidal(x,z);
  }

  @Override public double channelAuthority(int x,int z) {
    var urban=precision.coast(x,z);
    if(urban!=null)return urban.weight()*(1-Geo.smooth(6,16,precision.elevation(x,z,baseSample(x,z).metres())));
    CoastalRegions.Region region=coastal.at(x,z);
    return region==null?0:region.channelAuthority(x,z);
  }

  @Override public boolean mappedRiver(String name) {
    // The urban atlas contains coastline/all-water masks, not a replacement
    // hydraulic channel with a continuous longitudinal grade. Its dry pixels
    // include bridges, bars and survey gaps: they must not erase the W05 river.
    // The old Tokyo tidal replacement was retired in 0.1.14.
    return false;
  }

  @Override public double surveyedTidalAuthority(int x,int z) {
    if(precision.coast(x,z)!=null)return 0;
    CoastalRegions.Region region=coastal.at(x,z);
    return region==null?0:region.channelAuthority(x,z);
  }

  @Override public double channelShoreDistance(int x,int z) {
    var urban=precision.coast(x,z);
    if(urban!=null)return urban.waterDistance();
    CoastalRegions.Region region=coastal.at(x,z);
    return region==null || region.channelAuthority(x,z)==0
        ?Double.POSITIVE_INFINITY:region.waterDistance(x,z);
  }

  @Override public double coastDistance(int x, int z) {
    var urban=precision.coast(x,z);
    if(urban!=null) {
      if(urban.weight()>=1)return Math.abs(urban.seaDistance());
      double old=baseCoastDistance(x,z)*(baseSample(x,z).land()?1:-1);
      return Math.abs(old+(urban.seaDistance()-old)*urban.weight());
    }
    CoastalRegions.Region region=coastal.at(x,z);
    if(region==null) return baseCoastDistance(x,z);
    if(region.blend(x,z)>=1) return Math.abs(region.distance(x,z,0));
    double distance=baseCoastDistance(x,z);
    return Math.abs(region.distance(x,z,baseSample(x,z).land()?distance:-distance));
  }

  private double baseCoastDistance(int x, int z) {
    double distance=coast.distance(x,z);
    if(distance>256) return distance;
    // Marching-square chords approximate a bilinear boundary. Close to shore use
    // the same coverage field as sample(), so its zero cannot sit several blocks
    // away from the shallow shelf / beach zero.
    double px=(Geo.lon(x)-122)/32*(width-1), pz=(46-Geo.lat(z))/26*(height-1);
    if(px<0||pz<0||px>=width-1||pz>=height-1)return distance;
    int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz;
    double a=(land[iz*width+ix]&255)/255.0,b=(land[iz*width+ix+1]&255)/255.0;
    double c=(land[(iz+1)*width+ix]&255)/255.0,d=(land[(iz+1)*width+ix+1]&255)/255.0;
    double coverage=(a*(1-tx)+b*tx)*(1-tz)+(c*(1-tx)+d*tx)*tz;
    double gx=((b-a)*(1-tz)+(d-c)*tz)*(width-1)/(32*Geo.DEG_LON/Geo.METRES_XZ);
    double gz=((c-a)*(1-tx)+(d-b)*tx)*(height-1)/(26*Geo.DEG_LAT/Geo.METRES_XZ);
    double gradient=Math.hypot(gx,gz);
    return gradient>1e-8 ? Math.min(distance,Math.abs(coverage-.5)/gradient) : distance;
  }

  private static BufferedImage load(String name) {
    try (InputStream in = RasterTerrain.class.getResourceAsStream("/assets/pjr/terrain/" + name)) {
      if (in == null) throw new IOException("Missing " + name);
      BufferedImage image = ImageIO.read(in);
      if (image == null) throw new IOException("Invalid PNG " + name);
      return image;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load PJR terrain", e);
    }
  }

  @Override
  public Sample sample(int x, int z) {
    Sample base=baseSample(x,z);
    CoastalRegions.Region region=coastal.at(x,z);
    if(region!=null && region.blend(x,z)>0) {
      CoastalRegions.Region beneath=coastal.beneath(region,x,z);
      if(beneath!=null && region.blend(x,z)<1)base=regionalSample(beneath,x,z,base);
      base=regionalSample(region,x,z,base);
    }
    var urban=precision.coast(x,z);
    if(urban==null)return base;
    double signed=urban.seaDistance();
    if(urban.weight()<1) {
      double old=baseCoastDistance(x,z)*(base.land()?1:-1);
      signed=old+(signed-old)*urban.weight();
    }
    if(signed<0)return new Sample(false,0,0);
    return new Sample(true,precision.elevation(x,z,base.metres()),1);
  }

  private Sample regionalSample(CoastalRegions.Region region,int x,int z,Sample base) {
    // Rivers/canals belong to terrestrial hydrology. They are never sea pixels,
    // including in map clients that ask only for the terrain's land ownership.
    if(region.channelAuthority(x,z)>0 && region.tidal(x,z)) return new Sample(true,0,1);
    double distance=region.blend(x,z)>=1 ? 0 : baseCoastDistance(x,z);
    double signed=region.distance(x,z,base.land()?distance:-distance);
    if(signed<0) return new Sample(false,0,0);
    // Explicit dry-land ownership survives zero/negative DEM values in reclaimed
    // lowlands. Sea flooding is controlled by the coastline, not a height threshold.
    return new Sample(true,Math.round(region.elevation(x,z,base.metres())),1);
  }

  private Sample baseSample(int x, int z) {
    double lon = Geo.lon(x), lat = Geo.lat(z);
    if (lon < 122 || lon > 154 || lat < 20 || lat > 46) return new Sample(false, 0, 0);
    double px = (lon - 122) / 32 * (width - 1), pz = (46 - lat) / 26 * (height - 1);
    int ix = (int) px,
        iz = (int) pz,
        ix1 = Math.min(ix + 1, width - 1),
        iz1 = Math.min(iz + 1, height - 1);
    double tx = px - ix, tz = pz - iz, coverage = 0, sum = 0, weight = 0;
    for (int k = 0; k < 4; k++) {
      int i = ((k & 2) == 0 ? iz : iz1) * width + ((k & 1) == 0 ? ix : ix1);
      double w = ((k & 1) == 0 ? 1 - tx : tx) * ((k & 2) == 0 ? 1 - tz : tz);
      int mask = land[i] & 255;
      coverage += w * mask / 255.0;
      if ((elevationMask[i] & 255) >= 128) {
        sum += (elevation[i] & 65535) * w;
        weight += w;
      }
    }
    if (coverage < 0.5) return new Sample(false, 0, coverage);
    double metres = weight == 0 ? 0 : Math.round(sum / weight);
    return new Sample(true,local.sample(x,z,precision.elevation(x,z,metres)),coverage);
  }
}
