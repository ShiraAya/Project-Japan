package com.sora.pjr.core;

/** Consumes the final geometry, including cut/blended banks. Never reads raw DEM height. */
public final class SurfacePainter {
  public enum Material {
    AIR,
    BEDROCK,
    STONE,
    DIRT,
    GRASS,
    COARSE_DIRT,
    PODZOL,
    SAND,
    GRAVEL,
    CLAY,
    SNOW_BLOCK,
    WATER,
    FALLING_WATER,
    SNOW
  }

  public record Layers(Material top, Material below, int thickness, boolean snow) {}

  /**
   * Depositional patches vary independently of absolute depth, including genuinely shallow lakes.
   */
  public Layers paintLake(
      Geometry g, double bedSlope, Lake lake, double shoreDistance, boolean inlet) {
    double broad = Geo.valueNoise(g.x(), g.z(), (int) Geo.horizontal(128), 0x4c414b45424544L);
    double fine = Geo.valueNoise(g.x(), g.z(), (int) Geo.horizontal(24), 0x534544494d454e54L);
    double n = broad * 0.8 + fine * 0.2;
    double depth = (g.waterY() - g.groundY()) / (double) lake.basinDepth();
    if (bedSlope > 1.0) return new Layers(Material.STONE, Material.STONE, 1, false);
    if (bedSlope > 0.45 || inlet && n < 0.65)
      return new Layers(Material.GRAVEL, Material.GRAVEL, 2, false);
    if (shoreDistance < Geo.horizontal(24))
      return new Layers(
          n < 0.38 ? Material.SAND : n < 0.62 ? Material.GRAVEL : Material.DIRT,
          Material.DIRT,
          2,
          false);
    if (depth > 0.55)
      return new Layers(
          n < 0.35 ? Material.GRAVEL : n < 0.53 ? Material.DIRT : Material.CLAY,
          Material.CLAY,
          3,
          false);
    return new Layers(
        n < 0.33
            ? Material.SAND
            : n < 0.52 ? Material.DIRT : n < 0.72 ? Material.CLAY : Material.GRAVEL,
        Material.CLAY,
        3,
        false);
  }

  public Layers paint(Geometry g, double slope) {
    return paint(g, slope, false);
  }

  public Layers paint(Geometry g, double slope, boolean coastalBeach) {
    double n =
        0.75 * Geo.valueNoise(g.x(), g.z(), (int) Geo.horizontal(256), 0x504A52L)
            + 0.25 * Geo.valueNoise(g.x(), g.z(), (int) Geo.horizontal(64), 0x524546494e4544L);
    if (g.wet()) {
      if (g.kind() == Hydrology.Kind.OCEAN)
        return new Layers(
            (g.waterY() - g.groundY()) * Geo.METRES_Y > 80 ? Material.GRAVEL : Material.SAND,
            Material.SAND,
            3,
            false);
      if (g.kind() == Hydrology.Kind.RIVER)
        return new Layers(n < 0.72 ? Material.GRAVEL : Material.SAND, Material.GRAVEL, 2, false);
      return new Layers(
          n < 0.30
              ? Material.SAND
              : n < 0.50 ? Material.DIRT : n < 0.75 ? Material.CLAY : Material.GRAVEL,
          Material.CLAY,
          3,
          false);
    }
    double metres = g.groundY() * Geo.METRES_Y;
    double snowLine = Geo.clamp(2250 - (Geo.lat(g.z()) - 35) * 95, 1450, 2550);
    double physicalSlope = slope * Geo.METRES_Y / Geo.METRES_XZ;
    if (physicalSlope > 0.675)
      return new Layers(
          Material.STONE, Material.STONE, 1, metres >= snowLine && physicalSlope < 1.1);
    if (metres <= 8 && coastalBeach) return new Layers(Material.SAND, Material.SAND, 4, false);
    if (metres >= snowLine) return new Layers(Material.SNOW_BLOCK, Material.STONE, 2, true);
    if (metres >= 1650)
      return new Layers(n < 0.70 ? Material.STONE : Material.PODZOL, Material.STONE, 2, false);
    if (metres >= 850)
      return new Layers(
          n < 0.22 ? Material.STONE : n < 0.7 ? Material.PODZOL : Material.COARSE_DIRT,
          Material.DIRT,
          3,
          false);
    if (metres >= 420)
      return new Layers(n < 0.6 ? Material.PODZOL : Material.COARSE_DIRT, Material.DIRT, 3, false);
    return new Layers(Material.GRASS, Material.DIRT, 3, false);
  }
}
