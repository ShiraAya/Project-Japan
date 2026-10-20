package com.sora.pjr.core;

/** Uniform 0.5 geographic scale, with Tokyo station fixed at (0,0). */
public final class Geo {
  public static final double ORIGIN_LAT = 35.681236, ORIGIN_LON = 139.767125;
  public static final double SCALE = 0.5;
  public static final double METRES_XZ = 1.0 / SCALE, METRES_Y = 1.0 / SCALE;
  public static final double DEG_LAT = 111320,
      DEG_LON = DEG_LAT * Math.cos(Math.toRadians(ORIGIN_LAT));
  public static final int MIN_Y = -256, HEIGHT = 2256, MAX_Y = MIN_Y + HEIGHT, SEA = 0;

  private Geo() {}

  /** Convert a physical horizontal distance in metres to world blocks. */
  public static double horizontal(double metres) {
    return metres / METRES_XZ;
  }

  /** Convert a physical vertical distance in metres to world blocks. */
  public static double vertical(double metres) {
    return metres / METRES_Y;
  }

  public static double x(double lon) {
    return (lon - ORIGIN_LON) * DEG_LON / METRES_XZ;
  }

  public static double z(double lat) {
    return (ORIGIN_LAT - lat) * DEG_LAT / METRES_XZ;
  }

  public static double lon(double x) {
    return ORIGIN_LON + x * METRES_XZ / DEG_LON;
  }

  public static double lat(double z) {
    return ORIGIN_LAT - z * METRES_XZ / DEG_LAT;
  }

  public static long key(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  public static double clamp(double x, double a, double b) {
    return Math.max(a, Math.min(b, x));
  }

  public static double smooth(double a, double b, double x) {
    double t = clamp((x - a) / (b - a), 0, 1);
    return t * t * (3 - 2 * t);
  }

  public static long hash(long x) {
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
    return x ^ (x >>> 31);
  }

  public static double noise(int x, int z, long salt) {
    return (hash(key(x, z) ^ salt) >>> 11) * 0x1.0p-53;
  }

  public static double valueNoise(int x, int z, int scale, long salt) {
    int ax = Math.floorDiv(x, scale), az = Math.floorDiv(z, scale);
    double tx = smooth(0, 1, Math.floorMod(x, scale) / (double) scale),
        tz = smooth(0, 1, Math.floorMod(z, scale) / (double) scale);
    double a = noise(ax, az, salt) * (1 - tx) + noise(ax + 1, az, salt) * tx;
    double b = noise(ax, az + 1, salt) * (1 - tx) + noise(ax + 1, az + 1, salt) * tx;
    return a * (1 - tz) + b * tz;
  }
}
