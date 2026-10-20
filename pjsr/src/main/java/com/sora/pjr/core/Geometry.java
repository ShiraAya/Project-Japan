package com.sora.pjr.core;

/** Final solid/water envelope. Painting and vegetation cannot change this result. */
public record Geometry(
    int x,
    int z,
    int naturalY,
    int groundY,
    int waterY,
    int waterTop,
    Hydrology.Kind kind,
    String owner,
    boolean containment,
    double coastCoverage) {
  public Geometry {
    if (groundY < Geo.MIN_Y + 1 || groundY >= Geo.MAX_Y - 16)
      throw new IllegalArgumentException("Ground outside PJR build range");
    if (kind != Hydrology.Kind.DRY
        && (waterY <= groundY || waterTop < waterY || waterTop >= Geo.MAX_Y - 16))
      throw new IllegalArgumentException("Invalid water column " + x + "," + z);
  }

  public boolean wet() {
    return kind != Hydrology.Kind.DRY;
  }

  public int highest() {
    return wet() ? waterTop : groundY;
  }
}
