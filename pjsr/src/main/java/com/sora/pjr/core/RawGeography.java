package com.sora.pjr.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Versioned geographic inputs only. Contains no river water levels or precomputed banks. */
public record RawGeography(List<Waterbody> lakes, List<Watercourse> rivers) {
  public record XY(double x, double z) {}

  public record Waterbody(
      String id,
      String name,
      boolean authored,
      double elevationMetres,
      double maxDepthMetres,
      Polygon polygon) {}

  public record Watercourse(
      String name,
      String officialCode,
      String sourceLake,
      String terminalLake,
      String parent,
      List<XY> points) {}

  public static RawGeography load() {
    InputStream raw = RawGeography.class.getResourceAsStream("/assets/pjr/world/geography.pjrg.gz");
    if (raw == null) throw new IllegalStateException("PJR geographic source data is missing");
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
      if (in.readInt() != 0x504a5247 || in.readInt() != 2)
        throw new IOException("Unknown geographic format");
      if (Double.compare(in.readDouble(), Geo.METRES_XZ) != 0
          || Double.compare(in.readDouble(), Geo.METRES_Y) != 0
          || Double.compare(in.readDouble(), Geo.ORIGIN_LON) != 0
          || Double.compare(in.readDouble(), Geo.ORIGIN_LAT) != 0)
        throw new IOException(
            "Geographic resource projection differs from PJR runtime; recompile geography");
      List<Waterbody> lakes = new ArrayList<>();
      int count = count(in, 1, 4096);
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < count; i++) {
        String id = text(in), name = text(in);
        boolean authored = in.readInt() != 0;
        double elevation = in.readDouble(), depth = in.readDouble();
        Polygon.Ring outer = ring(in);
        int holes = count(in, 0, 10000);
        List<Polygon.Ring> inner = new ArrayList<>();
        for (int h = 0; h < holes; h++) inner.add(ring(in));
        if (!ids.add(id)) throw new IOException("Duplicate waterbody " + id);
        lakes.add(new Waterbody(id, name, authored, elevation, depth, new Polygon(outer, inner)));
      }
      List<Watercourse> rivers = new ArrayList<>();
      // Bound malformed allocations; the selected release set is verified separately.
      count = count(in, 1, 4096);
      ids.clear();
      for (int i = 0; i < count; i++) {
        String name = text(in),
            code = text(in),
            source = text(in),
            terminal = text(in),
            parent = text(in);
        int points = count(in, 2, 1000000);
        List<XY> line = new ArrayList<>();
        for (int p = 0; p < points; p++) {
          double x = in.readDouble(), z = in.readDouble();
          if (!Double.isFinite(x + z)) throw new IOException("Invalid river coordinate");
          line.add(new XY(x, z));
        }
        if (!ids.add(name)) throw new IOException("Duplicate watercourse " + name);
        rivers.add(new Watercourse(name, code, source, terminal, parent, List.copyOf(line)));
      }
      if (in.read() != -1) throw new IOException("Trailing source data");
      return new RawGeography(List.copyOf(lakes), List.copyOf(rivers));
    } catch (IOException e) {
      throw new IllegalStateException("Invalid PJR geographic inputs", e);
    }
  }

  private static int count(DataInputStream in, int min, int max) throws IOException {
    int n = in.readInt();
    if (n < min || n > max) throw new IOException("Count out of bounds: " + n);
    return n;
  }

  private static String text(DataInputStream in) throws IOException {
    int n = count(in, 0, 100000);
    byte[] bytes = in.readNBytes(n);
    if (bytes.length != n) throw new EOFException();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static Polygon.Ring ring(DataInputStream in) throws IOException {
    int n = count(in, 3, 200000);
    int[] x = new int[n], z = new int[n];
    for (int i = 0; i < n; i++) {
      x[i] = in.readInt();
      z[i] = in.readInt();
    }
    return new Polygon.Ring(x, z);
  }
}
