package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import com.sora.pjr.forge.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Actual Minecraft ProtoChunks, palettes and heightmaps; not a client visual test. */
@SuppressWarnings("deprecation")
public final class ForgeValidation {
  private static long checks;

  private static void require(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    SharedConstants.tryDetectVersion();
    try {
      Bootstrap.bootStrap();
    } catch (ExceptionInInitializerError e) {
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      if (!(cause instanceof NoSuchMethodException)
          || !cause.getMessage().contains("NetworkEvent.<init>()")) throw e;
      System.out.println(
          "Standalone bootstrap: skipped Forge NetworkEvent constructor transform (no networking"
              + " used).");
    }
    WorldEngine engine = WorldRuntime.engine();
    FlowerValidation.verify();
    LevelHeightAccessor height = LevelHeightAccessor.create(Geo.MIN_Y, Geo.HEIGHT);
    List<int[]> locations = new ArrayList<>();
    locations.add(new int[] {0, 0});
    locations.add(new int[] {-1, -1});
    locations.add(new int[] {100000, 100000});
    locations.add(new int[] {(int) Geo.x(138.7274), (int) Geo.z(35.3606)});
    for (int[] point :
        new int[][] {
          {195,1514},{350,1610},{-210750,80395},{-210790,80352},{-210850,80280},
          {20591, -223057}, {33175, -4397}, {-73191, 40641}, {-212123, 92685},
          {-7500, -9526}, {-1246, -4445}, {4197, 2406},
          {-58020, -15296},
          {-53378, -4634},
          {-53630, -4786},
          {-42046, 19183},
          {-3074, 8429},
          {-30451, -10810},
          {-52367, 19624},
          {-53540, 13419},
          {-96929, -19048},
          {-66860, 24291},
          {99718, -352095},
          {-298950, 127154},
          {-80533, 11635},
          {-82118, 10446},
          {-58004, -15460},
          {-53695, 21629},
          {-57167, 29551},
          {-78712, 18564},
          {-54026, 14772},
          {-55199, 34899},
          {-24894, -84074},
          {27853, -8586}
        }) locations.add(new int[]{(int)Math.round(point[0]*4./3),(int)Math.round(point[1]*4./3)});
    for (String name : List.of("Biwa", "Kussharo", "Yamanaka", "Suwa")) {
      Lake l =
          engine.hydrology().data().lakes().stream()
              .filter(v -> v.name().equals(name))
              .findFirst()
              .orElseThrow();
      locations.add(new int[] {l.representativeX(), l.representativeZ()});
    }
    for (String name : List.of("Kurobe", "Tone", "Yodo", "Sagami")) {
      River r =
          engine.hydrology().data().rivers().stream()
              .filter(v -> v.name().equals(name))
              .findFirst()
              .orElseThrow();
      River.Point p = r.at(r.mouthDistance() * 0.4);
      locations.add(new int[] {(int) p.x(), (int) p.z()});
    }
    // New lake exits, lake-to-lake links, a surveyed gap and confluence aprons.
    for (String name : List.of("千歳川", "阿寒川", "三方湖・水月湖連絡水路",
        "境水道", "岩木川（十三湖湖口）", "北山川", "白井川", "カリベツ川")) {
      River r=engine.hydrology().data().rivers().stream()
          .filter(v->v.name().equals(name)).findFirst().orElseThrow();
      River.Point p=r.at(Math.max(0,r.totalLength()-24));
      locations.add(new int[]{(int)Math.floor(p.x()),(int)Math.floor(p.z())});
    }
    // The formerly coarse, rectangular coastline in the user's bay screenshot.
    locations.add(new int[]{(int)Geo.x(139.90),(int)Geo.z(35.36)});
    for(int[] point:new int[][]{{464,7814},{2292,7928},{3680,9088},{5971,-60},
        {5772,290},{2821,1529},{-292520,156049},{-314437,154181},{-200106,63256}})
      locations.add(new int[]{(int)Math.round(point[0]*4./3),(int)Math.round(point[1]*4./3)});
    PjrChunkGenerator generator = new PjrChunkGenerator(new PjrBiomeSource(null, null, null));
    for (River r : engine.hydrology().data().rivers()) {
      if (!RiverShapes.names().contains(r.name())) continue;
      River.Point p = r.at(r.mouthDistance() * 0.8);
      locations.add(new int[] {(int) Math.floor(p.x()), (int) Math.floor(p.z())});
    }
    for(double[] p:CoastalExpansionValidation.LAND) locations.add(new int[]{(int)Math.round(Geo.x(p[0])),(int)Math.round(Geo.z(p[1]))});
    for(double[] p:CoastalExpansionValidation.WATER) locations.add(new int[]{(int)Math.round(Geo.x(p[0])),(int)Math.round(Geo.z(p[1]))});
    // Source-classified interiors include every urban region, including reclaimed dry land.
    Set<String> covered=new HashSet<>();
    for(String line:Files.readAllLines(Path.of("data/precision-coast-probes.tsv"))) {
      if(line.startsWith("#")||line.isBlank())continue;
      String[] f=line.split("\\t");String key=f[0].replaceFirst("-[0-2]-[0-9]+$", "")+"-"+f[3];
      if(covered.add(key))locations.add(new int[]{Integer.parseInt(f[1]),Integer.parseInt(f[2])});
    }
    long t = System.nanoTime();
    int chunks = 0;
    for (int[] location : locations) {
      ChunkPos cp = new ChunkPos(Math.floorDiv(location[0], 16), Math.floorDiv(location[1], 16));
      ProtoChunk chunk = create(height, cp);
      ChunkWriter.fill(chunk, engine);
      chunks++;
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int z = 0; z < 16; z++)
        for (int x = 0; x < 16; x++) {
          int wx = cp.getMinBlockX() + x, wz = cp.getMinBlockZ() + z;
          Column c = engine.column(wx, wz);
          NoiseColumn queried = generator.getBaseColumn(wx, wz, height, null);
          for (int y = Geo.MIN_Y; y < Geo.MAX_Y; y++) {
            BlockState actual = chunk.getBlockState(pos.set(wx, y, wz));
            require(actual.equals(BlockPalette.state(c.at(y))), "Chunk mismatch " + pos);
            require(actual.equals(queried.getBlock(y)), "Base column mismatch " + pos);
          }
          for (Heightmap.Types type :
              List.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG)) {
            int scan = Geo.MIN_Y;
            for (int y = Geo.MAX_Y - 1; y >= Geo.MIN_Y; y--)
              if (type.isOpaque().test(chunk.getBlockState(pos.set(wx, y, wz)))) {
                scan = y + 1;
                break;
              }
            require(
                chunk.getHeight(type, x, z) + 1 == scan, "Heightmap mismatch " + cp + " " + type);
            require(
                generator.getBaseHeight(wx, wz, type, height, null) == scan,
                "Base height mismatch " + cp + " " + type);
          }
        }
      for (LevelChunkSection section : chunk.getSections()) {
        boolean nonAir = false, blocksTick = false, fluidsTick = false;
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
              BlockState s = section.getBlockState(x, y, z);
              nonAir |= !s.isAir();
              blocksTick |= s.isRandomlyTicking();
              fluidsTick |= s.getFluidState().isRandomlyTicking();
            }
        require(section.hasOnlyAir() == !nonAir, "Section non-air count");
        require(section.isRandomlyTickingBlocks() == blocksTick, "Section block tick count");
        require(section.isRandomlyTickingFluids() == fluidsTick, "Section fluid tick count");
      }
    }
    String report =
        String.format(
            Locale.ROOT,
            "PJR Forge ProtoChunk validation PASS\n"
                + "chunks=%d\n"
                + "assertions=%d\n"
                + "elapsedSeconds=%.3f\n"
                + "Includes exact block/base-column parity, scanned heightmaps/base-height parity,"
                + " section counters.\n"
                + "Excludes client rendering, full server tick simulation and vegetation"
                + " integration.\n",
            chunks,
            checks,
            (System.nanoTime() - t) / 1e9);
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/forge-validation.txt"), report);
    System.out.print(report);
  }

  private static ProtoChunk create(LevelHeightAccessor height, ChunkPos cp) {
    LevelChunkSection[] sections = new LevelChunkSection[height.getSectionsCount()];
    for (int i = 0; i < sections.length; i++)
      sections[i] =
          new LevelChunkSection(
              new PalettedContainer<>(
                  Block.BLOCK_STATE_REGISTRY,
                  Blocks.AIR.defaultBlockState(),
                  PalettedContainer.Strategy.SECTION_STATES),
              null);
    return new ProtoChunk(
        cp,
        UpgradeData.EMPTY,
        sections,
        new ProtoChunkTicks<>(),
        new ProtoChunkTicks<>(),
        height,
        null,
        null);
  }
}
