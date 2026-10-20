package com.sora.pjr.core;
import java.io.*;
public class InspectTerrain {
 public static void main(String[] args)throws Exception {
  WorldEngine e=WorldEngine.load();Terrain t=e.hydrology().terrain();PrecisionTerrain p=new PrecisionTerrain();
  int x0=-216500,z0=58800,nx=701,nz=701,step=4;long wall=0,ndiff=0;int max=0;
  try(var o=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[0])))){
   o.writeInt(nx);o.writeInt(nz);o.writeInt(step);o.writeInt(x0);o.writeInt(z0);
   for(int iz=0;iz<nz;iz++)for(int ix=0;ix<nx;ix++){
    int x=x0+ix*step,z=z0+iz*step;var g=e.geometry(x,z);var s=t.sample(x,z);
    o.writeShort(g.naturalY());o.writeShort(g.groundY());o.writeByte(g.kind().ordinal());o.writeFloat((float)p.elevation(x,z,Double.NaN));
    if(!g.wet()){if(g.groundY()!=g.naturalY())ndiff++;for(int[] d:new int[][]{{1,0},{0,1}}){var a=e.geometry(x+d[0],z+d[1]);if(!a.wet()){int v=Math.abs(a.groundY()-g.groundY());if(v>max){max=v;System.out.println("MAX "+max+" at "+x+","+z+" natural="+g.naturalY()+"/"+a.naturalY()+" ground="+g.groundY()+"/"+a.groundY()+" precise="+p.elevation(x,z,Double.NaN)+"/"+p.elevation(x+d[0],z+d[1],Double.NaN));}if(v>2)wall++;}}}
   }
  }
  System.out.println("max="+max+" wall="+wall+" modified="+ndiff);
  for(int[] q:new int[][]{{-214483,60810},{-214979,60288},{-215695,59838},{-215587,59738},{93,10859}})System.out.println(q[0]+","+q[1]+" lon="+Geo.lon(q[0])+" lat="+Geo.lat(q[1])+" "+e.geometry(q[0],q[1]));
 }
}
