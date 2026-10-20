package com.sora.pjr.core;

import java.util.*;
import java.util.function.Function;

/** Conservative padded bounds, including negative-coordinate cells; immutable after publication. */
public final class SpatialIndex<T> {
  private final int size;
  private final Map<Long, List<T>> cells;

  public SpatialIndex(List<T> values, int size, Function<T, Bounds> bounds) {
    this.size = size;
    Map<Long, List<T>> tmp = new HashMap<>();
    for (T v : values) {
      Bounds b = bounds.apply(v);
      for (int x = cell(b.minX()); x <= cell(b.maxX()); x++)
        for (int z = cell(b.minZ()); z <= cell(b.maxZ()); z++)
          tmp.computeIfAbsent(Geo.key(x, z), ignored -> new ArrayList<>()).add(v);
    }
    Map<Long, List<T>> frozen = new HashMap<>();
    tmp.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
    // Packed neighbouring X/Z keys have correlated hashes. HashMap's collision handling avoids
    // the long linear probes of Map.copyOf's compact map, while this wrapper remains read-only.
    cells = Collections.unmodifiableMap(frozen);
  }

  private int cell(double v) {
    return (int) Math.floor(v / size);
  }

  public List<T> at(double x, double z) {
    return cells.getOrDefault(Geo.key(cell(x), cell(z)), List.of());
  }

  public int cellCount() {
    return cells.size();
  }
}
