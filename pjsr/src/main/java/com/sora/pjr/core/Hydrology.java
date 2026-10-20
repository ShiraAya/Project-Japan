package com.sora.pjr.core;

import java.util.*;

/**
 * Independent vector water ownership. No block writes, surface materials or global mutable solve
 * state.
 */
public final class Hydrology {
  public enum Kind {
    DRY,
    OCEAN,
    RIVER,
    LAKE
  }

  public record Water(
      Kind kind, String owner, int bed, int level, int top, River.Hit river, Lake lake) {
    public static final Water NONE =
        new Water(Kind.DRY, "", 0, Integer.MIN_VALUE, Integer.MIN_VALUE, null, null);

    public boolean wet() {
      return kind != Kind.DRY;
    }
  }

  public record Shore(Lake lake, double distance, boolean inside) {}

  public record Context(Terrain.Sample terrain, Water water, River.Hit river, Shore shore) {}

  private static final double RIVER_BANK = Geo.horizontal(1536), LAKE_BANK = Geo.horizontal(1536);
  private final Terrain terrain;
  private final SpatialIndex<River.Segment> segments;
  private final SpatialIndex<Lake> lakes;
  private final WorldData data;

  public Hydrology(Terrain terrain, WorldData data) {
    this.terrain = terrain;
    this.data = data;
    segments =
        new SpatialIndex<>(
            data.rivers().stream().flatMap(r -> r.segments().stream()).toList(),
            (int) Geo.horizontal(2048),
            s -> s.bounds().expand(Math.max(s.a().width(), s.b().width()) / 2 + RIVER_BANK + 2));
    lakes = new SpatialIndex<>(data.lakes(), 1024, l -> l.polygon().bounds().expand(LAKE_BANK + 2));
  }

  public Context sample(int x, int z) {
    Terrain.Sample t = terrain.sample(x, z);
    boolean mappedChannel=terrain.tidalChannel(x,z);
    double channelAuthority=terrain.channelAuthority(x,z);
    Shore near = null, wetLake = null;
    for (Lake lake : lakes.at(x + 0.5, z + 0.5)) {
      if (!lake.polygon().bounds().expand(LAKE_BANK + 2).contains(x + 0.5, z + 0.5)) continue;
      double d = lake.polygon().distance(x + 0.5, z + 0.5);
      boolean inside = lake.polygon().wetBlock(x, z, d);
      Shore s = new Shore(lake, d, inside);
      if (inside && (wetLake == null || lake.id().compareTo(wetLake.lake.id()) < 0)) wetLake = s;
      if (d <= LAKE_BANK
          && (near == null
              || d < near.distance
              || d == near.distance && lake.id().compareTo(near.lake.id()) < 0)) near = s;
    }
    Map<River, River.Hit> nearest = new LinkedHashMap<>();
    for (River.Segment segment : segments.at(x + 0.5, z + 0.5)) {
      River.Hit h = segment.nearest(x + 0.5, z + 0.5);
      River.Hit previous = nearest.get(h.river());
      if (previous == null || h.distance() < previous.distance()) nearest.put(h.river(), h);
    }
    River.Hit wetRiver = null, bank = null;
    for (River.Hit h : nearest.values()) {
      double shore = h.distance() - radius(h);
      if (shore <= RIVER_BANK && (bank == null || shore < bank.distance() - radius(bank))) bank = h;
      // Widen the same surveyed water body. A model disk may open a bank only
      // when it overlaps mapped water; an offset, detached ribbon stays closed.
      double allowedRadius=radius(h);
      if(t.land() && !mappedChannel && channelAuthority>0 && terrain.mappedRiver(h.river().name())) {
        River.Point centre=h.river().at(h.station());
        double gap=terrain.channelShoreDistance((int)Math.floor(centre.x()),(int)Math.floor(centre.z()));
        if(gap>Math.max(0,radius(h)-2) || terrain.channelShoreDistance(x,z)>2)
          allowedRadius*=1-channelAuthority;
      }
      if (h.distance()>allowedRadius || allowedRadius==0) continue;
      if (wetRiver == null || choose(h, wetRiver)) wetRiver = h;
    }
    Water water = Water.NONE;
    if (wetLake != null) {
      Lake lake = wetLake.lake;
      int bed = lake.bed(x, z, wetLake.distance);
      if (wetRiver != null
          && wetRiver.river().connects(lake, wetRiver.station(), wetRiver.width())) {
        // Union each physical inlet/outlet with the shallow lake shelf, without changing the level.
        int riverBed = lake.waterY() - Math.min(lake.basinDepth(), depth(wetRiver));
        double blend =
            1 - Geo.smooth(0, Math.max(Geo.horizontal(128), wetRiver.width()), wetLake.distance);
        bed = Math.min(bed, (int) Math.round(bed + (riverBed - bed) * blend));
      }
      water =
          new Water(
              Kind.LAKE,
              lake.id(),
              Math.max(Geo.MIN_Y + 1, bed),
              lake.waterY(),
              lake.waterY(),
              wetRiver,
              lake);
    } else if(mappedChannel && channelAuthority>0) {
      // One surveyed water body, even where the W05 centreline is offset or absent.
      // These are coastal tidal channels at sea level, not independent sea basins.
      double inward=Math.max(0,-terrain.channelShoreDistance(x,z));
      int bed=-Math.max(1,(int)Math.round(1+5*Geo.smooth(0,Geo.horizontal(96),inward)));
      River.Hit owner=bank!=null && bank.distance()<=Math.max(64,radius(bank)*3)?bank:null;
      water=new Water(Kind.RIVER,owner==null?"tidal:tokyo-bay":owner.river().name(),bed,0,0,owner,null);
    } else if (wetRiver != null && (t.land() || wetRiver.river().sea())) {
      int level = wetRiver.river().water(wetRiver.station());
      int top = wetRiver.river().top(wetRiver.station());
      if (!t.land()) {
        level = 0;
        top = 0;
      }
      int bed = Math.max(Geo.MIN_Y + 1, level - depth(wetRiver));
      // Water ownership chooses a level/name, not a single excavation. Union compatible
      // channels so the parent's shallow edge cannot cap the tributary's deeper thalweg.
      for (River.Hit h : nearest.values()) {
        if (h.distance() <= radius(h)
            && (Math.abs(h.river().water(h.station()) - level) <= 1
                || h.river().related(wetRiver.river())))
          bed = Math.min(bed, level - depth(h));
      }
      if (wetRiver.river().sea() && level == Geo.SEA) {
        // The sea owns submerged estuaries. Fade the additional river excavation into
        // the shelf on both sides of the coast, instead of ending a deep gravel capsule
        // offshore. Keep the lower inland channel unchanged outside this short apron.
        double signedCoast = terrain.coastDistance(x,z) * (t.land() ? 1 : -1);
        double channel = Geo.smooth(-Geo.horizontal(48), Geo.horizontal(128), signedCoast);
        int shelf = t.land() ? -1 : oceanBed(x,z);
        bed = shelf + (int)Math.round((Math.min(bed,shelf)-shelf)*channel);
      }
      // A different lake's shore remains closed. Declared gates keep their own ownership.
      boolean blocked =
          near != null
              && !near.inside
              && near.distance < Geo.horizontal(24)
              && near.lake.waterY() > level + Geo.vertical(32)
              && !wetRiver.river().connects(near.lake, wetRiver.station(), wetRiver.width());
      if (!blocked)
        water = t.land()
            ? new Water(Kind.RIVER, wetRiver.river().name(), bed, level, top, wetRiver, null)
            : new Water(terrain.tidalChannel(x,z)?Kind.RIVER:Kind.OCEAN, terrain.tidalChannel(x,z)?wetRiver.river().name():"ocean", bed, 0, 0, wetRiver, null);
    }
    if (!water.wet() && !t.land())
      water = new Water(terrain.tidalChannel(x,z)?Kind.RIVER:Kind.OCEAN, terrain.tidalChannel(x,z)?"tidal:tokyo-bay":"ocean", oceanBed(x,z), 0, 0, null, null);
    double tidalAuthority=terrain.surveyedTidalAuthority(x,z);
    if(water.wet() && water.kind()!=Kind.LAKE && water.level()==0 && tidalAuthority>0) {
      // The surveyed river and adjoining marine polygons are one connected bed.
      // Do not excavate a second W05 trench or restart a beach at their class seam.
      int bed=water.kind()==Kind.OCEAN?oceanBed(x,z):
          (int)Math.round(water.bed()+(surveyedBed(x,z)-water.bed())*tidalAuthority);
      water=new Water(water.kind(),water.owner(),bed,water.level(),water.top(),water.river(),null);
    }
    return new Context(t, water, bank, wetLake != null ? wetLake : near);
  }

  private static boolean choose(River.Hit a, River.Hit b) {
    if (a.river().related(b.river())) {
      if (a.river().name().equals(b.river().terminalRiver())) return true;
      if (b.river().name().equals(a.river().terminalRiver())) return false;
    }
    double sa = a.distance() / radius(a), sb = b.distance() / radius(b);
    return sa < sb || sa == sb && a.river().name().compareTo(b.river().name()) < 0;
  }

  public static double radius(River.Hit h) {
    return h.width() / 2 + 0.75;
  }

  public static int depth(River.Hit h) {
    double p = Geo.clamp(h.station() / h.river().mouthDistance(), 0, 1);
    double mature = Geo.vertical(1.5 + Math.sqrt(h.width() * Geo.METRES_XZ) * 0.55 + p * 2);
    // Additional lower-river thalweg depth is visual shaping, not a measured bathymetry.
    // Fade it by width as well as progress so a tiny tributary does not become a deep slit.
    mature +=
        Geo.vertical(8)
            * Geo.smooth(0.55, 0.95, p)
            * Geo.smooth(Geo.horizontal(12), Geo.horizontal(80), h.width());
    RiverShapes.Shape shape = RiverShapes.get(h.river().name());
    if (shape != null) mature = Math.min(mature, shape.maxChannelDepth());
    double lateral = Geo.clamp(h.distance() / radius(h), 0, 1);
    return Math.max(1, (int) Math.round(1 + Math.max(0, mature - 1) * (1 - lateral * lateral)));
  }

  public WorldData data() {
    return data;
  }

  public Terrain terrain() { return terrain; }

  /** Continuous envelopes of every nearby branch; nearest-owner changes cannot cut a cliff. */
  public int bankGround(int x, int z, Context context) {
    int natural = context.terrain().ground();
    double cut = natural, support = Geo.MIN_Y;
    Map<River, River.Hit> bankChannels = new HashMap<>();
    Map<String, Double> outletCaps = new HashMap<>();
    List<River.Hit> bankHits = new ArrayList<>();
    // The dry coastal foot joins sea level; the band widens with relief. High rocky
    // coasts remain steeper, while a low beach no longer stands on the old 48-block wall.
    double coast = terrain.coastDistance(x,z);
    if (coast < Geo.horizontal(1536)) cut = Math.round(bankCut(natural, 0, coast, 192));
    double channelShore=terrain.channelShoreDistance(x,z);
    if(terrain.surveyedTidalAuthority(x,z)>0 && channelShore>=0 && channelShore<Geo.horizontal(192))
      cut=Math.min(cut,bankCut(natural,0,channelShore,96));
    double channelAuthority=terrain.channelAuthority(x,z);
    for (River.Segment segment : segments.at(x + 0.5, z + 0.5)) {
      River.Hit h = segment.nearest(x + 0.5, z + 0.5);
      River.Hit previous = bankChannels.get(h.river());
      if (previous == null || h.distance() < previous.distance()) bankChannels.put(h.river(), h);
      if(channelAuthority>=.999 && terrain.mappedRiver(h.river().name())
          && channelShore>Geo.horizontal(8))continue;
      double edge = Math.max(0, h.distance() - radius(h));
      if (edge >= RIVER_BANK) continue;
      bankHits.add(h);
      // Every connected downstream reach contributes a continuous valley envelope.
      // A nearest-reach switch at a bend must never turn drainage on or off.
      for (River.Gate gate : h.river().gates()) {
        double downstream = h.station() - gate.last();
        if (downstream < 0 || downstream >= LAKE_BANK * 2) continue;
        double outletLevel = gate.waterY() + (h.river().top(h.station()) - gate.waterY())
            * (1 - Geo.smooth(LAKE_BANK, LAKE_BANK * 2, downstream));
        if (outletLevel >= gate.waterY()) continue;
        // Distance-cone clearance prevents a low reach farther downstream
        // from dragging the whole high shoreline into its own excavation.
        double cap = outletLevel + Math.max(0, edge - 2) * 1.25;
        outletCaps.merge(gate.lakeId(), cap, Math::min);
      }
    }
    // Blend equidistant reaches before building the support envelope. Selecting
    // exactly one reach produced a Voronoi cliff across bends and outlet slopes.
    Map<River, double[]> bankBlends = new HashMap<>();
    for (River.Hit h : bankHits) {
      River.Hit nearest = bankChannels.get(h.river());
      double spread = Math.max(2, Math.min(48, nearest.distance() * 0.5));
      double weight = Math.max(0, 1 - (h.distance() - nearest.distance()) / spread);
      if (weight == 0) continue;
      weight *= weight * weight;
      double edge = Math.max(0, h.distance() - radius(h));
      double value = bankSupport(natural, h.river().top(h.station()), edge);
      double level = h.river().top(h.station());
      level += (h.river().bankWater(h.station()) - level)
          * Geo.smooth(Geo.horizontal(8), Geo.horizontal(64), edge);
      double[] blend = bankBlends.computeIfAbsent(h.river(), ignored -> new double[3]);
      blend[0] += weight; blend[1] += weight * value;
      blend[2] += weight * bankCut(natural, level, edge, 128);
    }
    for (var entry : bankBlends.entrySet()) {
      River.Hit nearest = bankChannels.get(entry.getKey());
      double edge = Math.max(0, nearest.distance() - radius(nearest));
      double localLevel = nearest.river().top(nearest.station());
      double local = bankSupport(natural, localLevel, edge);
      double blended = entry.getValue()[1] / entry.getValue()[0];
      // Near actual water retain its local level. Wider slopes use the blended
      // reach field. Above-DEM water needs a graded bank, not a one-cell sill.
      double value = local + (blended - local) * Geo.smooth(2, 16, edge);
      support = Math.max(support, value);
      // Distant low reaches must not excavate the entire upstream hillside.
      // Blend the equally near branches; keep the actual shore foot at its local level.
      double localCut = bankCut(natural, localLevel, edge, 128);
      double blendedCut = entry.getValue()[2] / entry.getValue()[0];
      cut = Math.min(cut, localCut + (blendedCut - localCut) * Geo.smooth(2, 16, edge));
    }
    for (Lake lake : lakes.at(x + 0.5, z + 0.5)) {
      if (!lake.polygon().bounds().expand(LAKE_BANK).contains(x + 0.5, z + 0.5)) continue;
      double edge = lake.polygon().distance(x + 0.5, z + 0.5);
      if (edge >= LAKE_BANK) continue;
      cut = Math.min(cut, bankCut(natural, lake.waterY(), edge, 192));
      // A C1 apron meets the DEM with zero residual height and slope,
      // instead of intersecting it with a straight support cone.
      double lakeSupport = bankSupport(natural, lake.waterY(), edge);
      Double outletCap = outletCaps.get(lake.id());
      if (outletCap != null) {
        // Keep the rasterized wet shoreline closed; the downstream valley and
        // its surrounding slope join continuously beyond this narrow collar.
        lakeSupport += (Math.min(lakeSupport, outletCap) - lakeSupport)
            * Geo.smooth(3, 12, edge);
      }
      support = Math.max(support, lakeSupport);
    }
    return (int) Math.ceil(Math.max(cut, support));
  }

  private static double bankSupport(int natural, double level, double edge) {
    double clearance = Math.max(0, edge - 2);
    double excess = level - natural - clearance * .5;
    double band = Math.min(32, clearance * .5);
    if (band > 0 && Math.abs(excess) < band) {
      double u = Math.abs(excess) / band;
      // The support and natural terrain meet tangentially, without a straight outer lip.
      excess = Math.copySign(band * u * u * (2 - u), excess);
    }
    return natural + excess;
  }

  private static double bankCut(int natural, double level, double edge, double minimumMetres) {
    double width =
        Geo.horizontal(
            Math.min(
                1536, Math.max(minimumMetres, Math.max(0, natural - level) * Geo.METRES_Y * 3.6)));
    return Math.min(natural, level + (natural - level) * Geo.smooth(0.75, width, edge));
  }

  /** Low ground qualifies as beach only within 24 metres of an actual ocean-mask boundary. */
  public boolean coastalBeach(int x, int z) {
    double distance=terrain.coastDistance(x,z);
    if(Double.isFinite(distance)) return distance <= Geo.horizontal(24);
    // Small synthetic/custom Terrain implementations may supply samples without a contour.
    int reach=(int)Geo.horizontal(24);
    for(int dz=-reach;dz<=reach;dz++) for(int dx=-reach;dx<=reach;dx++)
      if(dx*dx+dz*dz<=reach*reach && !terrain.sample(x+dx,z+dz).land()) return true;
    return false;
  }

  /** Synthetic shelf, explicitly not measured bathymetry. First wet cells are one block deep. */
  public int oceanBed(int x, int z) {
    double distance=terrain.coastDistance(x,z);
    double shelf=Geo.smooth(0,Geo.horizontal(2400),distance);
    int base=-Math.max(1,(int)Math.round(1+(Geo.vertical(128)-1)*shelf));
    double authority=terrain.surveyedTidalAuthority(x,z);
    return authority==0?base:(int)Math.round(base+(surveyedBed(x,z)-base)*authority);
  }

  /** Model depth follows the shared wet perimeter, independently of river/sea labels. */
  private int surveyedBed(int x,int z) {
    double d=Math.max(0,-terrain.channelShoreDistance(x,z));
    double depth=1+Geo.smooth(0,6,d)+4*Geo.smooth(6,36,d)+42*Geo.smooth(36,900,d);
    return -Math.max(1,(int)Math.round(depth));
  }

  public List<River.Segment> indexedSegmentsAt(int x, int z) {
    return segments.at(x, z);
  }
}
