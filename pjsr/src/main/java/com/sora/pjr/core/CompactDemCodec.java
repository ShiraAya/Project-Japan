package com.sora.pjr.core;

import java.io.*;

/** Compact retained DEM nodes; resolution loss is explicit in the profile, not the codec. */
final class CompactDemCodec {
  static float[] decodeFloatsXor(DataInputStream in,int width,int height)throws IOException {
    int[] bits=new int[Math.multiplyExact(width,height)];byte[] plane=new byte[bits.length];
    for(int shift=24;shift>=0;shift-=8){in.readFully(plane);for(int i=0;i<bits.length;i++)bits[i]|=(plane[i]&255)<<shift;}
    float[] values=new float[bits.length];
    for(int y=0;y<height;y++)for(int x=0;x<width;x++){int i=y*width+x;if(x>0)bits[i]^=bits[i-1];values[i]=Float.intBitsToFloat(bits[i]);}
    return values;
  }
  static short[] decode(DataInputStream in,int expectedSize,int expectedQuantum)throws IOException {
    if(in.readInt()!=0x504a5245)throw new IOException("Invalid compact DEM header");
    int size=in.readUnsignedShort(),quantum=in.readUnsignedByte(),codec=in.readUnsignedByte();
    if(size!=expectedSize||quantum!=expectedQuantum)throw new IOException("DEM profile mismatch");
    short[] data=decodeSigned16(in,size,size,codec);
    for(int i=0;i<data.length;i++)if(data[i]!=Short.MIN_VALUE) {
      int value=data[i]*quantum;if(value<=Short.MIN_VALUE||value>Short.MAX_VALUE)throw new IOException("DEM elevation overflow");data[i]=(short)value;
    }
    if(in.read()!=-1)throw new IOException("Trailing compact DEM bytes");return data;
  }
  static short[] decodeSigned16(DataInputStream in,int width,int height,int codec)throws IOException {
    short[] data=new short[Math.multiplyExact(width,height)];
    if(codec==1) {
      byte[] high=new byte[data.length];in.readFully(high);
      for(int i=0;i<data.length;i++){int zig=((high[i]&255)<<8)|in.readUnsignedByte();data[i]=(short)((zig>>>1)^-(zig&1));}
    } else if(codec==2) {
      for(int i=0;i<data.length;i++) {
        int zig=0,shift=0,b;
        do {b=in.readUnsignedByte();if(shift==14&&(b&0xfc)!=0)throw new IOException("Invalid DEM varint");zig|=(b&127)<<shift;shift+=7;}while((b&128)!=0&&shift<=14);
        if((b&128)!=0)throw new IOException("Invalid DEM varint");data[i]=(short)((zig>>>1)^-(zig&1));
      }
    } else throw new IOException("Invalid DEM predictor");
    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
      int i=y*width+x;data[i]=(short)(data[i]+(x>0?data[i-1]:0)+(y>0?data[i-width]:0)-(x>0&&y>0?data[i-width-1]:0));
    }
    return data;
  }
}
