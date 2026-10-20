package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.util.*;

/** Physical-distance and original geographic landmark checks for the 0.5 release. */
final class ScaleValidation {
  private static int checks;

  private static void require(boolean ok, String reason) {
    checks++;
    if (!ok) throw new AssertionError("Scale regression: " + reason);
  }

  static int verify() {
    require(Geo.SCALE == 0.5, "requested scale");
    require(
        Math.abs(Geo.x(Geo.ORIGIN_LON + 1000.0 / Geo.DEG_LON) - 500) < 1e-7,
        "one kilometre east must be 500 blocks");
    require(
        Math.abs(Geo.z(Geo.ORIGIN_LAT + 1000.0 / Geo.DEG_LAT) + 500) < 1e-7,
        "one kilometre north must be -500 blocks");
    require(new Terrain.Sample(true, 1000, 1).ground() == 500, "1000 m elevation");
    require(
        new Terrain.Sample(true, 3776, 1).ground() == 1888,
        "3776 m conversion and build clearance");
    require(1888 + 32 < Geo.MAX_Y, "summit fits dimension");
    require(new Terrain.Sample(false, 0, 0).ground() == -64, "128 m modelled seabed");
    require(Math.abs(Geo.METRES_XZ - Geo.METRES_Y) < 1e-12, "isotropic physical scale");
    for (double[] coordinate :
        new double[][] {{135.7681, 35.0116}, {141.3545, 43.0618}, {130.4017, 33.5902}}) {
      require(
          Math.abs(Geo.lon(Geo.x(coordinate[0])) - coordinate[0]) < 1e-10, "longitude round trip");
      require(
          Math.abs(Geo.lat(Geo.z(coordinate[1])) - coordinate[1]) < 1e-10, "latitude round trip");
    }
    // Fixed geographic coordinates from original W05 source routes, independent of world scale.
    Map<String, double[]> origins =
        Map.of(
            "Ishikari", new double[] {142.97638329106599, 43.56593750438624},
            "Tama", new double[] {138.80495306, 35.79107778},
            "Shinano", new double[] {138.71744306, 35.9433775},
            "Shimanto", new double[] {133.083265, 33.45008278},
            "Kuma", new double[] {130.97146083, 32.44115972});
    RawGeography raw = RawGeography.load();
    for (var river : raw.rivers()) {
      if (!origins.containsKey(river.name())) continue;
      double[] reference = origins.get(river.name());
      var p = river.points().get(0);
      require(Math.abs(Geo.lon(p.x()) - reference[0]) < 1e-8, river.name() + " source longitude");
      require(Math.abs(Geo.lat(p.z()) - reference[1]) < 1e-8, river.name() + " source latitude");
    }
    var biwa = raw.lakes().stream().filter(l -> l.name().equals("Biwa")).findFirst().orElseThrow();
    Bounds b = biwa.polygon().bounds();
    // Former quantized geographic bounds, expanded by 4/3; <=2 blocks of rounding difference.
    require(
        Math.abs(b.minX() - -176588) <= 2 && Math.abs(b.maxX() - -157748) <= 2,
        "Biwa east-west extent");
    require(
        Math.abs(b.minZ() - 9168) <= 2 && Math.abs(b.maxZ() - 39016) <= 2,
        "Biwa north-south extent");
    return checks;
  }
}
