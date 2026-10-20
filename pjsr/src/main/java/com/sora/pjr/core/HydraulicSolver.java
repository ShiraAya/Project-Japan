package com.sora.pjr.core;

import java.util.*;

/**
 * Independent constrained least-squares grade solver. Pool-adjacent-violators fits a descending
 * grade between hard geographic anchors. A Lipschitz envelope then limits the grade to one vertical
 * block per horizontal block before quantization.
 */
public final class HydraulicSolver {
  public record Anchor(double station, int level, String reason) {}

  private HydraulicSolver() {}

  public static short[] solve(
      double[] target, double spacing, double length, List<Anchor> requested) {
    return solve(target, spacing, length, requested, false);
  }

  /**
   * A river grade must not average a valley floor upward. Use the descending lower envelope of
   * channel support instead; hard lake/junction anchors and the maximum drop still take priority.
   */
  public static short[] solveTerrain(
      double[] target, double spacing, double length, List<Anchor> requested) {
    return solve(target, spacing, length, requested, true);
  }

  public static short[] solveTerrain(
      double[] target, double spacing, double length, List<Anchor> requested, double[] maxDrop) {
    return solve(target, spacing, length, requested, true, maxDrop);
  }

  private static short[] solve(
      double[] target,
      double spacing,
      double length,
      List<Anchor> requested,
      boolean terrainBound) {
    double[] maxDrop = new double[target.length - 1];
    Arrays.fill(maxDrop, spacing);
    return solve(target, spacing, length, requested, terrainBound, maxDrop);
  }

  private static short[] solve(
      double[] target,
      double spacing,
      double length,
      List<Anchor> requested,
      boolean terrainBound,
      double[] maxDrop) {
    if (target.length < 2 || spacing <= 0 || length <= 0)
      throw new IllegalArgumentException("Bad hydraulic domain");
    if (maxDrop.length != target.length - 1)
      throw new IllegalArgumentException("Drop-domain mismatch");
    double[] capacity = new double[target.length];
    for (int i = 0; i < maxDrop.length; i++) {
      if (!Double.isFinite(maxDrop[i]) || maxDrop[i] < 0 || maxDrop[i] > spacing + 1e-9)
        throw new IllegalArgumentException("Invalid local grade bound");
      capacity[i + 1] = capacity[i] + maxDrop[i];
    }
    TreeMap<Integer, Anchor> anchors = new TreeMap<>();
    for (Anchor a : requested) {
      int i = (int) Math.round(Geo.clamp(a.station() / spacing, 0, target.length - 1));
      Anchor old = anchors.putIfAbsent(i, a);
      if (old != null && old.level() != a.level())
        throw new IllegalArgumentException("Conflicting hydraulic anchors: " + old + " / " + a);
    }
    if (!anchors.containsKey(0) || !anchors.containsKey(target.length - 1))
      throw new IllegalArgumentException("Missing endpoint anchors");
    double[] grade = new double[target.length];
    List<Integer> indices = new ArrayList<>(anchors.keySet());
    for (int section = 1; section < indices.size(); section++) {
      int from = indices.get(section - 1), to = indices.get(section);
      int high = anchors.get(from).level(), low = anchors.get(to).level();
      if (high < low)
        throw new IllegalArgumentException(
            "Upstream anchor below downstream: " + anchors.get(from) + " / " + anchors.get(to));
      if (high - low > capacity[to] - capacity[from] + 1e-7)
        throw new IllegalArgumentException(
            "Infeasible grade between " + anchors.get(from) + " / " + anchors.get(to));
      grade[from] = high;
      grade[to] = low;
      int n = to - from - 1;
      if (n <= 0) continue;
      if (terrainBound) {
        for (int i = from + 1; i < to; i++)
          grade[i] = Math.min(grade[i - 1], Geo.clamp(target[i], low, high));
      } else {
        double[] sum = new double[n];
        int[] weight = new int[n], starts = new int[n];
        int blocks = 0;
        for (int i = from + 1; i < to; i++) {
          sum[blocks] = Geo.clamp(target[i], low, high);
          weight[blocks] = 1;
          starts[blocks] = i;
          blocks++;
          while (blocks > 1
              && sum[blocks - 2] / weight[blocks - 2] < sum[blocks - 1] / weight[blocks - 1]) {
            sum[blocks - 2] += sum[blocks - 1];
            weight[blocks - 2] += weight[blocks - 1];
            blocks--;
          }
        }
        for (int b = 0; b < blocks; b++)
          for (int i = starts[b]; i < starts[b] + weight[b]; i++) grade[i] = sum[b] / weight[b];
      }
      // Forward and backward cones preserve anchor feasibility and prevent vertical curtains.
      for (int i = from + 1; i < to; i++)
        grade[i] = Math.max(grade[i], grade[i - 1] - maxDrop[i - 1]);
      for (int i = to - 1; i > from; i--) grade[i] = Math.min(grade[i], grade[i + 1] + maxDrop[i]);
    }
    short[] result = new short[(int) Math.ceil(length) + 1];
    int previous = Integer.MAX_VALUE;
    for (int s = 0; s < result.length; s++) {
      double position = Math.min(length, s) / spacing;
      int i = Math.min(grade.length - 2, (int) position);
      double t = Math.min(1, position - i);
      int y = (int) Math.ceil(grade[i] * (1 - t) + grade[i + 1] * t - 1e-9);
      y = Math.min(previous, y);
      if (y < Geo.MIN_Y + 2 || y >= Geo.MAX_Y - 16)
        throw new IllegalArgumentException("Profile outside world range");
      result[s] = (short) y;
      previous = y;
    }
    return result;
  }
}
