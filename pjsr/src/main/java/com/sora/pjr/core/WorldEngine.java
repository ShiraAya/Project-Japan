package com.sora.pjr.core;

import java.util.*;

/**
 * Geographic geometry -> final bank geometry -> surface paint. Caches are bounded and thread-local.
 */
public final class WorldEngine {
  private final Hydrology hydrology;
  private final SurfacePainter painter = new SurfacePainter();
  private final ThreadLocal<Map<Long, Geometry>> geometries = ThreadLocal.withInitial(HashMap::new);
  private final ThreadLocal<Map<Long, Hydrology.Context>> contexts =
      ThreadLocal.withInitial(HashMap::new);

  public WorldEngine(Terrain terrain, WorldData data) {
    hydrology = new Hydrology(terrain, data);
  }

  public static WorldEngine load() {
    Terrain terrain = new RasterTerrain();
    return new WorldEngine(terrain, WorldData.load(terrain));
  }

  public Hydrology hydrology() {
    return hydrology;
  }

  public Geometry geometry(int x, int z) {
    Map<Long, Geometry> cache = geometries.get();
    long key = Geo.hash(Geo.key(x, z));
    Geometry old = cache.get(key);
    if (old != null) return old;
    Hydrology.Context c = context(x, z);
    var w = c.water();
    int natural = c.terrain().ground();
    int ground = natural;
    boolean containment = false;
    if (w.wet()) ground = w.bed();
    else {
      ground = hydrology.bankGround(x, z, c);
      // A local spill test, independent of nearest-feature bank painting. Exactly the four
      // adjacent wet columns determine whether a dry cell needs a containment sill.
      for (int[] offset : NEIGHBOURS) {
        var adjacent = context(x + offset[0], z + offset[1]).water();
        if (adjacent.wet() && ground < adjacent.top()) {
          ground = adjacent.top();
          containment = true;
        }
      }
    }
    ground = (int) Geo.clamp(ground, Geo.MIN_Y + 1, Geo.MAX_Y - 17);
    Geometry result =
        new Geometry(
            x,
            z,
            natural,
            ground,
            w.level(),
            w.top(),
            w.kind(),
            w.owner(),
            containment,
            c.terrain().coverage());
    if (cache.size() >= 16384) cache.clear();
    cache.put(key, result);
    return result;
  }

  private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  public Column column(int x, int z) {
    Geometry g = geometry(x, z);
    if (g.kind() == Hydrology.Kind.LAKE) {
      Hydrology.Context c = context(x, z);
      Lake lake = c.water().lake();
      double slope = 0;
      for (int[] offset : NEIGHBOURS) {
        Geometry adjacent = geometry(x + offset[0] * 6, z + offset[1] * 6);
        if (adjacent.kind() == g.kind() && adjacent.owner().equals(g.owner()))
          slope = Math.max(slope, Math.abs(g.groundY() - adjacent.groundY()) / 6.0);
      }
      River.Hit river = c.water().river();
      boolean inlet =
          river != null
              && river.river().connects(lake, river.station(), river.width())
              && c.shore().distance() < Geo.horizontal(64);
      return new Column(g, painter.paintLake(g, slope, lake, c.shore().distance(), inlet));
    }
    if (g.wet()) return new Column(g, painter.paint(g, 0, false));
    // A submerged bed says nothing about the slope of the dry bank above it.
    double slope =
        Math.max(
            drySlope(g, geometry(x - 2, z), geometry(x + 2, z)),
            drySlope(g, geometry(x, z - 2), geometry(x, z + 2)));
    boolean beach = g.groundY() * Geo.METRES_Y <= 8 && hydrology.coastalBeach(x, z);
    return new Column(g, painter.paint(g, slope, beach));
  }

  static double drySlope(Geometry centre, Geometry a, Geometry b) {
    if (!a.wet() && !b.wet()) return Math.abs(a.groundY() - b.groundY()) / 4.0;
    if (!a.wet()) return Math.abs(centre.groundY() - a.groundY()) / 2.0;
    if (!b.wet()) return Math.abs(centre.groundY() - b.groundY()) / 2.0;
    return 0;
  }

  private Hydrology.Context context(int x, int z) {
    Map<Long, Hydrology.Context> cache = contexts.get();
    long key = Geo.hash(Geo.key(x, z));
    Hydrology.Context old = cache.get(key);
    if (old != null) return old;
    if (cache.size() >= 32768) cache.clear();
    Hydrology.Context result = hydrology.sample(x, z);
    cache.put(key, result);
    return result;
  }

  public void releaseThreadCache() {
    geometries.remove();
    contexts.remove();
  }
}
