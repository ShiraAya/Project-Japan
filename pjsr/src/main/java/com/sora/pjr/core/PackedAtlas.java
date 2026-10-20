package com.sora.pjr.core;

import java.io.*;
import java.util.*;

/** Small independently compressed 4x4 tile packs; bounded compressed-byte cache, no disk/network. */
final class PackedAtlas {
  private static final int MAX_PACK=4*1024*1024,MAX_CACHE=4*1024*1024;
  private final String folder;
  private final LinkedHashMap<Long,byte[]> cache=new LinkedHashMap<>(16,.75f,true);
  private int cachedBytes;
  PackedAtlas(String folder){this.folder=folder;}
  synchronized InputStream open(int z,int x,int y)throws IOException {
    long key=TiledRaster.key(z,x>>2,y>>2);byte[] bytes=cache.get(key);
    if(bytes==null) {
      String path="/assets/pjr/terrain/"+folder+"/packs/"+z+"-"+(x>>2)+"-"+(y>>2)+".pck";
      try(var in=PackedAtlas.class.getResourceAsStream(path)) {
        if(in==null)throw new IOException("Missing offline atlas pack "+path);bytes=in.readNBytes(MAX_PACK+1);
      }
      if(bytes.length>MAX_PACK)throw new IOException("Atlas pack exceeds safety bound");
      while(!cache.isEmpty()&&(cache.size()>=16||cachedBytes+bytes.length>MAX_CACHE)) {
        var first=cache.entrySet().iterator();cachedBytes-=first.next().getValue().length;first.remove();
      }
      cache.put(key,bytes);cachedBytes+=bytes.length;
    }
    try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
      if(in.readInt()!=0x504a5250)throw new IOException("Bad atlas pack header");int count=in.readUnsignedShort();
      if(count<1||count>16)throw new IOException("Invalid atlas pack count");int start=6+count*9,target=(y&3)*4+(x&3),offset=-1,length=0,seen=0;
      for(int i=0;i<count;i++) {
        int slot=in.readUnsignedByte(),at=in.readInt(),size=in.readInt();
        if(slot>=16||(seen&(1<<slot))!=0||at<0||size<1||at>(long)bytes.length-start-size)throw new IOException("Invalid atlas pack entry");
        seen|=1<<slot;if(slot==target){offset=start+at;length=size;}
      }
      if(offset<0)throw new IOException("Indexed tile missing from pack");return new ByteArrayInputStream(bytes,offset,length);
    }
  }
}
