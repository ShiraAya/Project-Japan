package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Regression of the seven 0.1.10 screenshots against final generated columns. */
public final class CoastalWaterValidation {
 private static long checks;
 private static void require(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  Terrain t=new RasterTerrain();verify(new WorldEngine(t,WorldData.load(t)),t);
 }
 public static void verify(WorldEngine e,Terrain t)throws Exception {
  int specks=0,branches=0,banks=0,centres=0,expanded=0,connected=0;
  for(int[] p:new int[][]{{1696,1840},{1472,2080},{1744,2644},{1768,3016},{2012,4208}}) {
   var g=e.geometry(p[0],p[1]);require(g.kind()==Hydrology.Kind.OCEAN && g.groundY()<0,"Tiny harbour land retained "+Arrays.toString(p));specks++;
  }
  for(int[] p:new int[][]{{3344,1896},{672,3300},{192,3916},{-472,4952}})
   require(e.geometry(p[0],p[1]).kind()!=Hydrology.Kind.OCEAN,"Isolated inland sea retained "+Arrays.toString(p));
  // The tributaries pictured in screenshot 7, plus the four main lower reaches.
  for(int z=-1500;z<1600;z+=8)for(int x=-2800;x<6400;x+=8) {
   if(!t.tidalChannel(x,z))continue;
   require(t.sample(x,z).land(),"Channel reported as marine terrain "+x+","+z);
   var g=e.geometry(x,z);require(g.kind()==Hydrology.Kind.RIVER,"Mapped tributary is not river "+x+","+z);
   require(g.waterTop()==0 && g.groundY()<g.waterTop(),"Missing channel water volume");branches++;
  }
  require(branches>5000,"Insufficient branch coverage");
  StringBuilder detail=new StringBuilder();
  for(River r:e.hydrology().data().rivers()) {
   if(!t.mappedRiver(r.name()))continue;
   int local=0,excluded=0;
   for(double s=0;s<r.totalLength();s+=4) {
    var p=r.at(s);int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());
    if(t.channelAuthority(x,z)==0)continue;
    // A surveyed bank or a retained river island can intersect the historical
    // centreline. Require a nearby actual channel, not excavation through that land.
    require(e.geometry(x,z).wet() || mappedWaterNear(t,r,p),"Missing mapped river reach "+r.name()+" "+x+","+z);local++;centres++;
    // Widened banks must form one connected surface with surveyed water.
    if(t.channelAuthority(x,z)!=1)continue;
    var a=r.at(Math.max(0,s-4));var b=r.at(Math.min(r.totalLength(),s+4));
    double dx=b.x()-a.x(),dz=b.z()-a.z(),len=Math.max(.01,Math.hypot(dx,dz));
    for(int offset=-(int)Math.ceil(p.width());offset<=p.width();offset+=4) {
     int bx=(int)Math.floor(p.x()-dz/len*offset),bz=(int)Math.floor(p.z()+dx/len*offset);
     if(!t.sample(bx,bz).land() || t.tidalChannel(bx,bz) || t.channelAuthority(bx,bz)!=1)continue;
     var g=e.geometry(bx,bz);
     if(g.wet()) {
      require(g.kind()==Hydrology.Kind.RIVER,"Widened river bank became ocean");
      require(t.channelShoreDistance(bx,bz)<=2,"Extra widening exceeds two bank blocks");
      if(expanded++%97==0) {require(reachesMapped(e,t,bx,bz),"Detached widened ribbon "+r.name()+" "+bx+","+bz);connected++;}
     } else {banks++;excluded++;}
    }
   }
   detail.append(r.name()+" mappedReachSamples="+local+" dryBankSamples="+excluded+"\n");
  }
  require(centres>3000 && banks>1000,"Mapped river and bank coverage");
  require(expanded==0 || connected>0,"Missing expansion connectivity checks");
  // Actual terrain classification, including the old blend band. Every marine
  // component away from the cropped perimeter must join the main bay.
  int x0=-4652,z0=-1620,step=4,w=2800,h=2402;
  byte[] sea=new byte[w*h];int marineNodes=0;
  for(int j=0;j<h;j++)for(int i=0;i<w;i++)if(!t.sample(x0+i*step,z0+j*step).land()) {sea[j*w+i]=1;marineNodes++;}
  int[] queue=new int[w*h];int components=0,orphans=0;int largest=0;
  for(int start=0;start<sea.length;start++) {
   if(sea[start]!=1)continue;
   int head=0,tail=0;queue[tail++]=start;sea[start]=2;boolean edge=false;
   while(head<tail) {
    int q=queue[head++],i=q%w,j=q/w;
    edge|=i==0 || j==0 || i==w-1 || j==h-1;
    if(i>0 && sea[q-1]==1) {sea[q-1]=2;queue[tail++]=q-1;}
    if(i+1<w && sea[q+1]==1) {sea[q+1]=2;queue[tail++]=q+1;}
    if(j>0 && sea[q-w]==1) {sea[q-w]=2;queue[tail++]=q-w;}
    if(j+1<h && sea[q+w]==1) {sea[q+w]=2;queue[tail++]=q+w;}
   }
   components++;largest=Math.max(largest,tail);if(!edge)orphans++;
  }
  require(orphans==0,"Enclosed marine components in final terrain: "+orphans);
  require(largest>2_000_000,"Tokyo Bay lost its marine body");
  String report="PJR coastal water ownership validation PASS\nassertions="+checks+"\ntinyLandSites="+specks+
   "\ntributarySamples="+branches+"\nmappedReachSamples="+centres+"\ndryBankSamples="+banks+"\nwidenedBankSamples="+expanded+"\nindependentConnectivityFloods="+connected+
   "\nmarineGridSamples="+(w*h)+"\nmarineNodes="+marineNodes+"\nmarineComponents="+components+
   "\nenclosedMarineComponents="+orphans+"\n"+detail+
   "Final terrain and water columns; not a graphical client, PJM cache or fluid-tick test.\n";
  Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/coastal-water-validation.txt"),report);System.out.print(report);
 }
 private static boolean reachesMapped(WorldEngine e,Terrain t,int x,int z) {
  int span=257;byte[] seen=new byte[span*span];int[] queue=new int[span*span];int head=0,tail=0;
  queue[tail++]=128*span+128;seen[queue[0]]=1;
  while(head<tail) {
   int q=queue[head++],i=q%span,j=q/span,px=x+i-128,pz=z+j-128;
   if(t.tidalChannel(px,pz) || !t.sample(px,pz).land())return true;
   for(int n:new int[]{i>0?q-1:-1,i+1<span?q+1:-1,j>0?q-span:-1,j+1<span?q+span:-1}) {
    if(n<0 || seen[n]!=0)continue;seen[n]=1;
    if(e.geometry(x+n%span-128,z+n/span-128).wet())queue[tail++]=n;
   }
  }
  return false;
 }
 public static boolean mappedWaterNear(Terrain t,River r,River.Point p) {
  int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());
  if(!t.mappedRiver(r.name()) || t.channelAuthority(x,z)==0) return false;
  // Maximum accepted W05/GSI offset is 64 blocks (~171 m), checked against
  // an actual wet point, including both sides of river islands.
  for(int dz=-64;dz<=64;dz+=4)for(int dx=-64;dx<=64;dx+=4)
   if(dx*dx+dz*dz<=64*64 && t.tidalChannel(x+dx,z+dz)) return true;
  return false;
 }
}
