package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;

/** Checks rendered water ownership at lake polygon boundaries, independent of centreline gates. */
public final class LakeConnectionValidation {
  private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void verify(WorldEngine engine) throws Exception {
    Hydrology hydrology = engine.hydrology();
    WorldData data = hydrology.data();
    SpatialIndex<River.Segment> index =
        new SpatialIndex<>(
            data.rivers().stream().flatMap(r -> r.segments().stream()).toList(),
            128,
            s -> s.bounds().expand(Math.max(s.a().width(), s.b().width()) / 2 + 4));
    int pairs = 0, mismatches = 0, maxDifference = 0, maxInletDrop = 0, inletChecks = 0;
    Map<String, Integer> groups = new TreeMap<>();
    StringBuilder mismatchDetails=new StringBuilder("lake_id,lake,river,x,z,lake_level,river_level,station\n");
    Set<String> seen = new HashSet<>();
    for (Lake lake : data.lakes()) {
      // Every polygon segment and island ring is walked at half-block spacing. A 3x3 halo
      // covers all four-neighbour crossings; the narrow river index only rejects dry coastline.
      Set<Long> cells = new HashSet<>();
      List<Polygon.Ring> rings = new ArrayList<>();
      rings.add(lake.polygon().outer());
      rings.addAll(lake.polygon().holes());
      for (Polygon.Ring ring : rings)
        for (int i = 0; i < ring.size(); i++) {
          int j = (i + 1) % ring.size();
          double dx = ring.x(j) - ring.x(i), dz = ring.z(j) - ring.z(i);
          int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) * 2));
          for (int step = 0; step <= steps; step++) {
            double x = ring.x(i) + dx * step / steps, z = ring.z(i) + dz * step / steps;
            if (!nearRiver(index, x, z, 3)) continue;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            for (int oz = -1; oz <= 1; oz++)
              for (int ox = -1; ox <= 1; ox++) cells.add(Geo.key(bx + ox, bz + oz));
          }
        }
      for (long cell : cells) {
        int x = (int) (cell >> 32), z = (int) cell;
        if (!wet(lake, x, z)) continue;
        for (int[] offset : OFFSETS) {
          int nx = x + offset[0], nz = z + offset[1];
          if (wet(lake, nx, nz) || !nearRiver(index, nx + 0.5, nz + 0.5, 0)) continue;
          Hydrology.Water riverWater = hydrology.sample(nx, nz).water();
          if (riverWater.kind() != Hydrology.Kind.RIVER) continue;
          Hydrology.Water lakeWater = hydrology.sample(x, z).water();
          if (lakeWater.kind() != Hydrology.Kind.LAKE) continue;
          String identity = x + "," + z + ":" + offset[0] + "," + offset[1];
          if (!seen.add(identity)) continue;
          pairs++;
          int difference =
              Math.max(
                  Math.abs(lakeWater.level() - riverWater.level()),
                  Math.abs(lakeWater.top() - riverWater.top()));
          if (difference != 0) {
            mismatches++;
            mismatchDetails.append(String.format(Locale.ROOT,"%s,%s,%s,%d,%d,%d,%d,%.3f%n",lake.id(),lake.name(),riverWater.owner(),nx,nz,lakeWater.level(),riverWater.level(),riverWater.river().station()));
          }
          maxDifference = Math.max(maxDifference, difference);
          String group = riverWater.owner() + " / " + lake.name();
          groups.merge(group, 1, Integer::sum);
          River.Hit hit = riverWater.river();
          River.Gate gate =
              hit.river().gates().stream()
                  .filter(g -> g.lakeId().equals(lake.id()))
                  .min(
                      Comparator.comparingDouble(
                          g ->
                              Math.min(
                                  Math.abs(g.first() - hit.station()),
                                  Math.abs(g.last() - hit.station()))))
                  .orElse(null);
          if (gate != null && hit.station() <= gate.first()) {
            int drop =
                hit.river().water(Math.max(0, hit.station() - 16))
                    - hit.river().water(hit.station());
            maxInletDrop = Math.max(maxInletDrop, drop);
            inletChecks++;
            require(drop <= 3, "Abrupt approach at " + group + " " + nx + "," + nz);
          }
          if (gate != null
              && hit.station() >= gate.last()
              && LakeReferences.gentleOutlet(lake.name())) {
            int drop = hit.river().water(hit.station()) - hit.river().water(hit.station() + 16);
            require(drop <= 3, "Abrupt ordinary outlet at " + group);
          }
        }
      }
    }
    require(pairs >= 1000 && groups.size() >= 57, "Full river/lake interface coverage (52 retained + at least 5 new)");
    require(groups.keySet().stream().anyMatch(g -> g.startsWith("Yoro /")), "Yoro lake interface checked");
    require(groups.keySet().stream().anyMatch(g -> g.startsWith("Kawauchi-Shimokita /")), "Kawauchi lake interface checked");
    for (String link : List.of("Hitachitone", "Wani", "Imagiriguchi"))
      require(groups.keySet().stream().anyMatch(g -> g.startsWith(link + " /")), "New connecting reach checked " + link);
    require(inletChecks > 0, "Inlet grade checks executed");
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/lake-interface-mismatches.csv"),mismatchDetails);
    require(
        mismatches == 0,
        "River/lake water-level mismatch: " + mismatches + ", max=" + maxDifference);
    River yoshino =
        data.rivers().stream().filter(r -> r.name().equals("Yoshino")).findFirst().orElseThrow();
    River.Gate sameura =
        yoshino.gates().stream().filter(g -> g.lakeName().equals("早明浦湖")).findFirst().orElseThrow();
    int relief = sameura.waterY() - yoshino.water(sameura.last() + 160);
    require(relief >= 20, "Preserve reservoir relief downstream of the verified Sameura dam");
    StringBuilder report = new StringBuilder("PJR river-lake connection validation PASS\n");
    report
        .append("polygonLakes=")
        .append(data.lakes().size())
        .append("\ncontactGroups=")
        .append(groups.size())
        .append("\nadjacentPairs=")
        .append(pairs)
        .append("\nwaterLevelOrTopMismatches=")
        .append(mismatches)
        .append("\nmaxBoundaryDifference=")
        .append(maxDifference)
        .append("\ninletApproachChecks=")
        .append(inletChecks)
        .append("\nmaxInletDropOver16Blocks=")
        .append(maxInletDrop)
        .append("\nSameuraRelief160BlocksDownstream=")
        .append(relief)
        .append('\n');
    groups.forEach(
        (name, count) -> report.append(name).append(": ").append(count).append(" pairs\n"));
    report
        .append(
            "Checks cover vector-derived adjacent source and top-water levels, not fluid ticks.\n")
        .append(
            "Reservoir relief is preserved, not a claim of an accurately modelled dam/spillway.\n");
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/lake-connection-validation.txt"), report);
    System.out.print(report);
  }

  private static boolean wet(Lake lake, int x, int z) {
    return lake.polygon().wetBlock(x, z, lake.polygon().distance(x + 0.5, z + 0.5));
  }

  private static boolean nearRiver(
      SpatialIndex<River.Segment> index, double x, double z, double extra) {
    for (River.Segment segment : index.at(x, z)) {
      River.Hit hit = segment.nearest(x, z);
      if (hit.distance() <= Hydrology.radius(hit) + extra) return true;
    }
    return false;
  }
}
