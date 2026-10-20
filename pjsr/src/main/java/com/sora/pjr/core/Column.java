package com.sora.pjr.core;

import static com.sora.pjr.core.SurfacePainter.Material.*;

/** Single block contract shared by chunk fill, base-column queries and verification. */
public record Column(Geometry geometry, SurfacePainter.Layers layers) {
  public SurfacePainter.Material at(int y) {
    if (y < Geo.MIN_Y || y >= Geo.MAX_Y) return AIR;
    if (y == Geo.MIN_Y) return BEDROCK;
    if (y <= geometry.groundY()) {
      if (y == geometry.groundY()) return layers.top();
      return y > geometry.groundY() - layers.thickness() ? layers.below() : STONE;
    }
    if (geometry.wet() && y <= geometry.waterTop())
      return y > geometry.waterY() ? FALLING_WATER : WATER;
    if (layers.snow() && y == geometry.groundY() + 1) return SNOW;
    return AIR;
  }

  public int surfaceHeight() {
    return geometry.highest() + 1 + (layers.snow() ? 1 : 0);
  }

  public int oceanFloorHeight() {
    return geometry.groundY() + 1;
  }
}
