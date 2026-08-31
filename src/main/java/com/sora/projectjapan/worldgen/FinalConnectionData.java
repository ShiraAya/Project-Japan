package com.sora.projectjapan.worldgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CF-01..04 connection-only runtime layer.
 *
 * <p>LR-FINAL lake shorelines and AR-FINAL authored RiverCourse centre-lines are immutable here.
 * AR-11 may still identify a declared contact whose two frozen masks do not physically overlap.
 * For exactly those cases this layer supplies a narrow connector corridor, guided by the same W05
 * logical-river point cloud and ending on a rendered final lake mask.  It is not a visible promoted
 * river and therefore does not alter the frozen 115 authored geometry registry.</p>
 */
final class FinalConnectionData {
    private static final String ROUTE_RESOURCE =
            "/assets/projectjapan/hydrology/generated/cf_final_connection_routes.csv";
    private static final int GRID_SIZE = 512;
    private static final double BANK_MARGIN = 5.0D;
    private static final Map<String, List<Point>> ROUTES = loadRoutes();
    private static final List<Bridge> BRIDGES = buildBridges();
    private static final Map<Long, List<Bridge>> GRID = buildGrid(BRIDGES);

    private FinalConnectionData() {}

    static int bridgeCount() { return BRIDGES.size(); }

    static RiverData.RiverSample mergeWithExisting(RiverData.RiverSample existing,
                                                    int worldX, int worldZ) {
        RiverData.RiverSample bridge = sampleWorld(worldX, worldZ);
        if (!bridge.corridor()) return existing;
        if (!existing.corridor()) return bridge;
        // A frozen authored/active connector wet column already owns the established channel.
        // The CF bridge exists only to close the missing interval and therefore yields there.
        if (existing.water()) return existing;
        if (bridge.water()) return bridge;
        return existing.distanceFromWater() <= bridge.distanceFromWater() ? existing : bridge;
    }

    static RiverData.RiverSample sampleWorld(int worldX, int worldZ) {
        RiverData.RiverSample best = RiverData.RiverSample.NONE;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Bridge bridge : candidates(worldX, worldZ)) {
            if (!bridge.mayContain(worldX, worldZ)) continue;
            Nearest nearest = bridge.nearest(worldX, worldZ);
            if (nearest == null) continue;
            double progress = clamp(nearest.along / bridge.length, 0.0D, 1.0D);
            double fullWidth = bridge.width;
            double halfWater = Math.max(1.5D, fullWidth * 0.5D);
            double halfCorridor = halfWater + BANK_MARGIN;
            if (nearest.distance > halfCorridor) continue;
            boolean water = nearest.distance <= halfWater + 1.25D;
            int waterY = bridge.waterY(progress);
            int depth = Math.max(2, Math.min(6, 2 + (int)Math.round(fullWidth * 0.14D)));
            double score = nearest.distance / Math.max(1.0D, halfCorridor);
            String lakeName = bridge.connectedLakeName(progress);
            double lakeBlend = bridge.lakeBlend(progress);
            RiverData.RiverSample sample = new RiverData.RiverSample(
                    true, water, bridge.displayName, nearest.distance, halfWater, halfCorridor,
                    progress, waterY, waterY - depth, depth, false, false, true,
                    waterY, waterY, false, 0.28D, Math.max(0.0D, nearest.distance - halfWater),
                    lakeName, lakeBlend, 0.0D, "", waterY, 0.0D);
            if (sample.water() != best.water()) {
                if (sample.water()) { best = sample; bestScore = score; }
            } else if (score < bestScore) {
                best = sample; bestScore = score;
            }
        }
        return best;
    }

    static List<BridgeAudit> auditSnapshots() {
        List<BridgeAudit> result = new ArrayList<>(BRIDGES.size());
        for (Bridge bridge : BRIDGES) result.add(bridge.audit());
        return List.copyOf(result);
    }

    private static List<Bridge> buildBridges() {
        List<RiverData.CFLakeGateSeed> seeds = RiverData.cfMissingRiverLakeGateSeeds();
        List<Bridge> result = new ArrayList<>(seeds.size());
        for (RiverData.CFLakeGateSeed seed : seeds) {
            String key = key(seed.river(), seed.lakeId());
            List<Point> resourceRoute = ROUTES.get(key);
            if (resourceRoute == null || resourceRoute.size() < 2) {
                throw new IllegalStateException("CF-01 missing frozen route resource for " + key);
            }
            Point expectedRiver = new Point(seed.riverX(), seed.riverZ());
            Point expectedLake = new Point(seed.lakeX(), seed.lakeZ());
            if (distance(resourceRoute.get(0), expectedRiver) > 2.0D
                    || distance(resourceRoute.get(resourceRoute.size() - 1), expectedLake) > 2.0D) {
                throw new IllegalStateException("CF-01 stale route resource for " + key
                        + ": regenerate tools/hydrology/cf_connection_route_builder.py");
            }
            result.add(new Bridge(seed, resourceRoute));
        }
        if (result.size() != ROUTES.size()) {
            throw new IllegalStateException("CF-01 route/seed count mismatch routes=" + ROUTES.size()
                    + " seeds=" + result.size());
        }
        return List.copyOf(result);
    }

    private static Map<String, List<Point>> loadRoutes() {
        InputStream stream = FinalConnectionData.class.getResourceAsStream(ROUTE_RESOURCE);
        if (stream == null) throw new IllegalStateException("Missing " + ROUTE_RESOURCE);
        Map<String, List<Point>> mutable = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);
                if (p.length != 4) throw new IllegalStateException("Bad CF route row: " + line);
                String key = p[0];
                int sequence = Integer.parseInt(p[1]);
                List<Point> points = mutable.computeIfAbsent(key, ignored -> new ArrayList<>());
                if (sequence != points.size()) throw new IllegalStateException("Non-contiguous CF route " + key);
                points.add(new Point(Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load CF routes", e);
        }
        Map<String, List<Point>> result = new HashMap<>();
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static Map<Long, List<Bridge>> buildGrid(List<Bridge> bridges) {
        Map<Long, List<Bridge>> mutable = new HashMap<>();
        for (Bridge bridge : bridges) {
            int minX = Math.floorDiv((int)Math.floor(bridge.minX), GRID_SIZE);
            int maxX = Math.floorDiv((int)Math.ceil(bridge.maxX), GRID_SIZE);
            int minZ = Math.floorDiv((int)Math.floor(bridge.minZ), GRID_SIZE);
            int maxZ = Math.floorDiv((int)Math.ceil(bridge.maxZ), GRID_SIZE);
            for (int gx = minX; gx <= maxX; gx++) for (int gz = minZ; gz <= maxZ; gz++) {
                mutable.computeIfAbsent(gridKey(gx, gz), ignored -> new ArrayList<>()).add(bridge);
            }
        }
        Map<Long, List<Bridge>> result = new HashMap<>();
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static List<Bridge> candidates(int x, int z) {
        return GRID.getOrDefault(gridKey(Math.floorDiv(x, GRID_SIZE), Math.floorDiv(z, GRID_SIZE)), List.of());
    }

    private static long gridKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static String key(String river, String lakeId) { return river + "|" + lakeId; }
    private static double clamp(double value, double lo, double hi) { return Math.max(lo, Math.min(hi, value)); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) return value >= edge1 ? 1.0D : 0.0D;
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static final class Bridge {
        final RiverData.CFLakeGateSeed seed;
        final String displayName;
        final List<Point> route;
        final double[] station;
        final double length;
        final double width;
        final int startY, endY;
        final boolean lakeAtStart;
        final double minX, maxX, minZ, maxZ;

        Bridge(RiverData.CFLakeGateSeed seed, List<Point> riverToLakeRoute) {
            this.seed = seed;
            this.displayName = "CF_CONNECTION_ONLY:" + seed.river() + "->" + seed.lakeName();
            this.lakeAtStart = seed.lakeToRiver();
            List<Point> flowRoute = new ArrayList<>(riverToLakeRoute);
            if (lakeAtStart) Collections.reverse(flowRoute);
            this.route = List.copyOf(flowRoute);
            this.startY = lakeAtStart ? seed.lakeY() : seed.riverY();
            this.endY = lakeAtStart ? seed.riverY() : seed.lakeY();
            if (endY > startY) {
                throw new IllegalStateException("CF-04 downstream rise " + seed.river() + "/" + seed.lakeId()
                        + " " + startY + "->" + endY);
            }

            RiverData.RiverSample river = RiverData.sampleWorld(seed.riverX(), seed.riverZ());
            this.width = clamp(river.water() ? river.halfWaterWidth() * 2.0D : 4.0D, 3.0D, 32.0D);
            this.station = new double[route.size()];
            double total = 0.0D;
            double loX = Double.POSITIVE_INFINITY, hiX = Double.NEGATIVE_INFINITY;
            double loZ = Double.POSITIVE_INFINITY, hiZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < route.size(); i++) {
                Point p = route.get(i);
                loX = Math.min(loX, p.x); hiX = Math.max(hiX, p.x);
                loZ = Math.min(loZ, p.z); hiZ = Math.max(hiZ, p.z);
                if (i > 0) total += distance(route.get(i - 1), p);
                station[i] = total;
            }
            this.length = Math.max(1.0D, total);
            if (Math.abs(startY - endY) > Math.floor(length) + 1) {
                throw new IllegalStateException("CF-04 insufficient run for unit steps " + seed.river()
                        + "/" + seed.lakeId() + " drop=" + (startY - endY) + " length=" + length);
            }
            double pad = width * 0.5D + BANK_MARGIN + 4.0D;
            this.minX = loX - pad; this.maxX = hiX + pad;
            this.minZ = loZ - pad; this.maxZ = hiZ + pad;
        }

        boolean mayContain(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        Nearest nearest(int x, int z) {
            Nearest best = null;
            for (int i = 0; i + 1 < route.size(); i++) {
                Point a = route.get(i), b = route.get(i + 1);
                double dx = b.x - a.x, dz = b.z - a.z;
                double denom = dx * dx + dz * dz;
                double t = denom <= 0.0D ? 0.0D
                        : clamp(((x - a.x) * dx + (z - a.z) * dz) / denom, 0.0D, 1.0D);
                double px = a.x + dx * t, pz = a.z + dz * t;
                double d = Math.hypot(x - px, z - pz);
                double along = station[i] + Math.sqrt(denom) * t;
                if (best == null || d < best.distance) best = new Nearest(d, along);
            }
            return best;
        }

        int waterY(double progress) { return (int)Math.round(lerp(startY, endY, clamp(progress, 0.0D, 1.0D))); }

        String connectedLakeName(double progress) {
            if (lakeAtStart && progress <= 0.16D) return seed.lakeName();
            if (!lakeAtStart && progress >= 0.84D) return seed.lakeName();
            return "";
        }

        double lakeBlend(double progress) {
            if (lakeAtStart && progress <= 0.16D) return 1.0D - smoothstep(0.0D, 0.16D, progress);
            if (!lakeAtStart && progress >= 0.84D) return smoothstep(0.84D, 1.0D, progress);
            return 0.0D;
        }

        BridgeAudit audit() {
            int maximumRise = 0, maximumDrop = 0;
            int previous = startY;
            int samples = Math.max(2, (int)Math.ceil(length) + 1);
            for (int i = 1; i < samples; i++) {
                int current = waterY(i / (double)(samples - 1));
                maximumRise = Math.max(maximumRise, current - previous);
                maximumDrop = Math.max(maximumDrop, previous - current);
                previous = current;
            }
            Point lakePoint = lakeAtStart ? route.get(0) : route.get(route.size() - 1);
            Point riverPoint = lakeAtStart ? route.get(route.size() - 1) : route.get(0);
            LakeData.LakeSample lake = LakeData.sampleWorld(lakePoint.x, lakePoint.z);
            RiverData.RiverSample river = RiverData.sampleWorld(riverPoint.x, riverPoint.z);
            int thirdParty = 0;
            String thirdPartyId = "";
            int routeSamples = Math.max(2, (int)Math.ceil(length / 8.0D) + 1);
            for (int i = 0; i < routeSamples; i++) {
                double along = length * i / (double)(routeSamples - 1);
                Point point = pointAt(along);
                LakeData.LakeSample owner = LakeData.sampleWorld(point.x, point.z);
                if (owner.water() && !owner.name().equals(seed.lakeName())) {
                    thirdParty++;
                    if (thirdPartyId.isEmpty()) thirdPartyId = owner.name();
                }
            }
            return new BridgeAudit(seed.river(), seed.officialIdentifier(), seed.relation(), seed.lakeId(),
                    seed.lakeName(), lakeAtStart, route.size(), length, width, startY, endY, maximumRise, maximumDrop,
                    lake.water() && lake.name().equals(seed.lakeName()), lake.waterSurfaceY(),
                    river.water(), river.name(), river.waterSurfaceY(), thirdParty, thirdPartyId,
                    lakePoint.x, lakePoint.z, riverPoint.x, riverPoint.z);
        }

        private Point pointAt(double along) {
            double s = clamp(along, 0.0D, length);
            int i = 0;
            while (i + 1 < station.length && station[i + 1] < s) i++;
            if (i + 1 >= route.size()) return route.get(route.size() - 1);
            Point a = route.get(i), b = route.get(i + 1);
            double span = Math.max(1.0E-9D, station[i + 1] - station[i]);
            double t = clamp((s - station[i]) / span, 0.0D, 1.0D);
            return new Point((int)Math.round(lerp(a.x, b.x, t)), (int)Math.round(lerp(a.z, b.z, t)));
        }
    }

    record BridgeAudit(String river, String officialIdentifier, String relation,
                       String lakeId, String lakeName, boolean lakeToRiver, int routePoints, double lengthBlocks,
                       double widthBlocks, int startY, int endY, int maximumRise,
                       int maximumDrop, boolean lakeEndpointPhysical, int lakeEndpointY,
                       boolean riverEndpointPhysical, String riverEndpointOwner, int riverEndpointY,
                       int thirdPartyLakeSamples, String firstThirdPartyLake,
                       int lakeX, int lakeZ, int riverX, int riverZ) {}

    private record Point(int x, int z) {}
    private record Nearest(double distance, double along) {}
}
