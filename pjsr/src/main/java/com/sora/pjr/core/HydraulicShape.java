package com.sora.pjr.core;

import java.io.*;
import java.util.List;

/** Portable validation of derived geometry; raw geographic inputs still use exact SHA-256. */
final class HydraulicShape {
  private static final int VALUES = 14;
  record Signature(int count, double[] values) {
    boolean matches(List<River.Point> points) {
      if (points.size() != count) return false;
      double[] actual = capture(points).values;
      for (int i = 0; i < VALUES; i++) if (!close(values[i], actual[i])) return false;
      return true;
    }
  }

  static boolean close(double a, double b) {
    return Double.isFinite(a) && Double.isFinite(b)
        && Math.abs(a - b) <= 1e-6 + 1e-12 * Math.max(Math.abs(a), Math.abs(b));
  }

  static Signature capture(List<River.Point> points) {
    int n = points.size();
    if (n < 2 || n > 2_000_000) throw new IllegalArgumentException("Invalid river point count");
    River.Point first = points.get(0), last = points.get(n - 1);
    double[] v = {first.x(), first.z(), last.x(), last.z(),
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0, 0, 0};
    double previous = -1;
    for (int i = 0; i < n; i++) {
      River.Point p = points.get(i);
      if (!Double.isFinite(p.x()) || !Double.isFinite(p.z()) || !Double.isFinite(p.station())
          || !Double.isFinite(p.width()) || p.width() <= 0 || p.station() < previous)
        throw new IllegalArgumentException("Invalid derived river geometry");
      previous = p.station();
      v[4] = Math.min(v[4], p.x()); v[5] = Math.max(v[5], p.x());
      v[6] = Math.min(v[6], p.z()); v[7] = Math.max(v[7], p.z());
      v[8] = Math.min(v[8], p.width()); v[9] = Math.max(v[9], p.width());
      double weight = i + 1.0;
      v[10] += p.x() * weight; v[11] += p.z() * weight;
      v[12] += p.station() * weight; v[13] += p.width() * weight;
    }
    double weights = n * (n + 1.0) / 2;
    for (int i = 10; i < VALUES; i++) v[i] /= weights;
    return new Signature(n, v);
  }

  static void write(DataOutputStream out, List<River.Point> points) throws IOException {
    Signature s = capture(points);
    out.writeInt(s.count);
    for (double value : s.values) out.writeDouble(value);
  }

  static Signature read(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 2 || count > 2_000_000) throw new IOException("Invalid baked geometry count");
    double[] values = new double[VALUES];
    for (int i = 0; i < VALUES; i++) {
      values[i] = in.readDouble();
      if (!Double.isFinite(values[i])) throw new IOException("Non-finite baked geometry");
    }
    return new Signature(count, values);
  }
}
