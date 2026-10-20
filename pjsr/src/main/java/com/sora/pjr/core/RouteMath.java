package com.sora.pjr.core;

import static com.sora.pjr.core.RawGeography.XY;

import java.util.*;

/** Small independent polyline operations; no name-specific route repair code. */
final class RouteMath {
  record Projection(double x, double z, double station, double distance, int segment) {}

  static double distance(XY a, XY b) {
    return Math.hypot(a.x() - b.x(), a.z() - b.z());
  }

  static double length(List<XY> p) {
    double d = 0;
    for (int i = 1; i < p.size(); i++) d += distance(p.get(i - 1), p.get(i));
    return d;
  }

  static Projection nearest(List<XY> points, XY q) {
    double station = 0;
    Projection best = null;
    for (int i = 1; i < points.size(); i++) {
      XY a = points.get(i - 1), b = points.get(i);
      double dx = b.x() - a.x(), dz = b.z() - a.z(), length = distance(a, b);
      double t =
          Geo.clamp(
              ((q.x() - a.x()) * dx + (q.z() - a.z()) * dz) / Math.max(1e-10, length * length),
              0,
              1);
      double x = a.x() + t * dx, z = a.z() + t * dz, d = Math.hypot(q.x() - x, q.z() - z);
      if (best == null || d < best.distance)
        best = new Projection(x, z, station + t * length, d, i - 1);
      station += length;
    }
    return best;
  }

  static List<XY> densify(List<XY> input) {
    List<XY> output = new ArrayList<>();
    output.add(input.get(0));
    for (int i = 1; i < input.size(); i++) {
      XY a = input.get(i - 1), b = input.get(i);
      int n = Math.max(1, (int) Math.ceil(distance(a, b) / Geo.horizontal(64)));
      for (int j = 1; j <= n; j++)
        output.add(new XY(a.x() + (b.x() - a.x()) * j / n, a.z() + (b.z() - a.z()) * j / n));
    }
    return output;
  }

  static XY interiorNear(Polygon polygon, XY from) {
    Polygon.Ring ring = polygon.outer();
    List<XY> edge = new ArrayList<>();
    for (int i = 0; i < ring.size(); i++) edge.add(new XY(ring.x(i), ring.z(i)));
    edge.add(edge.get(0));
    Projection p = nearest(edge, from);
    for (double r = Geo.horizontal(16); r <= Geo.horizontal(256); r *= 2)
      for (int i = 0; i < 32; i++) {
        double angle = i * Math.PI / 16,
            x = p.x + Math.cos(angle) * r,
            z = p.z + Math.sin(angle) * r;
        if (polygon.contains(x, z)) return new XY(x, z);
      }
    throw new IllegalStateException("Could not find interior near lake shore");
  }
}
