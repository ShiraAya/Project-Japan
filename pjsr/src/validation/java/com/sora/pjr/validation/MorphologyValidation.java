package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Image-coordinate regressions and export of actual engine geometry, not invented preview art. */
public final class MorphologyValidation {
  private static final int[][] SITES = {
    {-80533, 11635}, {-82118, 10446}, {-58004, -15460},
    {-53695, 21629}, {-57167, 29551}, {-78712, 18564}
  };
  private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    export(WorldEngine.load(), args.length == 0 ? "morphology" : args[0], args.length == 0);
  }

  public static void verify(WorldEngine engine) throws Exception {
    export(engine, "morphology", true);
  }

  private static void export(WorldEngine engine, String label, boolean check) throws Exception {
    Files.createDirectories(Path.of("build/reports"));
    StringBuilder report =
        new StringBuilder(
            "PJR morphology " + (check ? "validation PASS" : "baseline export") + "\n");
    report.append("6 screenshot windows, +/-240 blocks, spacing=2, 58081 sampled columns each.\n");
    int allPairs = 0, allRemote = 0;
    Path tilePath = Path.of("build/reports/" + label + "-tiles.bin");
    Path stagedTiles = tilePath.resolveSibling(tilePath.getFileName() + ".part");
    try (DataOutputStream tiles =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(stagedTiles)))) {
      tiles.writeInt(SITES.length);
      tiles.writeInt(241);
      for (int i = 0; i < SITES.length; i++) {
        int cx = SITES[i][0], cz = SITES[i][1];
        tiles.writeInt(cx);
        tiles.writeInt(cz);
        int wet = 0, maxRaise = 0, extraJump = 0, remoteContacts = 0;
        TreeMap<Integer, Integer> gaps = new TreeMap<>(), depths = new TreeMap<>();
        TreeMap<String, Integer> materials = new TreeMap<>();
        for (int z = cz - 240; z <= cz + 240; z += 2)
          for (int x = cx - 240; x <= cx + 240; x += 2) {
            Column column = engine.column(x, z);
            Geometry g = column.geometry();
            tiles.writeShort(g.groundY());
            tiles.writeShort(g.wet() ? g.waterY() : Short.MIN_VALUE);
            tiles.writeShort(g.naturalY());
            tiles.writeByte(g.kind().ordinal());
            tiles.writeByte(column.layers().top().ordinal());
            if (g.wet()) {
              wet++;
              depths.merge(g.waterY() - g.groundY(), 1, Integer::sum);
              materials.merge(column.layers().top().name(), 1, Integer::sum);
            } else {
              maxRaise = Math.max(maxRaise, g.groundY() - g.naturalY());
              for (int[] offset : OFFSETS) {
                Geometry adjacent = engine.geometry(x + offset[0], z + offset[1]);
                if (adjacent.wet()) gaps.merge(g.groundY() - adjacent.waterTop(), 1, Integer::sum);
                else
                  extraJump =
                      Math.max(
                          extraJump,
                          Math.abs(g.groundY() - adjacent.groundY())
                              - Math.abs(g.naturalY() - adjacent.naturalY()));
              }
            }
            if ((i == 3 || i == 4) && g.kind() == Hydrology.Kind.RIVER) {
              River.Hit a = engine.hydrology().sample(x, z).water().river();
              for (int[] offset : OFFSETS) {
                Hydrology.Water b = engine.hydrology().sample(x + offset[0], z + offset[1]).water();
                if (b.kind() != Hydrology.Kind.RIVER || !b.owner().equals(g.owner())) continue;
                if (Math.abs(a.station() - b.river().station()) > Math.max(96, a.width() * 3))
                  remoteContacts++;
              }
            }
          }
        int pairs = gaps.values().stream().mapToInt(Integer::intValue).sum();
        allPairs += pairs;
        allRemote += remoteContacts;
        River.Hit river = engine.hydrology().sample(cx, cz).river();
        report.append(
            String.format(
                Locale.ROOT,
                "site%d=(%d,%d) river=%s width=%.3f wet=%d maxRaise=%d maxExtraJump=%d"
                    + " shorePairs=%d gaps=%s remoteReachContacts=%d%n",
                i + 1,
                cx,
                cz,
                river.river().name(),
                river.width(),
                wet,
                maxRaise,
                extraJump,
                pairs,
                gaps,
                remoteContacts));
        report
            .append(" depths=")
            .append(depths)
            .append(" bedMaterials=")
            .append(materials)
            .append('\n');
        if (check) {
          require(pairs > 100, "Screenshot shoreline coverage " + i);
          require(
              gaps.firstKey() >= 0 && gaps.lastKey() <= 1,
              "Raised immediate shoreline " + i + " " + gaps);
          require(maxRaise == 0, "Artificial containment wall in screenshot " + i);
          require(extraJump <= 2, "Discontinuous shore blend in screenshot " + i);
          require(remoteContacts == 0, "Distant meander reaches touch across their neck " + i);
        }
        engine.releaseThreadCache();
      }
    }
    for (String name : List.of("Suwa", "奥矢作湖", "Nojiri", "Tazawa")) {
      Lake lake =
          engine.hydrology().data().lakes().stream()
              .filter(l -> l.name().equals(name))
              .findFirst()
              .orElseThrow();
      Bounds b = lake.polygon().bounds();
      TreeMap<Integer, Integer> depths = new TreeMap<>();
      TreeMap<String, Integer> materials = new TreeMap<>();
      for (int iz = 0; iz < 101; iz++)
        for (int ix = 0; ix < 101; ix++) {
          int x = (int) (b.minX() + (b.maxX() - b.minX()) * (ix + 0.5) / 101);
          int z = (int) (b.minZ() + (b.maxZ() - b.minZ()) * (iz + 0.5) / 101);
          Column column = engine.column(x, z);
          Geometry g = column.geometry();
          if (g.kind() != Hydrology.Kind.LAKE || !g.owner().equals(lake.id())) continue;
          depths.merge(g.waterY() - g.groundY(), 1, Integer::sum);
          materials.merge(column.layers().top().name(), 1, Integer::sum);
        }
      report
          .append("lake=")
          .append(name)
          .append(" level=")
          .append(lake.waterY())
          .append(" maxDepthLimit=")
          .append(lake.maxDepth())
          .append(" generationDepthLimit=")
          .append(lake.basinDepth())
          .append(" sampledDepths=")
          .append(depths)
          .append(" materials=")
          .append(materials)
          .append('\n');
      if (check) {
        require(depths.firstKey() == 1, "Lake must retain shallow margins " + name);
        require(
            depths.lastKey() <= lake.basinDepth(), "Inlet may not exceed generation depth " + name);
        require(
            depths.lastKey() >= lake.basinDepth() * 0.85,
            "Lake geometry never reaches reference depth " + name);
        require(depths.size() >= Math.min(3, lake.basinDepth()), "Flat single-depth lake " + name);
        require(materials.size() >= 3, "Uniform lake bed material " + name);
        int count = depths.values().stream().mapToInt(Integer::intValue).sum();
        require(materials.getOrDefault("SAND", 0) < count * 0.6, "Entire lake is sand " + name);
        if (name.equals("Suwa")) {
          require(
              lake.maxDepth() == 3 && lake.basinDepth() == 5,
              "Keep Suwa reference at 3 while applying the requested two-block visual allowance");
          require(
              Collections.max(depths.values()) < count * 0.8,
              "Suwa depth quantization creates a flat plate");
        }
        if (name.equals("Nojiri"))
          require(lake.maxDepth() == 14, "Zero input must use the existing 38.3 m reference");
        if (name.equals("奥矢作湖")) require(lake.waterY() == 112, "Verified 298 m Yahagi pool");
      }
      engine.releaseThreadCache();
    }
    if (check) {
      for (River r : engine.hydrology().data().rivers()) {
        for (int i = 1; i < r.points().size(); i++) {
          River.Point a = r.points().get(i - 1), b = r.points().get(i);
          require(
              b.width() >= a.width() - 1e-9
                  && b.width() - a.width() <= 0.00800001 * (b.station() - a.station()),
              "Abrupt change in river width " + r.name());
        }
      }
    }
    report
        .append("totalShorePairs=")
        .append(allPairs)
        .append("\nremoteMeanderContacts=")
        .append(allRemote)
        .append(
            "\n"
                + "Limit: sampled geometry and materials; no client rendering or fluid-tick"
                + " simulation.\n");
    Files.move(
        stagedTiles, tilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    Files.writeString(Path.of("build/reports/" + label + "-validation.txt"), report);
    System.out.print(report);
  }
}
