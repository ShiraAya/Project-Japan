package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Executable checks of hydraulic constraints and boundary contracts against independently stated
 * expectations.
 */
public final class CoreValidation {
  private static long checks;

  private static void require(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    long start = System.nanoTime();
    solverCases();
    polygonCases();
    int scaleChecks = ScaleValidation.verify();
    WorldEngine engine = WorldEngine.load();
    ShoreValidation.verify(engine);
    MorphologyValidation.verify(engine);
    ShoreFitValidation.verify(engine);
    WaterVolumeValidation.verify(engine);
    LakeConnectionValidation.verify(engine);
    RegionalRiverValidation.verify(engine);
    CoastJunctionValidation.verify(engine);
    EstuaryReservoirValidation.verify(engine);
    CoastalExpansionValidation.verify(engine);
    TokyoBaseCoastValidation.verify(engine);
    LakeNetworkValidation.verify(engine);
    WorldData data = engine.hydrology().data();
    for (River river : data.rivers())
      if (river.sourceLake().isEmpty() && river.sourceRiver().isEmpty())
        require(
            Math.abs(river.points().get(0).width() - Geo.horizontal(4)) < 1e-9,
            "Ordinary headwaters start as narrow streams; established lake outlets are separate");
    for (var rawLake : RawGeography.load().lakes()) {
      if (!Double.isFinite(rawLake.elevationMetres())) continue;
      Lake solved =
          data.lakes().stream().filter(l -> l.id().equals(rawLake.id())).findFirst().orElseThrow();
      require(
          solved.waterY()
              == Math.round(
                  LakeReferences.elevation(rawLake.id(), rawLake.elevationMetres()) * Geo.SCALE),
          "Physical lake level conversion " + solved.name());
    }
    int invalidDepths = 0;
    for (var raw : RawGeography.load().lakes()) {
      if (Double.isFinite(raw.maxDepthMetres()) && raw.maxDepthMetres() <= 0) invalidDepths++;
      if (Double.isFinite(raw.maxDepthMetres()) && raw.maxDepthMetres() > 0)
        require(
            LakeReferences.depth(raw.id(), raw.maxDepthMetres()) == raw.maxDepthMetres(),
            "Valid physical depths must not be replaced by basin estimates");
    }
    require(LakeReferences.depth("W09:gc01_364", 0) == 38.3, "Nojiri positive fallback");
    require(
        Double.isNaN(LakeReferences.depth("no-reference", 0)),
        "Zero is missing, not a one-block lake");
    require(Double.isNaN(LakeReferences.depth("no-reference", -5)), "Negative depth is missing");
    var midoriRaw =
        RawGeography.load().lakes().stream()
            .filter(l -> l.id().equals("W09:gc01_528"))
            .findFirst()
            .orElseThrow();
    require(midoriRaw.elevationMetres() == 5, "Original W09 input stays auditable");
    require(
        LakeReferences.elevation(midoriRaw.id(), 5) == 177,
        "Verified Midori normal pool is 177 metres");
    require(
        LakeReferences.source(midoriRaw.id()).contains("qsr.mlit.go.jp"),
        "Correction has operator provenance");
    require(data.rivers().size() == RawGeography.load().rivers().size() && data.rivers().size()>170, "Complete expanded lake network");
    require(data.lakes().size() == 221, "Lake selection count");
    Map<String, River> names = new HashMap<>();
    for (River r : data.rivers()) names.put(r.name(), r);
    long profiles = 0;
    int gates = 0;
    List<int[]> samples = new ArrayList<>();
    for (River river : data.rivers()) {
      int previous = river.water(0);
      for (int s = 1; s < river.profileSize(); s++) {
        int current = river.water(s);
        require(current <= previous, "Reverse flow " + river.name() + " @ " + s);
        require(previous - current <= 1, "Vertical jump " + river.name() + " @ " + s);
        previous = current;
        profiles++;
      }
      if (!river.terminalRiver().isEmpty()) {
        River parent = names.get(river.terminalRiver());
        River.Point end = river.at(river.totalLength());
        River.Hit best = null;
        for (var segment : parent.segments()) {
          var h = segment.nearest(end.x(), end.z());
          if (best == null || h.distance() < best.distance()) best = h;
        }
        require(best.distance() < 0.02, "Disconnected tributary " + river.name());
        require(
            river.water(river.totalLength()) == parent.water(best.station()),
            "Confluence water mismatch " + river.name());
      }
      if (!river.sourceRiver().isEmpty()) {
        River parent = names.get(river.sourceRiver());
        River.Point startPoint = river.at(0);
        River.Hit best = null;
        for (var segment : parent.segments()) {
          var h = segment.nearest(startPoint.x(), startPoint.z());
          if (best == null || h.distance() < best.distance()) best = h;
        }
        require(best.distance() < 0.02, "Disconnected bifurcation " + river.name());
        require(
            river.water(0) == parent.water(best.station()),
            "Bifurcation water mismatch " + river.name());
      }
      for (River.Gate gate : river.gates()) {
        gates++;
        for (double s :
            new double[] {gate.first(), (gate.first() + gate.last()) / 2, gate.last()}) {
          require(river.water(s) == gate.waterY(), "Lake gate level " + river.name() + " " + gate);
          River.Point p = river.at(s);
          samples.add(new int[] {(int) Math.floor(p.x()), (int) Math.floor(p.z())});
        }
      }
      for (int i = 0; i <= 12; i++) {
        var p = river.at(river.totalLength() * i / 12);
        samples.add(new int[] {(int) Math.floor(p.x()), (int) Math.floor(p.z())});
      }
      for (double s = 0; s <= river.totalLength(); s += 32) {
        var p = river.at(s);
        require(
            engine.geometry((int) Math.floor(p.x()), (int) Math.floor(p.z())).wet()
                || CoastalWaterValidation.mappedWaterNear(engine.hydrology().terrain(),river,p),
            "Dry river centre " + river.name() + " @ " + s);
      }
    }
    for (Lake lake : data.lakes()) {
      Geometry centre = engine.geometry(lake.representativeX(), lake.representativeZ());
      require(centre.kind() == Hydrology.Kind.LAKE, "Lake interior is dry/ocean: " + lake.name());
      require(centre.waterY() == lake.waterY(), "Lake interior level: " + lake.name());
      samples.add(new int[] {lake.representativeX(), lake.representativeZ()});
      for (var ring : allRings(lake))
        for (int i = 0; i < ring.size(); i += Math.max(1, ring.size() / 80)) {
          for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
              int x = ring.x(i) + dx, z = ring.z(i) + dz;
              Geometry g = engine.geometry(x, z);
              if (g.wet()) continue;
              for (int[] offset :
                  List.of(
                      new int[] {1, 0}, new int[] {-1, 0}, new int[] {0, 1}, new int[] {0, -1})) {
                Geometry neighbour = engine.geometry(x + offset[0], z + offset[1]);
                require(
                    !neighbour.wet() || g.groundY() >= neighbour.waterTop(),
                    "Uncontained shore " + lake.name() + " @ " + x + "," + z);
              }
            }
        }
    }
    for (int[] xy : samples) {
      Column c = engine.column(xy[0], xy[1]);
      Geometry g = c.geometry();
      require(c.at(Geo.MIN_Y) == SurfacePainter.Material.BEDROCK, "Bedrock contract");
      require(c.at(c.surfaceHeight()) == SurfacePainter.Material.AIR, "Surface height contract");
      require(c.at(g.groundY()) != SurfacePainter.Material.AIR, "Solid surface contract");
      if (g.wet())
        require(c.at(g.waterY()) == SurfacePainter.Material.WATER, "Water source contract");
    }
    // Same world, different order and worker threads. No shared mutable hydraulic solve state.
    List<Geometry> expected = new ArrayList<>();
    for (int[] p : samples) expected.add(engine.geometry(p[0], p[1]));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int worker = 0; worker < 4; worker++) {
        final int offset = worker;
        jobs.add(
            () -> {
              try {
                for (int i = offset; i < samples.size(); i += 4) {
                  int[] p = samples.get(i);
                  if (!engine.geometry(p[0], p[1]).equals(expected.get(i))) return false;
                }
                return true;
              } finally {
                engine.releaseThreadCache();
              }
            });
      }
      for (Future<Boolean> result : pool.invokeAll(jobs))
        require(result.get(), "Thread/order determinism");
    } finally {
      pool.shutdown();
    }
    String report =
        String.format(
            Locale.ROOT,
            "PJR independent core validation PASS\n"
                + "scale=0.5:0.5:0.5\n"
                + "scaleChecks=%d\n"
                + "inputNonPositiveDepths=%d\n"
                + "rivers=%d\n"
                + "lakes=%d\n"
                + "longitudinalSteps=%d\n"
                + "lakeIntervals=%d\n"
                + "assertions=%d\n"
                + "elapsedSeconds=%.3f\n"
                + "Checks: constrained-solver oracle cases, all longitudinal steps, confluence"
                + " endpoints, every lake interval, sampled lake shores/islands, block-column"
                + " contracts, concurrent deterministic queries.\n"
                + "Limit: shore coverage is sampled; no Minecraft fluid ticks or client rendering"
                + " in this task.\n",
            scaleChecks,
            invalidDepths,
            data.rivers().size(),
            data.lakes().size(),
            profiles,
            gates,
            checks,
            (System.nanoTime() - start) / 1e9);
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/core-validation.txt"), report);
    Files.write(Path.of("build/reports/network-audit.txt"), data.diagnostics());
    System.out.print(report);
  }

  private static List<Polygon.Ring> allRings(Lake lake) {
    List<Polygon.Ring> result = new ArrayList<>();
    result.add(lake.polygon().outer());
    result.addAll(lake.polygon().holes());
    return result;
  }

  private static void solverCases() {
    short[] fractionalProfile =
        HydraulicSolver.solve(
            new double[] {12, 8},
            10.5,
            10.5,
            List.of(
                new HydraulicSolver.Anchor(0, 12, "source"),
                new HydraulicSolver.Anchor(10.5, 8, "junction")));
    River fractional =
        new River(
            "fractional",
            "",
            "",
            "",
            "",
            10.5,
            10.5,
            false,
            List.of(new River.Point(0, 0, 0, 3), new River.Point(10.5, 0, 10.5, 4)),
            fractionalProfile,
            List.of());
    require(
        fractional.water(10) == 9 && fractional.water(10.5) == 8 && fractional.water(11) == 8,
        "Fractional endpoint must preserve exact confluence anchor");
    short[] p =
        HydraulicSolver.solve(
            new double[] {20, 18, 19, 10},
            10,
            30,
            List.of(
                new HydraulicSolver.Anchor(0, 20, "a"), new HydraulicSolver.Anchor(30, 10, "b")));
    require(p[0] == 20 && p[30] == 10, "Hard endpoint anchors");
    require(p[10] == 19 && p[20] == 19, "PAVA pooling oracle");
    p =
        HydraulicSolver.solve(
            new double[] {30, 4, 40, 9, 0},
            10,
            40,
            List.of(
                new HydraulicSolver.Anchor(0, 30, "a"),
                new HydraulicSolver.Anchor(20, 20, "lake"),
                new HydraulicSolver.Anchor(40, 0, "b")));
    require(p[20] == 20, "Internal anchor");
    for (int i = 1; i < p.length; i++)
      require(p[i] <= p[i - 1] && p[i - 1] - p[i] <= 1, "Bounded monotone grade");
    double[] gradual = new double[65];
    Arrays.fill(gradual, 100);
    double[] bounds = new double[64];
    Arrays.fill(bounds, 0.125);
    short[] eased =
        HydraulicSolver.solveTerrain(
            gradual,
            1,
            64,
            List.of(
                new HydraulicSolver.Anchor(0, 18, "upstream"),
                new HydraulicSolver.Anchor(64, 10, "lake")),
            bounds);
    for (int i = 0; i < eased.length; i++)
      require(eased[i] == 18 - i / 8, "1-in-8 grade with exact endpoint anchors");
    boolean infeasible = false;
    try {
      HydraulicSolver.solveTerrain(
          gradual,
          1,
          64,
          List.of(
              new HydraulicSolver.Anchor(0, 19, "upstream"),
              new HydraulicSolver.Anchor(64, 10, "lake")),
          bounds);
    } catch (IllegalArgumentException expected) {
      infeasible = true;
    }
    require(infeasible, "Local grade capacity must reject incompatible anchors");
    short[] plateau =
        HydraulicSolver.solveTerrain(
            new double[] {20, 18, 9, 8, 0},
            16,
            64,
            List.of(
                new HydraulicSolver.Anchor(0, 20, "upstream"),
                new HydraulicSolver.Anchor(32, 16, "lake entry"),
                new HydraulicSolver.Anchor(48, 16, "lake exit"),
                new HydraulicSolver.Anchor(64, 0, "downstream")),
            new double[] {2, 2, 0, 16});
    require(
        plateau[16] == 18 && plateau[32] == 16 && plateau[48] == 16 && plateau[64] == 0,
        "Variable limits preserve lake plateaus and reservoir relief");
    boolean rejected = false;
    try {
      HydraulicSolver.solve(
          new double[] {2, 5},
          10,
          10,
          List.of(new HydraulicSolver.Anchor(0, 2, "a"), new HydraulicSolver.Anchor(10, 5, "b")));
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    require(rejected, "Infeasible reverse anchors must fail");
    rejected = false;
    try {
      HydraulicSolver.solve(
          new double[] {30, 0},
          10,
          10,
          List.of(new HydraulicSolver.Anchor(0, 30, "a"), new HydraulicSolver.Anchor(10, 0, "b")));
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    require(rejected, "Infeasible drop must fail");
  }

  private static void polygonCases() {
    Polygon p =
        new Polygon(
            new Polygon.Ring(new int[] {-10, 10, 10, -10}, new int[] {-10, -10, 10, 10}),
            List.of(new Polygon.Ring(new int[] {-2, 2, 2, -2}, new int[] {-2, -2, 2, 2})));
    require(p.contains(6, 0), "Outer polygon");
    require(!p.contains(0, 0), "Island hole");
    require(!p.contains(11, 0), "Outside polygon");
    require(p.distance(0, 0) == 2 && p.distance(6, 0) == 4, "Nearest shore includes island");
    require(p.wetBlock(5, 0, p.distance(5.5, 0.5)), "Block coverage");
    require(!p.wetBlock(0, 0, p.distance(0.5, 0.5)), "Island block");
    require(
        Math.abs(Geo.x(Geo.ORIGIN_LON)) < 1e-9 && Math.abs(Geo.z(Geo.ORIGIN_LAT)) < 1e-9,
        "Tokyo projection origin");
  }
}
