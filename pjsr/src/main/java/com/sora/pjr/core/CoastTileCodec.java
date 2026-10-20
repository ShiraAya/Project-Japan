package com.sora.pjr.core;

import java.io.*;
import java.util.Arrays;

/** Rebuilds legacy surveyed distance values from compact offline data, with exact residuals. */
final class CoastTileCodec {
  private static final int SIZE=512, INF=1_000_000_000;
  private static final short[] DISTANCES=new short[256];
  private static final byte[] CODES=new byte[32761];
  static {
    for(int c=-128;c<128;c++)DISTANCES[c+128]=(short)Math.round(Math.copySign(Math.min(32760,4*Math.expm1(Math.abs(c)*Math.log(2)/9)),c));
    for(int i=0;i<CODES.length;i++)CODES[i]=(byte)Math.min(127,(int)Math.rint(Math.log1p(i/4.)/Math.log(2)*9));
  }
  static short distance(int code){return DISTANCES[code+128];}

  static short[] decode(DataInputStream in)throws IOException {
    if(in.readInt()!=0x504a5243||in.readInt()!=1)throw new IOException("Unsupported coast codec");
    int width=in.readInt(),height=in.readInt(),ox=in.readInt(),oy=in.readInt();double pixel=in.readDouble();
    if(width<512||height<512||width>608||height>608||ox<0||oy<0||ox+512>width||oy+512>height||!Double.isFinite(pixel)||pixel<=0)throw new IOException("Invalid compact coast dimensions");
    byte[] mask=new byte[width*height];
    for(int channel=1;channel<=2;channel++)for(int i=0;i<mask.length;i+=8) {
      int bits=in.readUnsignedByte();for(int bit=0;bit<8;bit++)if((bits&(128>>bit))!=0)mask[i+bit]|=(byte)channel;
    }
    boolean sameChannels=true;
    for(byte b:mask){if(b<0||b>2)throw new IOException("Invalid coast class");if(b==2)sameChannels=false;}
    short[] out=new short[2*SIZE*SIZE];
    int[] sharedToWet=null,sharedToDry=null;
    for(int channel=0;channel<2;channel++) {
      float[] coarse=new float[65*65];int[] squared=new int[65*65];
      for(int y=0;y<65;y++)for(int x=0;x<65;x++) {
        int i=y*65+x,v=signedVarint(in)+(x>0?squared[i-1]:0)+(y>0?squared[i-65]:0)-(x>0&&y>0?squared[i-66]:0);
        if(v==Integer.MIN_VALUE)throw new IOException("Invalid coarse coast distance");squared[i]=v;
        coarse[i]=(float)(Math.copySign(Math.sqrt(Math.abs(v)),v)*pixel*8);
      }
      int wetCount=0;for(byte b:mask)if(channel==0?b==1:b!=0)wetCount++;
      boolean mixed=wetCount>0&&wetCount<mask.length;
      // With no inland-water class, both masks are identical. Their coarse
      // distances may still differ, so share only the exact fine transforms.
      int[] toWet=mixed?(channel==1&&sameChannels?sharedToWet:edt(mask,width,height,channel,true)):null;
      int[] toDry=mixed?(channel==1&&sameChannels?sharedToDry:edt(mask,width,height,channel,false)):null;
      if(channel==0&&sameChannels){sharedToWet=toWet;sharedToDry=toDry;}
      for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
        int c=(y>>3)*65+(x>>3);double fx=(x&7)/8.,fy=(y&7)/8.;
        float low=(float)((coarse[c]*(1-fx)+coarse[c+1]*fx)*(1-fy)+(coarse[c+65]*(1-fx)+coarse[c+66]*fx)*fy);
        double fine;
        if(mixed) {
          int i=(y+oy)*width+x+ox;boolean wet=channel==0?mask[i]==1:mask[i]!=0;
          fine=(wet?-Math.sqrt(toDry[i]):Math.sqrt(toWet[i]))*pixel;
        } else fine=wetCount==0?128:-128;
        double t=Math.max(0,Math.min(1,(Math.abs(fine)-64)/48));t=t*t*(3-2*t);
        double merged=fine+(low-fine)*t;
        int quantized=(int)Math.max(-32760,Math.min(32760,Math.rint(merged*8)));
        int code=CODES[Math.abs(quantized)]*(quantized<0?-1:1);
        out[channel*SIZE*SIZE+y*SIZE+x]=distance(code);
      }
    }
    int residuals=in.readInt();if(residuals<0||residuals>out.length)throw new IOException("Invalid coast residual count");
    int previous=-1;
    for(int n=0;n<residuals;n++) {
      int index=in.readInt();if(index<=previous||index>=out.length)throw new IOException("Invalid coast residual index");
      out[index]=distance(in.readByte());previous=index;
    }
    if(in.read()!=-1)throw new IOException("Trailing compact coast data");
    return out;
  }

  private static int signedVarint(DataInputStream in)throws IOException {
    int value=0;
    for(int shift=0;shift<35;shift+=7) {
      int b=in.readUnsignedByte();if(shift==28&&(b&0xf0)!=0)throw new IOException("Invalid coast varint");
      value|=(b&127)<<shift;if((b&128)==0)return (value>>>1)^-(value&1);
    }
    throw new IOException("Invalid coast varint");
  }

  private static int[] edt(byte[] mask,int width,int height,int channel,boolean targetWet) {
    int[] first=new int[mask.length],out=new int[mask.length];
    int n=Math.max(width,height);int[] f=new int[n],d=new int[n],sites=new int[n];double[] edges=new double[n+1];
    for(int y=0;y<height;y++) {
      for(int x=0;x<width;x++){byte b=mask[y*width+x];boolean wet=channel==0?b==1:b!=0;f[x]=wet==targetWet?0:INF;}
      transform(f,d,width,sites,edges);System.arraycopy(d,0,first,y*width,width);
    }
    for(int x=0;x<width;x++) {
      for(int y=0;y<height;y++)f[y]=first[y*width+x];
      transform(f,d,height,sites,edges);for(int y=0;y<height;y++)out[y*width+x]=d[y];
    }
    return out;
  }
  private static void transform(int[] f,int[] d,int n,int[] sites,double[] edges) {
    int k=-1;
    for(int q=0;q<n;q++) {
      if(f[q]>=INF)continue;
      double edge=Double.NEGATIVE_INFINITY;
      while(k>=0) {
        int p=sites[k];edge=((double)f[q]+q*q-f[p]-p*p)/(2.*(q-p));
        if(edge>edges[k])break;k--;
      }
      sites[++k]=q;edges[k]=k==0?Double.NEGATIVE_INFINITY:edge;edges[k+1]=Double.POSITIVE_INFINITY;
    }
    if(k<0){Arrays.fill(d,0,n,INF);return;}
    int at=0;for(int q=0;q<n;q++){while(edges[at+1]<q)at++;int delta=q-sites[at];d[q]=delta*delta+f[sites[at]];}
  }
}
