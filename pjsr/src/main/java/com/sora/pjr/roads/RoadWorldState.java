package com.sora.pjr.roads;

import com.sora.pjr.core.Geo;
import com.sora.pjr.forge.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Main-thread SR registration plus immutable suppression snapshot consumed by chunk workers. */
public final class RoadWorldState extends SavedData {
  private static final Map<ServerLevel,RoadWorldState> WORLDS=new ConcurrentHashMap<>();
  public final Set<UUID> introduced=ConcurrentHashMap.newKeySet();
  public final Set<UUID> suppressed=ConcurrentHashMap.newKeySet();
  final Set<Long> generated=ConcurrentHashMap.newKeySet();
  final Set<Long> ready=ConcurrentHashMap.newKeySet();
  final Set<UUID> changed=ConcurrentHashMap.newKeySet();
  private transient ServerLevel level;
  private transient RoadData sr;
  private transient SrBridge bridge;
  private boolean internal;
  private long observedRevision=-1;
  private final Map<UUID,RoadRecord> observed=new HashMap<>();
  private transient java.nio.file.Path journal;
  public RoadWorldState() {}
  static RoadWorldState load(CompoundTag t){
    if(t.getInt("Version")!=1||!RoadCatalog.SNAPSHOT.equals(t.getString("Snapshot"))||!RoadCatalog.HASH.equals(t.getString("Hash")))throw new IllegalStateException("PJSR world snapshot mismatch; restore the matching mod/data version");
    var s=new RoadWorldState();for(Tag item:t.getList("Introduced",Tag.TAG_INT_ARRAY))s.introduced.add(NbtUtils.loadUUID(item));
    for(Tag item:t.getList("Suppressed",Tag.TAG_INT_ARRAY))s.suppressed.add(NbtUtils.loadUUID(item));return s;
  }
  @Override public CompoundTag save(CompoundTag t){
    t.putInt("Version",1);t.putString("Snapshot",RoadCatalog.SNAPSHOT);t.putString("Hash",RoadCatalog.HASH);
    var a=new ListTag();introduced.stream().sorted().forEach(id->a.add(NbtUtils.createUUID(id)));t.put("Introduced",a);
    var b=new ListTag();suppressed.stream().sorted().forEach(id->b.add(NbtUtils.createUUID(id)));t.put("Suppressed",b);return t;
  }
  public static RoadWorldState get(ServerLevel level){
    var s=WORLDS.get(level);if(s==null)throw new IllegalStateException("PJSR state not initialized before world generation");return s;
  }
  public static void initialize(ServerLevel level){
    if(!(level.getChunkSource().getGenerator() instanceof PjrChunkGenerator g)||!g.roadsEnabled())return;
    if(WORLDS.containsKey(level))return;
    var state=level.getDataStorage().computeIfAbsent(RoadWorldState::load,RoadWorldState::new,"pjsr_roads_v1");
    state.level=level;state.sr=RoadData.get(level);state.bridge=g.roads();
    // A write-ahead override journal closes the gap between independently saved SR/chunk data.
    state.journal=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        .resolve("pjsr-overrides-"+Integer.toHexString(level.dimension().location().toString().hashCode())+"-v1.txt");
    try{if(java.nio.file.Files.exists(state.journal)){
      var lines=java.nio.file.Files.readAllLines(state.journal);
      if(lines.isEmpty()||!lines.get(0).equals(RoadCatalog.SNAPSHOT+" "+RoadCatalog.HASH))throw new java.io.IOException("Override journal snapshot mismatch");
      for(String line:lines.subList(1,lines.size()))if(!line.isBlank())state.suppressed.add(UUID.fromString(line));
    }}catch(Exception e){throw new IllegalStateException("Cannot recover PJSR overrides",e);}
    var previous=state.sr.index.changed;
    state.sr.index.changed=id->{previous.accept(id);if(!state.internal&&state.bridge.records.containsKey(id)){
      // Callback can precede a transactional replace. Suppress base immediately, observe final result at tick end.
      state.suppressed.add(id);state.persistOverrides();state.changed.add(id);state.setDirty();
    }};
    state.observeNativeChanges();state.setDirty();WORLDS.put(level,state);
  }
  private void persistOverrides(){
    try{
      var lines=new ArrayList<String>();lines.add(RoadCatalog.SNAPSHOT+" "+RoadCatalog.HASH);
      suppressed.stream().sorted().forEach(id->lines.add(id.toString()));
      var temp=journal.resolveSibling(journal.getFileName()+".tmp");
      java.nio.file.Files.write(temp,lines,java.nio.charset.StandardCharsets.UTF_8);
      try(var channel=java.nio.channels.FileChannel.open(temp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
      java.nio.file.Files.move(temp,journal,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }catch(java.io.IOException e){throw new IllegalStateException("PJSR could not persist edit suppression",e);}
  }
  private static CompoundTag authored(RoadRecord road){
    var md=LaneTopology.metadata(road);
    // SR may refresh derived end handles while only part of a corridor is registered.
    // Those handles are not a player override; retain manual points and every other field.
    return road.withLanePoints(md.points(md.points().stream().filter(p->!p.automatic()).toList())).save();
  }
  private void observeNativeChanges(){
    long revision=sr.index.revision();if(revision==observedRevision)return;
    boolean dirty=false;
    // SR 1.0.15's atomic splitter temporarily mutes index.changed. Compare only
    // this bounded pilot's introduced IDs after an index revision, never all SR roads.
    for(UUID id:introduced){
      if(suppressed.contains(id)||!bridge.records.containsKey(id))continue;
      var live=sr.index.roads.get(id);var before=observed.get(id);
      if(live==null){if(before!=null)dirty|=suppressed.add(id);}
      else if(live.record!=before){
        if(!authored(live.record).equals(authored(bridge.records.get(id))))dirty|=suppressed.add(id);
        observed.put(id,live.record);
      }
    }
    observedRevision=revision;
    if(dirty){persistOverrides();setDirty();}
  }
  public void markGenerated(int x,int z){generated.add(Geo.key(x,z));}
  public void markReady(int x,int z){ready.add(Geo.key(x,z));}
  public void drain(){
    // Native SR edits synchronously materialize their whole bounded unit. Future automatic writes are blocked.
    observeNativeChanges();changed.clear();
    int budget=4;
    for(long key:new ArrayList<>(ready)){
      if(budget--<=0)break;ready.remove(key);
      int x=(int)(key>>32),z=(int)key;
      if(!generated.contains(key))continue;
      internal=true;
      try{
        for(var u:bridge.plan.inChunk(x,z)){
          if(suppressed.contains(u.id()))continue;
          var existing=sr.index.roads.get(u.id());
          if(existing==null){
            // Unsuppressed missing records are interrupted registration and can be recovered.
            var record=bridge.records.get(u.id());sr.index.put(RoadIndex.Built.loading(record));sr.setDirty();
            introduced.add(u.id());setDirty();RoadNetwork.broadcastRoad(level,record);
          }
        }
      }finally{internal=false;}
    }
  }
  public static void unload(ServerLevel level){WORLDS.remove(level);}
}
