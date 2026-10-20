package com.sora.pjr.core;

import java.util.*;

/** Rings retain islands. Edge distance uses a BVH; inclusion uses horizontal scan bands. */
public final class Polygon {
  public static final class Ring {
    private final int[] x, z;
    private final Bounds bounds;
    private final Node edges;
    private final Map<Integer, int[]> bands;

    public Ring(int[] x, int[] z) {
      if (x.length != z.length || x.length < 3) throw new IllegalArgumentException("Invalid ring");
      this.x = x.clone();
      this.z = z.clone();
      double minX = Arrays.stream(x).min().orElseThrow(),
          maxX = Arrays.stream(x).max().orElseThrow();
      double minZ = Arrays.stream(z).min().orElseThrow(),
          maxZ = Arrays.stream(z).max().orElseThrow();
      bounds = new Bounds(minX, minZ, maxX, maxZ);
      Integer[] order = new Integer[x.length];
      Map<Integer, List<Integer>> scan = new HashMap<>();
      for (int i = 0; i < x.length; i++) {
        order[i] = i;
        int j = (i + 1) % x.length;
        for (int b = Math.floorDiv(Math.min(z[i], z[j]), 64);
            b <= Math.floorDiv(Math.max(z[i], z[j]), 64);
            b++) scan.computeIfAbsent(b, k -> new ArrayList<>()).add(i);
      }
      Map<Integer, int[]> frozen = new HashMap<>();
      scan.forEach((k, v) -> frozen.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
      bands = Map.copyOf(frozen);
      edges = build(order, 0, order.length);
    }

    private Node build(Integer[] order, int from, int to) {
      double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
      for (int n = from; n < to; n++) {
        int i = order[n], j = (i + 1) % x.length;
        minX = Math.min(minX, Math.min(x[i], x[j]));
        maxX = Math.max(maxX, Math.max(x[i], x[j]));
        minZ = Math.min(minZ, Math.min(z[i], z[j]));
        maxZ = Math.max(maxZ, Math.max(z[i], z[j]));
      }
      Bounds b = new Bounds(minX, minZ, maxX, maxZ);
      if (to - from <= 12)
        return new Node(
            b, Arrays.stream(order, from, to).mapToInt(Integer::intValue).toArray(), null, null);
      boolean east = maxX - minX >= maxZ - minZ;
      Arrays.sort(
          order,
          from,
          to,
          Comparator.comparingDouble(
              i -> east ? x[i] + x[(i + 1) % x.length] : z[i] + z[(i + 1) % z.length]));
      int mid = (from + to) / 2;
      return new Node(b, null, build(order, from, mid), build(order, mid, to));
    }

    public boolean contains(double px, double pz) {
      if (!bounds.contains(px, pz)) return false;
      int[] scan = bands.get((int) Math.floor(pz / 64));
      if (scan == null) return false;
      boolean inside = false;
      for (int i : scan) {
        int j = (i + 1) % x.length;
        if ((z[i] > pz) != (z[j] > pz) && px < (x[j] - x[i]) * (pz - z[i]) / (z[j] - z[i]) + x[i])
          inside = !inside;
      }
      return inside;
    }

    public double distance(double px, double pz) {
      return Math.sqrt(distance(edges, px, pz, Double.POSITIVE_INFINITY));
    }

    private double distance(Node n, double px, double pz, double best) {
      if (n.bounds.distanceSquared(px, pz) > best) return best;
      if (n.indices != null) {
        for (int i : n.indices) {
          int j = (i + 1) % x.length;
          double dx = x[j] - x[i], dz = z[j] - z[i];
          double t =
              Geo.clamp(
                  ((px - x[i]) * dx + (pz - z[i]) * dz) / Math.max(1, dx * dx + dz * dz), 0, 1);
          double ex = px - (x[i] + t * dx), ez = pz - (z[i] + t * dz);
          best = Math.min(best, ex * ex + ez * ez);
        }
        return best;
      }
      Node a = n.left, b = n.right;
      if (a.bounds.distanceSquared(px, pz) > b.bounds.distanceSquared(px, pz)) {
        a = n.right;
        b = n.left;
      }
      return distance(b, px, pz, distance(a, px, pz, best));
    }

    public Bounds bounds() {
      return bounds;
    }

    public int size() {
      return x.length;
    }

    public int x(int i) {
      return x[i];
    }

    public int z(int i) {
      return z[i];
    }

    private record Node(Bounds bounds, int[] indices, Node left, Node right) {}
  }

  private final Ring outer;
  private final List<Ring> holes;

  public Polygon(Ring outer, List<Ring> holes) {
    this.outer = outer;
    this.holes = List.copyOf(holes);
  }

  public boolean contains(double x, double z) {
    if (!outer.contains(x, z)) return false;
    for (Ring hole : holes) if (hole.contains(x, z)) return false;
    return true;
  }

  public double distance(double x, double z) {
    double d = outer.distance(x, z);
    for (Ring hole : holes)
      if (hole.bounds().distanceSquared(x, z) < d * d) d = Math.min(d, hole.distance(x, z));
    return d;
  }

  public boolean wetBlock(int x, int z, double edgeDistance) {
    boolean centre = contains(x + 0.5, z + 0.5);
    if (edgeDistance >= 1) return centre;
    int wet = 0;
    for (int a = 0; a < 4; a++)
      for (int b = 0; b < 4; b++) if (contains(x + (a + 0.5) / 4, z + (b + 0.5) / 4)) wet++;
    return wet > 8 || wet == 8 && centre;
  }

  public Bounds bounds() {
    return outer.bounds();
  }

  public Ring outer() {
    return outer;
  }

  public List<Ring> holes() {
    return holes;
  }
}
