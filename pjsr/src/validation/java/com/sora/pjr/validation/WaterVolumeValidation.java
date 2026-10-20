package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;

/** Regression for downstream volume and user-requested shallow-lake visual depth. */
public final class WaterVolumeValidation {
  private static long checks;

  private static void require(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    run(WorldEngine.load(), args.length == 0 ? "water-volume" : args[0], args.length == 0);
  }

  public static void verify(WorldEngine engine) throws Exception {
    run(engine, "water-volume", true);
  }

  private static int generatedDepth(Lake lake) throws Exception {
    // The same exporter can load 0.1.5 bytecode, which predates the explicit visual allowance.
    try {
      return (int) Lake.class.getMethod("basinDepth").invoke(lake);
    } catch (NoSuchMethodException missing) {
      return lake.maxDepth();
    }
  }

  private static void run(WorldEngine engine, String label, boolean check) throws Exception {
    checks = 0;
    WorldData data = engine.hydrology().data();
    Path root = Path.of("build/reports");
    Files.createDirectories(root);
    StringBuilder report =
        new StringBuilder(
            "PJR water volume " + (check ? "validation PASS" : "baseline export") + "\n");
    StringBuilder rivers =
        new StringBuilder(
            "river,progress,station,width,centre_depth,actual_kind,actual_depth,x,z,water\n");
    for (River r : data.rivers())
      for (int i = 0; i <= 100; i++) {
        double s = r.mouthDistance() * i / 100;
        River.Point p = r.at(s);
        River.Hit h = new River.Hit(r, s, 0, p.width());
        int depth = Hydrology.depth(h), x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
        Geometry g = engine.geometry(x, z);
        rivers
            .append(r.name())
            .append(',')
            .append(i / 100.0)
            .append(',')
            .append(s)
            .append(',')
            .append(p.width())
            .append(',')
            .append(depth)
            .append(',')
            .append(g.kind())
            .append(',')
            .append(g.wet() ? g.waterY() - g.groundY() : 0)
            .append(',')
            .append(x)
            .append(',')
            .append(z)
            .append(',')
            .append(g.waterY())
            .append('\n');
        if (check) {
          require(depth >= 1, "River bed must contain water");
          River.Hit edge = new River.Hit(r, s, Hydrology.radius(h), p.width());
          require(Hydrology.depth(edge) == 1, "Deeper thalweg must keep a shallow margin");
          double upstream =
              Geo.vertical(1.5 + Math.sqrt(p.width() * Geo.METRES_XZ) * .55 + i / 100.0 * 2);
          if (i <= 55)
            require(
                depth == Math.max(1, (int) Math.round(upstream)),
                "No lower-river bonus in headwaters");
        }
        if (List.of("Oi", "Shinano", "Tone", "Shonai").contains(r.name())
            && (i == 50 || i == 90 || i == 100))
          report.append(
              String.format(
                  Locale.ROOT,
                  "%s p=%.2f width=%.3f centreDepth=%d actual=%s/%d coordinate=%d,%d%n",
                  r.name(),
                  i / 100.0,
                  p.width(),
                  depth,
                  g.kind(),
                  g.wet() ? g.waterY() - g.groundY() : 0,
                  x,
                  z));
        if (check && r.name().equals("Oi")) {
          if (i == 50) require(p.width() < 15, "Keep modest middle-reach width");
          if (i == 90) {
            require(p.width() > 45, "Oi lower reach remains over-restricted");
            require(
                g.kind() == Hydrology.Kind.RIVER && g.waterY() - g.groundY() >= 6,
                "Actual lower Oi channel needs depth, not just a model value");
          }
          if (i == 100) require(p.width() > 60, "Restore broad lower Oi towards the mouth");
        }
      }
    Files.writeString(root.resolve(label + "-rivers.csv"), rivers);

    int enhanced = 0;
    StringBuilder lakes =
        new StringBuilder("id,name,reference_depth,generation_depth,water,area_km2\n");
    for (Lake l : data.lakes()) {
      int actual = generatedDepth(l), extra = actual - l.maxDepth();
      if (extra > 0) enhanced++;
      lakes
          .append(l.id())
          .append(',')
          .append(l.name())
          .append(',')
          .append(l.maxDepth())
          .append(',')
          .append(actual)
          .append(',')
          .append(l.waterY())
          .append(',')
          .append(l.area())
          .append('\n');
      if (check) {
        require(
            extra >= 0 && extra <= 2, "Shallow lake visual allowance must be modest " + l.name());
        if (l.maxDepth() >= 8) require(extra == 0, "Deep lakes retain their depth " + l.name());
        else require(extra > 0, "Shallow lake not enhanced " + l.name());
        if (l.area() < .1) require(extra <= 1, "Tiny ponds must not gain two blocks");
        require(l.waterY() - l.bed(.5) == 1, "Lake edge remains one block deep");
      }
    }
    Files.writeString(root.resolve(label + "-lakes.csv"), lakes);
    StringBuilder hist = new StringBuilder("lake,depth,count\n");
    for (String name : List.of("Suwa", "Shinji", "Nojiri", "Tazawa")) {
      Lake l = data.lakes().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
      Bounds b = l.polygon().bounds();
      TreeMap<Integer, Integer> depths = new TreeMap<>();
      int count = 0;
      long sum = 0;
      for (int iz = 0; iz < 101; iz++)
        for (int ix = 0; ix < 101; ix++) {
          int x = (int) Math.floor(b.minX() + (ix + .5) * (b.maxX() - b.minX()) / 101);
          int z = (int) Math.floor(b.minZ() + (iz + .5) * (b.maxZ() - b.minZ()) / 101);
          Geometry g = engine.geometry(x, z);
          if (g.kind() != Hydrology.Kind.LAKE || !g.owner().equals(l.id())) continue;
          int depth = g.waterY() - g.groundY();
          depths.merge(depth, 1, Integer::sum);
          sum += depth;
          count++;
          if (check)
            require(
                depth >= 1 && depth <= l.basinDepth(),
                "Lake depth must respect its explicit generation limit");
        }
      for (var e : depths.entrySet())
        hist.append(name)
            .append(',')
            .append(e.getKey())
            .append(',')
            .append(e.getValue())
            .append('\n');
      report.append(
          String.format(
              Locale.ROOT,
              "lake=%s reference=%d generated=%d sampledRange=%d..%d mean=%.3f samples=%d%n",
              name,
              l.maxDepth(),
              generatedDepth(l),
              depths.firstKey(),
              depths.lastKey(),
              sum / (double) count,
              count));
      if (check) {
        require(depths.firstKey() == 1, "Keep shallow lake shelf");
        require(depths.size() >= 3, "No uniform flat lake bed");
        if (name.equals("Suwa")) {
          require(
              l.maxDepth() == 3 && l.basinDepth() == 5,
              "Suwa reference is preserved separately from requested visual depth");
          require(
              depths.lastKey() == 5 && sum / (double) count > 2.3,
              "Suwa interior must visibly deepen");
          require(l.waterY() == 285, "Do not move lake surface to obtain depth");
        }
      }
      engine.releaseThreadCache();
    }
    Files.writeString(root.resolve(label + "-lake-depths.csv"), hist);
    if (check) {
      Polygon p =
          new Polygon(
              new Polygon.Ring(new int[] {0, 100, 100, 0}, new int[] {0, 0, 100, 100}), List.of());
      int[] expected = {3, 4, 5, 6, 6, 7, 8, 8};
      for (int d = 1; d <= 8; d++) {
        Lake l = new Lake("test", "test", false, 100, d, 1, 20, 50, 50, p);
        require(
            l.maxDepth() == d && l.basinDepth() == expected[d - 1],
            "Independent lake visual allowance case " + d);
      }
    }
    report
        .append("enhancedShallowLakes=")
        .append(enhanced)
        .append("\nassertions=")
        .append(checks)
        .append(
            "\nLimit: numerical generation checks, not client gameplay or fluid-tick execution.\n");
    Files.writeString(root.resolve(label + "-validation.txt"), report);
    System.out.print(report);
  }
}
