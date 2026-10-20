package com.sora.pjr.core;

import java.util.*;

/**
 * An official vector route and an independently solved monotone hydraulic grade, independent of the
 * DEM.
 */
public final class River {
  public record Gate(String lakeId, String lakeName, double first, double last, int waterY) {}

  public record Point(double x, double z, double station, double width) {}

  public record Hit(River river, double station, double distance, double width) {}

  public record Segment(River river, Point a, Point b) {
    public Bounds bounds() {
      return new Bounds(
          Math.min(a.x, b.x), Math.min(a.z, b.z), Math.max(a.x, b.x), Math.max(a.z, b.z));
    }

    public Hit nearest(double x, double z) {
      double dx = b.x - a.x, dz = b.z - a.z;
      double t =
          Geo.clamp(((x - a.x) * dx + (z - a.z) * dz) / Math.max(1e-12, dx * dx + dz * dz), 0, 1);
      return new Hit(
          river,
          a.station + t * (b.station - a.station),
          Math.hypot(x - a.x - t * dx, z - a.z - t * dz),
          a.width + t * (b.width - a.width));
    }
  }

  private final String name, sourceLake, terminalLake, sourceRiver, terminalRiver;
  private final double mouthDistance, totalLength;
  private final boolean sea;
  private final List<Point> points;
  private final short[] profile;
  private final List<Gate> gates;
  private final List<Segment> segments;

  public River(
      String name,
      String sourceLake,
      String terminalLake,
      String sourceRiver,
      String terminalRiver,
      double mouthDistance,
      double totalLength,
      boolean sea,
      List<Point> points,
      short[] profile,
      List<Gate> gates) {
    this.name = name;
    this.sourceLake = sourceLake;
    this.terminalLake = terminalLake;
    this.sourceRiver = sourceRiver;
    this.terminalRiver = terminalRiver;
    this.mouthDistance = mouthDistance;
    this.totalLength = totalLength;
    this.sea = sea;
    this.points = List.copyOf(points);
    this.profile = profile.clone();
    this.gates = List.copyOf(gates);
    if (name.isBlank()
        || points.size() < 2
        || profile.length < 2
        || mouthDistance <= 0
        || totalLength < mouthDistance) throw new IllegalArgumentException("Invalid river " + name);
    for (int i = 0; i < profile.length; i++)
      if (profile[i] < Geo.MIN_Y + 2
          || profile[i] >= Geo.MAX_Y - 32
          || (i > 0 && profile[i] > profile[i - 1]))
        throw new IllegalArgumentException(
            "Non-monotone or out-of-bounds profile: " + name + " at " + i);
    List<Segment> result = new ArrayList<>();
    for (int i = 1; i < points.size(); i++) {
      if (points.get(i).station <= points.get(i - 1).station)
        throw new IllegalArgumentException("Repeated station " + name);
      result.add(new Segment(this, points.get(i - 1), points.get(i)));
    }
    segments = List.copyOf(result);
  }

  public int water(double station) {
    // The terminal anchor is stored at ceil(length), while regular samples use integer stations.
    // A fractional physical endpoint must read its exact anchor instead of the preceding sample.
    if (station >= totalLength) return profile[profile.length - 1];
    return profile[(int) Geo.clamp(Math.floor(station), 0, profile.length - 1)];
  }

  public double bankWater(double station) {
    return (water(station - Geo.horizontal(192))
            + water(station) * 2
            + water(station + Geo.horizontal(192)))
        / 4.0;
  }

  public int top(double station) {
    return water(station - 1.5);
  }

  public boolean connects(Lake l, double station, double width) {
    double apron = Math.max(Geo.horizontal(128), width);
    for (Gate gate : gates)
      if (gate.lakeId.equals(l.id())
          && station >= gate.first - apron
          && station <= gate.last + apron) return true;
    return sourceLake.equals(l.name()) && station < Math.max(Geo.horizontal(256), width * 2)
        || terminalLake.equals(l.name())
            && station > mouthDistance - Math.max(Geo.horizontal(256), width * 2);
  }

  public boolean related(River other) {
    return name.equals(other.terminalRiver)
        || name.equals(other.sourceRiver)
        || other.name.equals(terminalRiver)
        || other.name.equals(sourceRiver);
  }

  public Point at(double station) {
    int lo = 0, hi = points.size() - 1;
    while (lo + 1 < hi) {
      int m = (lo + hi) / 2;
      if (points.get(m).station <= station) lo = m;
      else hi = m;
    }
    Point a = points.get(lo), b = points.get(hi);
    double t = Geo.clamp((station - a.station) / (b.station - a.station), 0, 1);
    return new Point(
        a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t, station, a.width + (b.width - a.width) * t);
  }

  public String name() {
    return name;
  }

  public String terminalRiver() {
    return terminalRiver;
  }

  public String sourceRiver() {
    return sourceRiver;
  }

  public String sourceLake() {
    return sourceLake;
  }

  public String terminalLake() {
    return terminalLake;
  }

  public double mouthDistance() {
    return mouthDistance;
  }

  public double totalLength() {
    return totalLength;
  }

  public boolean sea() {
    return sea;
  }

  public List<Point> points() {
    return points;
  }

  public List<Segment> segments() {
    return segments;
  }

  public int profileSize() {
    return profile.length;
  }

  public List<Gate> gates() {
    return gates;
  }
}
