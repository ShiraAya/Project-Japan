package com.sora.pjr.core;

import static com.sora.pjr.core.RawGeography.XY;

import java.util.*;

/** Builds a new hydraulic network from raw geography, once per engine instance. */
public final class WorldData {
  private final List<Lake> lakes;
  private final List<River> rivers;
  private final List<String> diagnostics;

  private WorldData(List<Lake> lakes, List<River> rivers, List<String> diagnostics) {
    this.lakes = List.copyOf(lakes);
    this.rivers = List.copyOf(rivers);
    this.diagnostics = List.copyOf(diagnostics);
  }

  public List<Lake> lakes() {
    return lakes;
  }

  public List<River> rivers() {
    return rivers;
  }

  public List<String> diagnostics() {
    return diagnostics;
  }

  public static WorldData load(Terrain terrain) {
    return new Builder(terrain, RawGeography.load()).build();
  }

  private static final class Draft {
    final RawGeography.Watercourse raw;
    List<XY> route;
    String parent, sourceParent = "";
    boolean sea;

    Draft(RawGeography.Watercourse raw) {
      this.raw = raw;
      route = new ArrayList<>(raw.points());
      parent = raw.parent();
    }
  }

  private static final class Builder {
    final Terrain terrain;
    final RawGeography raw;
    final List<Lake> lakes = new ArrayList<>();
    final Map<String, Lake> lakeNames = new HashMap<>();
    final Map<String, Draft> drafts = new LinkedHashMap<>();
    final Map<String, River> solved = new LinkedHashMap<>();
    final Set<String> visiting = new HashSet<>();
    final List<String> diagnostics = new ArrayList<>();
    final PrecomputedHydrology precomputed;
    SpatialIndex<Lake> lakeIndex;

    Builder(Terrain terrain, RawGeography raw) {
      this.terrain = terrain;
      this.raw = raw;
      precomputed=terrain instanceof RasterTerrain && !Boolean.getBoolean("pjr.compileHydrology")
          ? PrecomputedHydrology.load() : null;
    }

    WorldData build() {
      for (var source : raw.lakes()) {
        Lake lake = lake(source);
        lakes.add(lake);
        lakeNames.put(lake.name(), lake);
        lakeNames.put(lake.id(), lake);
      }
      lakeIndex =
          new SpatialIndex<>(
              lakes, 1024, l -> l.polygon().bounds().expand(Geo.horizontal(448) / 2 + 4));
      for (var source : raw.rivers()) drafts.put(source.name(), new Draft(source));
      if (!drafts.keySet().containsAll(RiverShapes.names()))
        throw new IllegalStateException("Regional dimensions refer to absent rivers");
      // Endpoint corrections are geometric, with measured displacements in the audit.
      for (Draft d : drafts.values()) {
        if (!d.raw.sourceLake().isEmpty()) attachLake(d, lakeNames.get(d.raw.sourceLake()), true);
        if (!d.raw.terminalLake().isEmpty())
          attachLake(d, lakeNames.get(d.raw.terminalLake()), false);
      }
      for (Draft d : drafts.values()) {
        if (!d.parent.isEmpty()) {
          Draft parent = drafts.get(d.parent);
          XY end = d.route.get(d.route.size() - 1);
          RouteMath.Projection p = RouteMath.nearest(parent.route, end);
          diagnostics.add(
              String.format(
                  Locale.ROOT,
                  "junction %s -> %s; displacement=%.2f blocks",
                  d.raw.name(),
                  d.parent,
                  p.distance()));
          if (p.distance() > Geo.horizontal(2048))
            throw new IllegalStateException(
                "Official routes do not meet: "
                    + d.raw.name()
                    + " -> "
                    + d.parent
                    + " distance="
                    + p.distance());
          append(d.route, new XY(p.x(), p.z()));
        } else if (d.raw.terminalLake().isEmpty()) extendToCoast(d);
        d.route = RouteMath.densify(d.route);
      }
      for (Draft d : drafts.values()) {
        if (!d.raw.sourceLake().isEmpty()) continue;
        XY start = d.route.get(0);
        Draft best = null;
        RouteMath.Projection projection = null;
        for (Draft other : drafts.values()) {
          if (other == d
              || !other
                  .raw
                  .officialCode()
                  .substring(0, 6)
                  .equals(d.raw.officialCode().substring(0, 6))) continue;
          if (RouteMath.length(other.route) < RouteMath.length(d.route) * 1.2) continue;
          var p = RouteMath.nearest(other.route, start);
          // Preserve the previously reviewed Edo/Tone archive seam. New branches
          // must actually meet; sharing a basin within a kilometre is insufficient.
          double sourceTolerance=d.raw.name().equals("Edo") && other.raw.name().equals("Tone")
              ? Geo.horizontal(1024):Geo.horizontal(24);
          if (p.distance() <= sourceTolerance
              && (projection == null || p.distance() < projection.distance())) {
            best = other;
            projection = p;
          }
        }
        if (best != null) {
          d.sourceParent = best.raw.name();
          d.route.set(0, new XY(projection.x(), projection.z()));
          diagnostics.add(
              "source bifurcation "
                  + d.sourceParent
                  + " -> "
                  + d.raw.name()
                  + "; displacement="
                  + projection.distance()
                  + " blocks");
        }
      }
      for (String name : drafts.keySet()) solve(name);
      if(precomputed!=null){diagnostics.clear();diagnostics.addAll(precomputed.diagnostics);}
      return new WorldData(lakes, new ArrayList<>(solved.values()), diagnostics);
    }

    Lake lake(RawGeography.Waterbody s) {
      if(precomputed!=null) {
        var cached=precomputed.lakes.get(s.id());
        if(cached==null)throw new IllegalStateException("Missing precomputed lake "+s.id());
        return cached.restore(s);
      }
      Polygon p = s.polygon();
      Bounds b = p.bounds();
      double area = ringArea(p.outer());
      for (var h : p.holes()) area -= ringArea(h);
      area =
          Math.max(Geo.METRES_XZ * Geo.METRES_XZ / 1e6, area * Geo.METRES_XZ * Geo.METRES_XZ / 1e6);
      XY representative = null;
      double best = -1;
      List<Integer> heights = new ArrayList<>();
      for (int iz = 0; iz < 24; iz++)
        for (int ix = 0; ix < 24; ix++) {
          int x = (int) Math.round(b.minX() + (ix + 0.5) * (b.maxX() - b.minX()) / 24);
          int z = (int) Math.round(b.minZ() + (iz + 0.5) * (b.maxZ() - b.minZ()) / 24);
          if (!p.contains(x + 0.5, z + 0.5)) continue;
          double distance = p.distance(x + 0.5, z + 0.5);
          if (distance > best) {
            best = distance;
            representative = new XY(x, z);
          }
          heights.add(terrain.sample(x, z).ground());
        }
      if (representative == null)
        representative = RouteMath.interiorNear(p, new XY(b.minX(), b.minZ()));
      Collections.sort(heights);
      int level;
      double elevation = LakeReferences.elevation(s.id(), s.elevationMetres());
      if (Double.isFinite(elevation)) {
        level = (int) Math.round(elevation / Geo.METRES_Y);
        if (Double.compare(elevation, s.elevationMetres()) != 0)
          diagnostics.add(
              "verified lake level correction "
                  + s.id()
                  + " "
                  + s.name()
                  + ": "
                  + s.elevationMetres()
                  + " m -> "
                  + elevation
                  + " m; "
                  + LakeReferences.source(s.id()));
      } else {
        level =
            heights.isEmpty()
                ? terrain.sample((int) representative.x(), (int) representative.z()).ground()
                : heights.get(heights.size() / 4);
        level = Math.max(0, level);
        LakeLevels.Estimate estimate = LakeLevels.estimate(p, terrain, level);
        level = estimate.level();
        diagnostics.add(
            "estimated lake level "
                + s.id()
                + " "
                + s.name()
                + " = "
                + level
                + " blocks (DEM interior="
                + estimate.interiorLevel()
                + "; dry-rim support="
                + estimate.rimSupport()
                + "; shore cells="
                + estimate.shoreCells()
                + ")");
      }
      if (LakeReferences.terrainSupportedLevel(s.id())) {
        LakeLevels.Estimate estimate = LakeLevels.estimate(p, terrain, level);
        diagnostics.add("terrain-supported lake level " + s.id() + " " + s.name()
            + ": reference=" + level + " -> generated=" + estimate.level()
            + "; dry-rim=" + estimate.rimSupport() + "; cells=" + estimate.shoreCells());
        level = estimate.level();
      }
      double depthMetres = LakeReferences.depth(s.id(), s.maxDepthMetres());
      if (!Double.isFinite(depthMetres)) {
        depthMetres = LakeReferences.modelDepth(s.id(), estimateDepth(p, area, level));
        diagnostics.add(
            "estimated lake depth "
                + s.id()
                + " "
                + s.name()
                + " = "
                + depthMetres
                + " m; raw="
                + s.maxDepthMetres());
      } else if (Double.compare(depthMetres, s.maxDepthMetres()) != 0) {
        diagnostics.add(
            "fallback lake depth "
                + s.id()
                + " "
                + s.name()
                + " = "
                + depthMetres
                + " m from scope reference; raw="
                + s.maxDepthMetres());
      }
      int depth = (int) Math.round(Geo.vertical(depthMetres));
      Lake result =
          new Lake(
              s.id(),
              s.name(),
              s.authored(),
              level,
              Math.max(1, Math.min((int) Math.round(Geo.vertical(1600)), depth)),
              area,
              Geo.horizontal(768),
              (int) representative.x(),
              (int) representative.z(),
              p);
      if (result.basinDepth() != result.maxDepth())
        diagnostics.add(
            "visual lake depth "
                + s.id()
                + " "
                + s.name()
                + ": input/estimate="
                + result.maxDepth()
                + " blocks -> generation="
                + result.basinDepth()
                + " blocks; water level and polygon unchanged");
      return result;
    }

    double estimateDepth(Polygon p, double area, int waterLevel) {
      // Missing measurements stay estimates. Low, flat wetlands must not get mountain basins
      // solely because they have a large surface area. Use surrounding relief as a conservative
      // cap.
      List<Double> relief = new ArrayList<>();
      Polygon.Ring ring = p.outer();
      int stride = Math.max(1, ring.size() / 96);
      for (int i = 0; i < ring.size(); i += stride)
        for (int direction = 0; direction < 8; direction++) {
          double angle = direction * Math.PI / 4;
          int x = (int) Math.round(ring.x(i) + Geo.horizontal(64) * Math.cos(angle));
          int z = (int) Math.round(ring.z(i) + Geo.horizontal(64) * Math.sin(angle));
          if (p.contains(x + 0.5, z + 0.5)) continue;
          Terrain.Sample sample = terrain.sample(x, z);
          if (sample.land() && terrain.elevationSupported(x,z))
            relief.add(Math.max(0, (sample.ground() - waterLevel) * Geo.METRES_Y));
        }
      Collections.sort(relief);
      double surroundingRelief = relief.isEmpty() ? 0 : relief.get((relief.size() - 1) * 9 / 10);
      return Math.min(12 + Math.sqrt(area) * 8, 3 + 0.8 * surroundingRelief);
    }

    static double ringArea(Polygon.Ring r) {
      double sum = 0;
      for (int i = 0; i < r.size(); i++) {
        int j = (i + 1) % r.size();
        sum += (double) r.x(i) * r.z(j) - (double) r.x(j) * r.z(i);
      }
      return Math.abs(sum) / 2;
    }

    void attachLake(Draft d, Lake lake, boolean source) {
      if (lake == null)
        throw new IllegalStateException("Missing declared lake for " + d.raw.name());
      List<XY> route = RouteMath.densify(d.route);
      int nearest = -1;
      double distance = Double.POSITIVE_INFINITY;
      for (int i = 0; i < route.size(); i++) {
        XY p = route.get(i);
        double gap =
            lake.polygon().contains(p.x(), p.z()) ? 0 : lake.polygon().distance(p.x(), p.z());
        if (gap < distance) {
          distance = gap;
          nearest = i;
        }
      }
      if (distance > Geo.horizontal(2048))
        throw new IllegalStateException(
            "Lake endpoint gap: " + d.raw.name() + " / " + lake.name() + " " + distance);
      XY point = route.get(nearest);
      XY inside =
          lake.polygon().contains(point.x(), point.z())
              ? point
              : RouteMath.interiorNear(lake.polygon(), point);
      List<XY> result = new ArrayList<>();
      if (source) {
        result.add(inside);
        for (int i = nearest; i < route.size(); i++) append(result, route.get(i));
      } else {
        for (int i = 0; i <= nearest; i++) append(result, route.get(i));
        append(result, inside);
      }
      if (result.size() < 2)
        throw new IllegalStateException("Degenerate lake route " + d.raw.name());
      d.route = result;
      diagnostics.add(
          String.format(
              Locale.ROOT,
              "lake endpoint %s %s %s; displacement=%.2f blocks",
              d.raw.name(),
              source ? "from" : "to",
              lake.name(),
              distance));
    }

    void extendToCoast(Draft d) {
      List<XY> p = d.route;
      XY end = p.get(p.size() - 1);
      if (!terrain.sample((int) end.x(), (int) end.z()).land()) {
        d.sea = true;
        return;
      }
      XY before = p.get(Math.max(0, p.size() - 5));
      double angle = Math.atan2(end.z() - before.z(), end.x() - before.x());
      XY found = null;
      double score = Double.POSITIVE_INFINITY;
      for (int step = -8; step <= 8; step++) {
        double a = angle + step * Math.PI / 16;
        for (double distance = Geo.horizontal(64);
            distance <= Geo.horizontal(3072);
            distance += Geo.horizontal(64)) {
          XY q = new XY(end.x() + Math.cos(a) * distance, end.z() + Math.sin(a) * distance);
          if (!terrain.sample((int) q.x(), (int) q.z()).land()) {
            double cost = distance * (1 + Math.abs(step) * 0.08);
            if (cost < score) {
              score = cost;
              found = q;
            }
            break;
          }
        }
      }
      if (found != null) {
        diagnostics.add(
            String.format(
                Locale.ROOT,
                "coastal extension %s; length=%.1f blocks",
                d.raw.name(),
                RouteMath.distance(end, found)));
        append(d.route, found);
        d.sea = true;
      } else
        diagnostics.add(
            "INLAND TERMINAL "
                + d.raw.name()
                + "; official endpoint retained, no invented sea connection");
    }

    River solve(String name) {
      if (solved.containsKey(name)) return solved.get(name);
      if (!visiting.add(name)) throw new IllegalStateException("Hydraulic cycle: " + name);
      Draft d = drafts.get(name);
      River parent = d.parent.isEmpty() ? null : solve(d.parent);
      River sourceParent = d.sourceParent.isEmpty() ? null : solve(d.sourceParent);
      double length = RouteMath.length(d.route), km = length * Geo.METRES_XZ / 1000;
      double mouthWidth = Geo.horizontal(Math.min(320, 24 + Math.sqrt(km) * 12));
      RiverShapes.Shape shape = RiverShapes.get(name);
      if (shape != null) mouthWidth = Geo.horizontal(shape.mouthWidthMetres());
      if (parent != null) {
        XY end = d.route.get(d.route.size() - 1);
        var hit = RouteMath.nearest(drafts.get(d.parent).route, end);
        mouthWidth = Math.min(mouthWidth, parent.at(hit.station()).width() * 0.85);
      }
      double sourceWidth = Geo.horizontal(d.raw.sourceLake().isEmpty() ? 4 : 24);
      if (shape != null) sourceWidth = Math.min(mouthWidth, Geo.horizontal(shape.sourceWidthMetres()));
      if (sourceParent != null) {
        double s = RouteMath.nearest(drafts.get(d.sourceParent).route, d.route.get(0)).station();
        sourceWidth = Math.min(mouthWidth, sourceParent.at(s).width() * 0.6);
      }
      List<River.Point> points = RiverWidths.build(d.route, length, sourceWidth, mouthWidth);
      if(precomputed!=null) {
        var cached=precomputed.rivers.get(name);
        if(cached==null)throw new IllegalStateException("Missing precomputed river "+name);
        River river=cached.restore(name,d.raw.sourceLake(),d.raw.terminalLake(),d.sourceParent,d.parent,d.sea,length,points);
        solved.put(name,river);visiting.remove(name);return river;
      }
      int n = (int) Math.ceil(length) + 1;
      double spacing = length / (n - 1);
      double[] target = new double[n];
      List<River.Gate> gates = new ArrayList<>();
      Map<Lake, Integer> opened = new LinkedHashMap<>();
      List<River.Gate> contacts = new ArrayList<>();
      Map<Lake, Integer> touching = new LinkedHashMap<>();
      int segment = 0;
      for (int i = 0; i < n; i++) {
        double s = i * spacing;
        while (segment + 1 < points.size() - 1 && points.get(segment + 1).station() < s) segment++;
        River.Point a = points.get(segment), b = points.get(segment + 1);
        double t = (s - a.station()) / (b.station() - a.station());
        double x = a.x() + (b.x() - a.x()) * t, z = a.z() + (b.z() - a.z()) * t;
        // Include both banks, rather than fitting a wide river to its centre pixel alone.
        // The ring also covers abrupt bends and the coarse raster's lateral height gradient.
        double radius = (a.width() + (b.width() - a.width()) * t) / 2 + Geo.horizontal(16);
        int support = terrain.sample((int) Math.floor(x), (int) Math.floor(z)).ground();
        for (int direction = 0; direction < 16; direction++) {
          double angle = direction * Math.PI / 8;
          support =
              Math.min(
                  support,
                  terrain
                      .sample(
                          (int) Math.floor(x + radius * Math.cos(angle)),
                          (int) Math.floor(z + radius * Math.sin(angle)))
                      .ground());
        }
        // Three blocks leave room for quantization and the short falling-water overlap.
        target[i] = Math.max(0, support - Geo.vertical(8));
        Set<Lake> inside = new HashSet<>();
        Set<Lake> contact = new HashSet<>();
        for (Lake lake : lakeIndex.at(x, z)) {
          double wetReach=radius-Geo.horizontal(16)+.75;
          boolean verifiedContact=LakeReferences.verifiedShoreLink(lake.id(),d.raw.officialCode())
              && lake.polygon().distance(x,z)<=Math.min(wetReach,Geo.horizontal(24));
          if (lake.polygon().contains(x, z) || verifiedContact) {
            inside.add(lake);
            opened.putIfAbsent(lake, i);
          }
          // The river's whole rasterized width can meet a lake before its centre line enters.
          double reach = radius - Geo.horizontal(16) + 0.75 + 2;
          if (inside.contains(lake)
              || (lake.polygon().bounds().expand(reach).contains(x, z)
                  && lake.polygon().distance(x, z) <= reach)) {
            contact.add(lake);
            touching.putIfAbsent(lake, i);
          }
        }
        Iterator<Map.Entry<Lake, Integer>> iterator = opened.entrySet().iterator();
        while (iterator.hasNext()) {
          var entry = iterator.next();
          if (inside.contains(entry.getKey()) && i < n - 1) continue;
          Lake l = entry.getKey();
          gates.add(
              new River.Gate(
                  l.id(),
                  l.name(),
                  entry.getValue() * spacing,
                  (inside.contains(l) ? i : i - 1) * spacing,
                  l.waterY()));
          iterator.remove();
        }
        Iterator<Map.Entry<Lake, Integer>> contactIterator = touching.entrySet().iterator();
        while (contactIterator.hasNext()) {
          var entry = contactIterator.next();
          if (contact.contains(entry.getKey()) && i < n - 1) continue;
          Lake l = entry.getKey();
          contacts.add(
              new River.Gate(
                  l.id(),
                  l.name(),
                  entry.getValue() * spacing,
                  (contact.contains(l) ? i : i - 1) * spacing,
                  l.waterY()));
          contactIterator.remove();
        }
      }
      List<HydraulicSolver.Anchor> anchors = new ArrayList<>();
      int downstream;
      if (parent != null) {
        XY end = d.route.get(d.route.size() - 1);
        double s = RouteMath.nearest(drafts.get(d.parent).route, end).station();
        downstream = parent.water(s);
      } else if (!d.raw.terminalLake().isEmpty())
        downstream = lakeNames.get(d.raw.terminalLake()).waterY();
      else downstream = d.sea ? 0 : (int) Math.round(target[n - 1]);
      int source =
          d.raw.sourceLake().isEmpty()
              ? Math.max(downstream, (int) Math.round(target[0]))
              : lakeNames.get(d.raw.sourceLake()).waterY();
      if (sourceParent != null) {
        XY start = d.route.get(0);
        source =
            sourceParent.water(
                RouteMath.nearest(drafts.get(d.sourceParent).route, start).station());
      }
      // DEM targets are soft terrain preferences; lake/junction levels are hard physical anchors.
      // A lake backwater may reach an unconnected headwater whose 8 m bed allowance lies lower.
      if(d.raw.sourceLake().isEmpty() && sourceParent==null)
        for(River.Gate gate:gates)source=Math.max(source,gate.waterY());
      if(d.raw.terminalLake().isEmpty() && parent==null && !d.sea)
        for(River.Gate gate:gates)downstream=Math.min(downstream,gate.waterY());
      anchors.add(new HydraulicSolver.Anchor(0, source, "source"));
      anchors.add(new HydraulicSolver.Anchor(length, downstream, "terminal"));
      Set<String> connectedLakes = new HashSet<>();
      for (River.Gate gate : gates) connectedLakes.add(gate.lakeId());
      double[] maxDrop = new double[n - 1];
      Arrays.fill(maxDrop, spacing);
      if(parent!=null) {
        XY endpoint=d.route.get(d.route.size()-1);
        double parentStation=RouteMath.nearest(drafts.get(d.parent).route,endpoint).station();
        // Shared water starts where the two channel widths overlap, before the
        // mathematical centreline endpoint. Settle the tributary there so the
        // parent's shallow edge cannot create a short waterfall or blocking sill.
        double apron=Math.min(length*.25,Math.max(8,(mouthWidth+parent.at(parentStation).width())*2));
        double join=Math.max(0,length-apron);
        for(River.Gate gate:gates)join=Math.max(join,gate.last()+3);
        for(River.Gate contact:contacts)
          if(connectedLakes.contains(contact.lakeId()))
            join=Math.max(join,contact.last()+3+8*Math.max(0,contact.waterY()-downstream)+2*spacing);
        if(join<length-1) {
          anchors.add(new HydraulicSolver.Anchor(join,downstream,"shared confluence"));
          easeGrade(maxDrop,spacing,join,true);
        }
      }
      for (River.Gate gate : contacts) {
        if (!connectedLakes.contains(gate.lakeId())) continue;
        double first = Math.max(0, gate.first() - 3), last = Math.min(length, gate.last() + 3);
        anchors.add(new HydraulicSolver.Anchor(first, gate.waterY(), "enter " + gate.lakeName()));
        anchors.add(new HydraulicSolver.Anchor(last, gate.waterY(), "leave " + gate.lakeName()));
        // Approach a lake gradually; water level corrections must not create inlet chutes.
        easeGrade(maxDrop, spacing, first, true);
        if (LakeReferences.gentleOutlet(gate.lakeName())) easeGrade(maxDrop, spacing, last, false);
        diagnostics.add(
            String.format(
                Locale.ROOT,
                "lake contact %s / %s: %.2f..%.2f; inlet eased; outlet=%s",
                name,
                gate.lakeName(),
                first,
                last,
                LakeReferences.gentleOutlet(gate.lakeName())
                    ? "natural-lake transition"
                    : "existing relief retained"));
      }
      short[] profile;
      try {
        profile = HydraulicSolver.solveTerrain(target, spacing, length, anchors, maxDrop);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            "Hydraulic solve failed for " + name + ": " + e.getMessage(), e);
      }
      River river =
          new River(
              name,
              d.raw.sourceLake(),
              d.raw.terminalLake(),
              d.sourceParent,
              d.parent,
              length,
              length,
              d.sea,
              points,
              profile,
              gates);
      solved.put(name, river);
      visiting.remove(name);
      return river;
    }

    static void append(List<XY> p, XY v) {
      if (p.isEmpty() || RouteMath.distance(p.get(p.size() - 1), v) > 0.01) p.add(v);
    }

    static void easeGrade(double[] maxDrop, double spacing, double edge, boolean inlet) {
      double reach = Geo.horizontal(512);
      int first = Math.max(0, (int) Math.floor((inlet ? edge - reach : edge) / spacing));
      int last =
          Math.min(maxDrop.length - 1, (int) Math.ceil((inlet ? edge : edge + reach) / spacing));
      for (int i = first; i <= last; i++) {
        double distance = Math.abs((i + 0.5) * spacing - edge);
        // A short flat contact apron followed by progressively steeper terrain, not 1:1 stairs.
        double rate = 0.125 + 0.875 * Geo.smooth(Geo.horizontal(64), reach, distance);
        maxDrop[i] = Math.min(maxDrop[i], spacing * rate);
      }
    }
  }
}
