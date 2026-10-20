package com.sora.pjr.core;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
/** Real packaged DEM nodata boundaries: continuous coarse contribution, valid-cell parity. */
public final class DemBoundaryValidation {
 public static void main(String[] args)throws Exception {
  var raster=new TiledRaster("dem-atlas",256,1,128);var tile=TiledRaster.class.getDeclaredMethod("tile",int.class,int.class,int.class);tile.setAccessible(true);
  int tiles=0,mixedTiles=0,checks=0;double maxJump=0;
  try(var in=new BufferedReader(new InputStreamReader(DemBoundaryValidation.class.getResourceAsStream("/assets/pjr/terrain/dem-atlas/index.tsv")))){
   for(String line;(line=in.readLine())!=null;){if(line.isBlank()||line.startsWith("#"))continue;String[] f=line.split("\t");int z=Integer.parseInt(f[0]),x=Integer.parseInt(f[1]),y=Integer.parseInt(f[2]),n=z==12?64:128;double step=256.0/n;short[] a=(short[])tile.invoke(raster,z,x,y);boolean found=false;
    // Both interior cells and cross-tile seams use the same sampler.
    for(int j=0;j<n;j++)for(int i=0;i<n;i++){
     for(int axis=0;axis<2;axis++) {
      short[] adjacent=axis==0?(i+1==n?(short[])tile.invoke(raster,z,x+1,y):a):(j+1==n?(short[])tile.invoke(raster,z,x,y+1):a);
      int ni=axis==0?(i+1)%n:i,nj=axis==1?(j+1)%n:j;
      boolean valid=a[j*n+i]!=Short.MIN_VALUE,next=adjacent!=null&&adjacent[nj*n+ni]!=Short.MIN_VALUE;
      if(valid==next)continue;
      found=true;double px=(x*n+i)*step,py=(y*n+j)*step,eps=step*1e-6;
      if(valid){if(axis==0)px+=step;else py+=step;}
      double dx=axis==0?eps:0,dy=axis==1?eps:0;
      double l=raster.sample(z,px-dx,py-dy,0,500),r=raster.sample(z,px+dx,py+dy,0,500);
      if(!Double.isFinite(l+r)||Math.abs(l-r)>.15)throw new AssertionError("DEM jump "+z+"/"+x+"/"+y+" "+l+"/"+r);
      maxJump=Math.max(maxJump,Math.abs(l-r));checks++;
     }
    }
    double px=(x*n+n/2.0)*step,py=(y*n+n/2.0)*step;double old=raster.sample(z,px,py,0),fresh=raster.sample(z,px,py,0,500);
    if(Double.isFinite(old)&&old!=fresh)throw new AssertionError("Retained node changed");
    tiles++;if(found)mixedTiles++;
   }
  }
  System.out.printf(Locale.ROOT,"DEM BOUNDARIES PASS tiles=%d mixedTiles=%d boundaryChecks=%d maxInfinitesimalJump=%.9f halfMetreUnits%n",tiles,mixedTiles,checks,maxJump);
 }
}
