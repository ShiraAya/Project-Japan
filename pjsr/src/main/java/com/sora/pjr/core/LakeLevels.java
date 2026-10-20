package com.sora.pjr.core;

import java.util.*;
import java.util.function.LongConsumer;

/** Terrain-compatible estimates only; never replaces an available physical reference level. */
public final class LakeLevels {
  private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  public record Estimate(int level, int interiorLevel, int rimSupport, int shoreCells) {}

  public static Estimate estimate(Polygon polygon, Terrain terrain, int interiorLevel) {
    int[] support = {Integer.MAX_VALUE, 0};
    dryShore(
        polygon,
        cell -> {
          int x = (int) (cell >> 32), z = (int) cell;
          Terrain.Sample sample = terrain.sample(x, z);
          // At the ocean the physical support is sea level, not the synthetic ocean floor.
          // Zero-elevation filled mask holes do not raise a coastal lake to the
          // dry-terrain minimum of one block above sea level.
          support[0] = Math.min(support[0], sample.land() && sample.metres()>0 ? sample.ground() : Geo.SEA);
          support[1]++;
        });
    if (support[1] == 0) throw new IllegalStateException("Lake has no rasterized shoreline");
    return new Estimate(
        Math.max(Geo.SEA, Math.min(interiorLevel, support[0])),
        interiorLevel,
        support[0],
        support[1]);
  }

  /** Enumerate the dry four-neighbour rim, including island shores, at half-block edge spacing. */
  public static void dryShore(Polygon polygon, LongConsumer visitor) {
    Set<Long> seen = new HashSet<>();
    List<Polygon.Ring> rings = new ArrayList<>();
    rings.add(polygon.outer());
    rings.addAll(polygon.holes());
    for (Polygon.Ring ring : rings)
      for (int i = 0; i < ring.size(); i++) {
        int j = (i + 1) % ring.size();
        double dx = ring.x(j) - ring.x(i), dz = ring.z(j) - ring.z(i);
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) * 2));
        for (int k = 0; k <= steps; k++) {
          int bx = (int) Math.floor(ring.x(i) + dx * k / steps);
          int bz = (int) Math.floor(ring.z(i) + dz * k / steps);
          for (int oz = -1; oz <= 1; oz++)
            for (int ox = -1; ox <= 1; ox++) {
              int x = bx + ox, z = bz + oz;
              long key = Geo.key(x, z);
              if (!seen.add(key) || wet(polygon, x, z)) continue;
              for (int[] offset : OFFSETS)
                if (wet(polygon, x + offset[0], z + offset[1])) {
                  visitor.accept(key);
                  break;
                }
            }
        }
      }
  }

  private static boolean wet(Polygon polygon, int x, int z) {
    return polygon.wetBlock(x, z, polygon.distance(x + .5, z + .5));
  }

  private LakeLevels() {}
}
