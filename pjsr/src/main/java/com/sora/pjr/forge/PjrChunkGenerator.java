package com.sora.pjr.forge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sora.pjr.core.*;
import com.sora.pjr.core.Column;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import net.minecraft.core.*;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

public final class PjrChunkGenerator extends ChunkGenerator {
  public static final Codec<PjrChunkGenerator> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(PjrBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.source), Codec.STRING.optionalFieldOf("road_snapshot", "").forGetter(g -> g.roadSnapshot))
                  .apply(i, PjrChunkGenerator::new));
  private final PjrBiomeSource source;
  private final String roadSnapshot;
  private volatile com.sora.pjr.roads.SrBridge roadBridge;
  public boolean roadsEnabled(){return !roadSnapshot.isEmpty();}
  public synchronized com.sora.pjr.roads.SrBridge roads(){
    if(!roadsEnabled())throw new IllegalStateException("Roads disabled");
    if(roadBridge==null)roadBridge=new com.sora.pjr.roads.SrBridge(WorldRuntime.engine());
    return roadBridge;
  }

  public PjrChunkGenerator(PjrBiomeSource source) {this(source, "");}
  public PjrChunkGenerator(PjrBiomeSource source,String roadSnapshot) {
    super(source); this.source=source;this.roadSnapshot=roadSnapshot;
    if(!roadSnapshot.isEmpty()&&!roadSnapshot.equals(com.sora.pjr.roads.RoadCatalog.SNAPSHOT))throw new IllegalStateException("PJSR road snapshot is missing or incompatible: "+roadSnapshot);
  }

  @Override
  protected Codec<? extends ChunkGenerator> codec() {
    return CODEC;
  }

  @Override
  public ChunkGeneratorStructureState createState(
      HolderLookup<StructureSet> sets, RandomState state, long seed) {
    return ChunkGeneratorStructureState.createForFlat(state, seed, source, Stream.empty());
  }

  @Override
  public CompletableFuture<ChunkAccess> fillFromNoise(
      Executor executor,
      Blender blender,
      RandomState state,
      StructureManager structures,
      ChunkAccess chunk) {
    return CompletableFuture.supplyAsync(
        () -> ChunkWriter.fill(chunk, WorldRuntime.engine()), executor);
  }

  @Override
  public void buildSurface(
      WorldGenRegion region, StructureManager structures, RandomState state, ChunkAccess chunk) {
    if(!roadsEnabled())return;
    var bridge=roads();if(bridge.plan.inChunk(chunk.getPos().x,chunk.getPos().z).isEmpty())return;
    var saved=com.sora.pjr.roads.RoadWorldState.get(region.getLevel());
    bridge.write(chunk,WorldRuntime.engine(),saved.suppressed);
    saved.markGenerated(chunk.getPos().x,chunk.getPos().z);
  }

  @Override
  public void applyCarvers(
      WorldGenRegion region,
      long seed,
      RandomState state,
      BiomeManager biomes,
      StructureManager structures,
      ChunkAccess chunk,
      GenerationStep.Carving step) {}

  @Override
  public void spawnOriginalMobs(WorldGenRegion region) {}

  @Override
  public void applyBiomeDecoration(
      WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
    if(!roadsEnabled()||roads().plan.inChunk(chunk.getPos().x,chunk.getPos().z).isEmpty())
      Vegetation.decorate(level, chunk, WorldRuntime.engine());
  }

  @Override
  public int getBaseHeight(
      int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState state) {
    Column c = WorldRuntime.engine().column(x, z);
    for (int y = Math.min(level.getMaxBuildHeight() - 1, c.surfaceHeight() - 1);
        y >= level.getMinBuildHeight();
        y--) if (type.isOpaque().test(BlockPalette.state(c.at(y)))) return y + 1;
    return level.getMinBuildHeight();
  }

  @Override
  public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState state) {
    Column c = WorldRuntime.engine().column(x, z);
    BlockState[] blocks = new BlockState[level.getHeight()];
    for (int i = 0; i < blocks.length; i++)
      blocks[i] = BlockPalette.state(c.at(level.getMinBuildHeight() + i));
    return new NoiseColumn(level.getMinBuildHeight(), blocks);
  }

  @Override
  public int getMinY() {
    return Geo.MIN_Y;
  }

  @Override
  public int getGenDepth() {
    return Geo.HEIGHT;
  }

  @Override
  public int getSeaLevel() {
    return Geo.SEA;
  }

  @Override
  public int getSpawnHeight(LevelHeightAccessor level) {
    return WorldRuntime.engine().column(0, 0).surfaceHeight();
  }

  @Override
  public void addDebugScreenInfo(List<String> lines, RandomState state, BlockPos p) {
    Geometry g = WorldRuntime.engine().geometry(p.getX(), p.getZ());
    lines.add(
        String.format(
            Locale.ROOT,
            "PJR %.5f E, %.5f N | ground %d | %s %s",
            Geo.lon(p.getX()),
            Geo.lat(p.getZ()),
            g.groundY(),
            g.kind(),
            g.owner()));
    if (g.wet()) lines.add("PJR water=" + g.waterY() + " depth=" + (g.waterY() - g.groundY()));
    lines.add("PJR 389 rivers / 221 lakes | X:Y:Z = 0.5:0.5:0.5");
  }
}
