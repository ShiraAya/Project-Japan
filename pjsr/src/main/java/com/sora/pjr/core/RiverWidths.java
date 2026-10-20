package com.sora.pjr.core;

import static com.sora.pjr.core.RawGeography.XY;

import java.util.*;

/** Slow downstream growth with an upstream-propagated clearance ceiling, never local pinches. */
final class RiverWidths {
  private record Edge(XY a, XY b, double start, double end) {
    Bounds bounds() {
      return new Bounds(
          Math.min(a.x(), b.x()),
          Math.min(a.z(), b.z()),
          Math.max(a.x(), b.x()),
          Math.max(a.z(), b.z()));
    }

    double fraction(XY p) {
      double dx = b.x() - a.x(), dz = b.z() - a.z();
      return Geo.clamp(
          ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / Math.max(1e-12, dx * dx + dz * dz), 0, 1);
    }
  }

  static List<River.Point> build(List<XY> route, double length, double source, double mouth) {
    int n = route.size();
    double[] stations = new double[n], widths = new double[n];
    List<Edge> edges = new ArrayList<>();
    for (int i = 1; i < n; i++) {
      stations[i] = stations[i - 1] + RouteMath.distance(route.get(i - 1), route.get(i));
      edges.add(new Edge(route.get(i - 1), route.get(i), stations[i - 1], stations[i]));
    }
    double reach = mouth + Geo.horizontal(48);
    SpatialIndex<Edge> index = new SpatialIndex<>(edges, 128, e -> e.bounds().expand(reach));
    for (int i = 0; i < n; i++) {
      XY p = route.get(i);
      double progress=stations[i]/length;
      double early=Math.pow(progress,2.6), late=Math.pow(progress,2.45);
      double nominal = source + (mouth - source) * (early+(late-early)*Geo.smooth(.50,.85,progress));
      double limited = nominal;
      // Curvature alone no longer narrows a river. Only non-adjacent overlap is a safety limit.
      for (Edge edge : index.at(p.x(), p.z())) {
        double t = edge.fraction(p), s = edge.start() + t * (edge.end() - edge.start());
        if (Math.abs(s - stations[i]) < Math.max(Geo.horizontal(192), nominal * 3)) continue;
        double distance =
            Math.hypot(
                p.x() - edge.a().x() - t * (edge.b().x() - edge.a().x()),
                p.z() - edge.a().z() - t * (edge.b().z() - edge.a().z()));
        limited = Math.min(limited, (distance - Geo.horizontal(12)) * 0.65);
      }
      widths[i] = Math.max(Geo.horizontal(4), limited);
    }
    // A downstream neck holds back earlier growth instead of pinching an already-wide reach.
    // The suffix minimum is monotone; the forward slew limit preserves that property.
    for (int i = n - 2; i >= 0; i--) widths[i] = Math.min(widths[i], widths[i + 1]);
    // Keep the upper half's slow growth, but release the overly strict full-length average
    // limit downstream. A smooth allowance recovers broad lower reaches over kilometres,
    // while the absolute slew cap and clearance ceiling still prevent abrupt flares/overlap.
    double averageRate = Math.max(0, mouth - source) / Math.max(1, length);
    for (int i = 1; i < n; i++) {
      double release = Geo.smooth(0.50, 0.85, stations[i] / length);
      double growthRate = Math.min(0.0015 + 0.0025 * release, averageRate * (1 + 3 * release));
      widths[i] = Math.min(widths[i], widths[i - 1] + (stations[i] - stations[i - 1]) * growthRate);
    }
    List<River.Point> points = new ArrayList<>();
    for (int i = 0; i < n; i++)
      points.add(new River.Point(route.get(i).x(), route.get(i).z(), stations[i], widths[i]));
    return points;
  }
}
