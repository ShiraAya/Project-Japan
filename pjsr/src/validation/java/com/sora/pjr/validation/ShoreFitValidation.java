package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exterior lake-wall and downstream-width regressions; baseline export uses the old engine. */
public final class ShoreFitValidation {
  private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static long checks;

  private static void require(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    run(WorldEngine.load(), args.length == 0 ? "shore-fit" : args[0], args.length == 0);
  }

  public static void verify(WorldEngine engine) throws Exception {
    run(engine, "shore-fit", true);
  }

  private static void run(WorldEngine engine, String label, boolean check) throws Exception {
    checks = 0;
    WorldData data = engine.hydrology().data();
    Lake lake = data.lakes().stream().filter(l -> l.name().equals("畑薙湖")).findFirst().orElseThrow();
    Path reports = Path.of("build/reports");
    Files.createDirectories(reports);
    StringBuilder report =
        new StringBuilder(
            "PJR shore fit " + (check ? "validation PASS" : "baseline export") + "\n");
    report.append("Hatanagi level=").append(lake.waterY()).append("\n");
    int maxRaise = 0, maxJump = 0, walls = 0, pairs = 0;
    TreeMap<Integer, Integer> gaps = new TreeMap<>();
    Path tile = reports.resolve(label + "-tiles.bin"),
        staged = reports.resolve(label + "-tiles.bin.part");
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)))) {
      out.writeInt(1);
      out.writeInt(321);
      out.writeInt(-54008);
      out.writeInt(14704);
      for (int z = 14384; z <= 15024; z += 2)
        for (int x = -54328; x <= -53688; x += 2) {
          Column c = engine.column(x, z);
          Geometry g = c.geometry();
          out.writeShort(g.groundY());
          out.writeShort(g.wet() ? g.waterY() : Short.MIN_VALUE);
          out.writeShort(g.naturalY());
          out.writeByte(g.kind().ordinal());
          out.writeByte(c.layers().top().ordinal());
          if (g.wet()) continue;
          int rise = g.groundY() - g.naturalY();
          maxRaise = Math.max(maxRaise, rise);
          if (rise > 1) walls++;
          for (int[] offset : OFFSETS) {
            Geometry a = engine.geometry(x + offset[0], z + offset[1]);
            if (a.wet()) {
              pairs++;
              gaps.merge(g.groundY() - a.waterTop(), 1, Integer::sum);
            } else
              maxJump =
                  Math.max(
                      maxJump,
                      Math.abs(g.groundY() - a.groundY()) - Math.abs(g.naturalY() - a.naturalY()));
          }
        }
    }
    Files.move(staged, tile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    report
        .append("windowColumns=103041\nmaxDryRaise=")
        .append(maxRaise)
        .append("\nraisedWallColumns=")
        .append(walls)
        .append("\nmaxExtraDryJump=")
        .append(maxJump)
        .append("\nshorePairs=")
        .append(pairs)
        .append("\nshoreGaps=")
        .append(gaps)
        .append('\n');
    StringBuilder section = new StringBuilder("x,z,natural,ground,water,kind\n");
    for (int x = -54100; x <= -53900; x++) {
      Geometry g = engine.geometry(x, 14772);
      section
          .append(x)
          .append(",14772,")
          .append(g.naturalY())
          .append(',')
          .append(g.groundY())
          .append(',')
          .append(g.wet() ? g.waterY() : "")
          .append(',')
          .append(g.kind())
          .append('\n');
    }
    Files.writeString(reports.resolve(label + "-section.csv"), section);
    StringBuilder widths = new StringBuilder("river,progress,station,width\n");
    for (River r : data.rivers())
      for (int i = 0; i <= 100; i++) {
        River.Point p = r.at(r.mouthDistance() * i / 100);
        widths
            .append(r.name())
            .append(',')
            .append(i / 100.0)
            .append(',')
            .append(p.station())
            .append(',')
            .append(p.width())
            .append('\n');
      }
    Files.writeString(reports.resolve(label + "-widths.csv"), widths);
    if (check) {
      require(maxRaise == 0, "Hatanagi must not stand on a raised retaining wall");
      require(maxJump <= 2, "Hatanagi exterior blend is discontinuous");
      require(
          pairs > 400 && gaps.firstKey() >= 0 && gaps.lastKey() <= 1,
          "Hatanagi bank-to-water transition");
      widthChecks(data, report);
      syntheticRims();
      Terrain terrain = new RasterTerrain();
      int lakes = 0;
      int[] total = {0};
      Map<String, RawGeography.Waterbody> raw = new HashMap<>();
      for (var s : RawGeography.load().lakes()) raw.put(s.id(), s);
      for (Lake l : data.lakes()) {
        if (Double.isFinite(LakeReferences.elevation(l.id(), raw.get(l.id()).elevationMetres())))
          continue;
        lakes++;
        LakeLevels.dryShore(
            l.polygon(),
            cell -> {
              int x = (int) (cell >> 32), z = (int) (long) cell;
              Terrain.Sample t = terrain.sample(x, z);
              require(
                  l.waterY() <= (t.land() ? t.ground() : Geo.SEA),
                  "Estimated lake above terrain support " + l.name() + " " + x + "," + z);
              Geometry g = engine.geometry(x, z);
              // Attribute only genuinely dry shore cells; adjoining river water is checked
              // separately.
              if (!g.wet())
                require(
                    g.groundY() <= g.naturalY(),
                    "Estimated lake requires an exterior wall " + l.name() + " " + x + "," + z);
              total[0]++;
            });
        engine.releaseThreadCache();
      }
      report
          .append("estimatedLakesChecked=")
          .append(lakes)
          .append("\nestimatedDryRimCells=")
          .append(total[0])
          .append('\n');
      require(lakes == 47, "All currently estimated lake levels must be covered");
      report.append("assertions=").append(checks).append('\n');
    }
    report.append(
        "Limit: generated geometry and width profiles; not client rendering or fluid-tick"
            + " execution.\n");
    Files.writeString(reports.resolve(label + "-validation.txt"), report);
    System.out.print(report);
  }

  private static void widthChecks(WorldData data, StringBuilder report) {
    int contractions = 0;
    double fastest = 0;
    for (River r : data.rivers())
      for (int i = 1; i < r.points().size(); i++) {
        River.Point a = r.points().get(i - 1), b = r.points().get(i);
        double diff = b.width() - a.width();
        if (diff < -1e-9) contractions++;
        double rate = diff / (b.station() - a.station());
        fastest = Math.max(fastest, rate);
        require(diff >= -1e-9, "Downstream width pinch " + r.name());
        require(rate <= .00800001, "Rapid downstream flare " + r.name());
      }
    River oi = data.rivers().stream().filter(r -> r.name().equals("Oi")).findFirst().orElseThrow();
    require(oi.at(oi.mouthDistance() * .5).width() < 15, "Oi middle reach still expands too early");
    report
        .append("riverProfiles=").append(data.rivers().size())
        .append("\ndownstreamContractions=")
        .append(contractions)
        .append("\nmaxWidthIncreasePerBlock=")
        .append(fastest)
        .append('\n');
  }

  private static void syntheticRims() {
    Polygon.Ring outer = new Polygon.Ring(new int[] {0, 18, 15, -2}, new int[] {0, 3, 19, 14});
    Polygon.Ring hole = new Polygon.Ring(new int[] {5, 9, 9, 5}, new int[] {5, 5, 9, 9});
    Polygon p = new Polygon(outer, List.of(hole));
    Set<Long> actual = new HashSet<>(), expected = new HashSet<>();
    LakeLevels.dryShore(p, actual::add);
    for (int x = -5; x <= 22; x++)
      for (int z = -4; z <= 23; z++) {
        if (p.wetBlock(x, z, p.distance(x + .5, z + .5))) continue;
        for (int[] o : OFFSETS)
          if (p.wetBlock(x + o[0], z + o[1], p.distance(x + o[0] + .5, z + o[1] + .5))) {
            expected.add(Geo.key(x, z));
            break;
          }
      }
    require(
        actual.equals(expected),
        "Rasterized rim must match independent full grid scan including islands");
    Terrain flat = (x, z) -> new Terrain.Sample(true, 80 * Geo.METRES_Y, 1);
    require(
        LakeLevels.estimate(p, flat, 100).level() == 80,
        "High interior estimate cannot overtop exterior rim");
    require(
        LakeLevels.estimate(p, flat, 70).level() == 70,
        "Supported lower estimate must be preserved");
    Terrain ocean = (x, z) -> new Terrain.Sample(false, 0, 0);
    require(
        LakeLevels.estimate(p, ocean, 100).level() == 0,
        "Ocean support is sea level, not artificial seafloor");
  }
}
