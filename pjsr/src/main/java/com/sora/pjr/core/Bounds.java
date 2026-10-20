package com.sora.pjr.core;

public record Bounds(double minX, double minZ, double maxX, double maxZ) {
  public Bounds {
    if (!Double.isFinite(minX)
        || !Double.isFinite(minZ)
        || !Double.isFinite(maxX)
        || !Double.isFinite(maxZ)
        || minX > maxX
        || minZ > maxZ) throw new IllegalArgumentException("Invalid spatial bounds");
  }

  public Bounds expand(double n) {
    return new Bounds(minX - n, minZ - n, maxX + n, maxZ + n);
  }

  public boolean contains(double x, double z) {
    return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
  }

  public double distanceSquared(double x, double z) {
    double dx = Math.max(Math.max(minX - x, 0), x - maxX),
        dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
    return dx * dx + dz * dz;
  }
}
