package com.sora.pjr.core;

/** Immutable lake with a shape-scaled model basin, not a surveyed bathymetric raster. */
public final class Lake {
  private final String id, name;
  private final boolean authored;
  private final int waterY, maxDepth, basinDepth, representativeX, representativeZ;
  private final double area, bankWidth, basinRadius;
  private final Polygon polygon;

  public Lake(
      String id,
      String name,
      boolean authored,
      int waterY,
      int maxDepth,
      double area,
      double bankWidth,
      int representativeX,
      int representativeZ,
      Polygon polygon) {
    if (id.isBlank()
        || name.isBlank()
        || maxDepth < 1
        || waterY < Geo.MIN_Y + 2
        || waterY >= Geo.MAX_Y - 32
        || area <= 0) throw new IllegalArgumentException("Invalid lake: " + id);
    this.id = id;
    this.name = name;
    this.authored = authored;
    this.waterY = waterY;
    this.maxDepth = maxDepth;
    // User-requested visual allowance, kept separate from the input/estimated depth.
    // Tiny ponds gain at most one block; deep lakes are unchanged.
    int extra = maxDepth <= 4 ? (area < 0.1 ? 1 : 2) : maxDepth <= 7 ? 1 : 0;
    this.basinDepth = maxDepth + extra;
    this.area = area;
    this.bankWidth = bankWidth;
    this.representativeX = representativeX;
    this.representativeZ = representativeZ;
    this.polygon = polygon;
    // The loader finds this interior point by maximising shore distance across the lake.
    // Narrow reservoirs therefore reach their model depth instead of remaining a wide shelf.
    basinRadius = Math.max(1, polygon.distance(representativeX + 0.5, representativeZ + 0.5));
  }

  public int bed(double shoreDistance) {
    return bed(representativeX, representativeZ, shoreDistance);
  }

  public int bed(int x, int z, double shoreDistance) {
    double t = Geo.clamp((shoreDistance - 0.5) / Math.max(1, basinRadius - 0.5), 0, 1);
    double bowl = Math.pow(Geo.smooth(0, 1, t), 0.72);
    int scale = Math.max(12, (int) Math.min(Geo.horizontal(768), basinRadius * 0.65));
    double relief = 0.87 + 0.13 * Geo.valueNoise(x, z, scale, Geo.hash(id.hashCode()));
    double depth = 1 + (basinDepth - 1) * bowl * relief;
    return Math.max(
        Geo.MIN_Y + 1, waterY - Math.max(1, Math.min(basinDepth, (int) Math.round(depth))));
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public boolean authored() {
    return authored;
  }

  public int waterY() {
    return waterY;
  }

  public int maxDepth() {
    return maxDepth;
  }

  /** Actual generation limit; maxDepth() retains the original reference or estimated limit. */
  public int basinDepth() {
    return basinDepth;
  }

  public double area() {
    return area;
  }

  public double bankWidth() {
    return bankWidth;
  }

  public int representativeX() {
    return representativeX;
  }

  public int representativeZ() {
    return representativeZ;
  }

  public Polygon polygon() {
    return polygon;
  }
}
