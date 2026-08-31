package com.sora.projectjapan.worldgen;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * RFIX-10 frozen topology-owner water corridors.
 *
 * <p>These are not visible P0/P1/P2 imports and do not enter RiverData's 115-course registry.
 * They exist only where a later connector must have a real downstream owner before nationwide
 * river import. The Urabandai three-lake no-waterway policy currently keeps this resource empty;
 * historical Nagase topology remains metadata-only and owns no runtime water.</p>
 */
final class FrozenRiverTopologyData {
    private static final String RESOURCE = "/assets/projectjapan/hydrology/generated/urabandai_topology.bin.gz";
    private static final int MAGIC = 0x504A5554; // PJUT
    private static final int VERSION = 2;
    private static final int GRID_SIZE = 512;
    private static final double BANK_MARGIN = 5.0D;

    private static final List<Owner> OWNERS = load();
    private static final Map<Long, List<Owner>> GRID = buildGrid();

    private FrozenRiverTopologyData() {}

    static int ownerCount() { return OWNERS.size(); }

    static List<LakeGraph.Point> routeForAudit(String ownerId) {
        for (Owner owner : OWNERS) if (owner.id.equals(ownerId)) return owner.route;
        return List.of();
    }

    static String ownerIdAt(int x, int z) {
        Sample sample = sampleOwner(x, z);
        return sample.water ? sample.owner.id : "";
    }

    static int waterYAt(int x, int z) {
        Sample sample = sampleOwner(x, z);
        return sample.water ? sample.waterY : Integer.MIN_VALUE;
    }

    static RiverData.RiverSample sampleWorld(int x, int z) {
        Sample sample = sampleOwner(x, z);
        if (sample.owner == null) return RiverData.RiverSample.NONE;
        Owner owner = sample.owner;
        double progress = sample.progress;
        double fullWidth = lerp(owner.startWidth, owner.endWidth, progress);
        double halfWater = Math.max(1.5D, fullWidth * 0.5D);
        int depth = Math.max(2, (int)Math.round(clamp(2.0D + fullWidth * 0.18D, 2.0D, 5.0D)));
        String lakeName = "";
        double lakeBlend = 0.0D;
        if (progress <= 0.08D) {
            lakeName = LakeData.nameById(owner.sourceLakeId);
            lakeBlend = 1.0D - smoothstep(0.0D, 0.08D, progress);
        } else if (progress >= 0.92D) {
            lakeName = LakeData.nameById(owner.targetLakeId);
            lakeBlend = smoothstep(0.92D, 1.0D, progress);
        }
        return new RiverData.RiverSample(true, sample.water, "TOPOLOGY_OWNER:" + owner.riverName,
                sample.distance, halfWater, halfWater + BANK_MARGIN, progress,
                sample.waterY, sample.waterY - depth, depth, false, false, false,
                sample.waterY, sample.waterY, false, 0.28D,
                Math.max(0.0D, sample.distance - halfWater), lakeName, lakeBlend, 0.0D,
                "", sample.waterY, 0.0D);
    }

    static List<OwnerAudit> auditSnapshots() {
        List<OwnerAudit> result = new ArrayList<>();
        for (Owner owner : OWNERS) {
            int startY = LakeData.waterSurfaceYById(owner.sourceLakeId);
            int endY = LakeData.waterSurfaceYById(owner.targetLakeId);
            int maxRise = 0, maxDrop = 0;
            int previous = startY;
            int samples = Math.max(2, (int)Math.ceil(owner.length) + 1);
            for (int i = 1; i < samples; i++) {
                int y = owner.waterY(i / (double)(samples - 1));
                maxRise = Math.max(maxRise, y - previous);
                maxDrop = Math.max(maxDrop, previous - y);
                previous = y;
            }
            result.add(new OwnerAudit(owner.id, owner.riverId, owner.riverName,
                    owner.sourceLakeId, owner.targetLakeId, owner.length,
                    startY, endY, maxRise, maxDrop, owner.route.size()));
        }
        return List.copyOf(result);
    }

    private static Sample sampleOwner(int x, int z) {
        Owner bestOwner = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestProgress = 0.0D;
        boolean bestWater = false;
        for (Owner owner : GRID.getOrDefault(gridKey(Math.floorDiv(x, GRID_SIZE), Math.floorDiv(z, GRID_SIZE)), List.of())) {
            Nearest nearest = owner.nearest(x, z);
            if (nearest == null) continue;
            double progress = clamp(nearest.along / owner.length, 0.0D, 1.0D);
            double fullWidth = lerp(owner.startWidth, owner.endWidth, progress);
            double halfWater = Math.max(1.5D, fullWidth * 0.5D);
            double halfCorridor = halfWater + BANK_MARGIN;
            if (nearest.distance > halfCorridor) continue;
            boolean water = nearest.distance <= halfWater + 1.25D;
            if (bestOwner == null || (water && !bestWater)
                    || (water == bestWater && nearest.distance < bestDistance)) {
                bestOwner = owner;
                bestDistance = nearest.distance;
                bestProgress = progress;
                bestWater = water;
            }
        }
        if (bestOwner == null) return Sample.NONE;
        return new Sample(bestOwner, bestProgress, bestDistance, bestWater,
                bestOwner.waterY(bestProgress));
    }

    private static List<Owner> load() {
        try (InputStream raw = FrozenRiverTopologyData.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("Missing " + RESOURCE);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid Urabandai topology magic");
                if (in.readInt() != VERSION) throw new IOException("Unsupported Urabandai topology version");
                int count = readCount(in, 128, "topology owners");
                List<Owner> result = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String id = readString(in), riverId = readString(in), riverName = readString(in);
                    String sourceLakeId = readString(in), targetLakeId = readString(in);
                    double startWidth = in.readFloat(), endWidth = in.readFloat();
                    int points = readCount(in, 100_000, "topology owner points");
                    List<LakeGraph.Point> route = new ArrayList<>(points);
                    for (int p = 0; p < points; p++) route.add(new LakeGraph.Point(in.readInt(), in.readInt()));
                    int anchorCount = readCount(in, 64, "topology hydraulic anchors");
                    List<HydraulicAnchor> anchors = new ArrayList<>(anchorCount);
                    for (int a = 0; a < anchorCount; a++) {
                        anchors.add(new HydraulicAnchor(readString(in), in.readFloat()));
                    }
                    result.add(new Owner(id, riverId, riverName, sourceLakeId, targetLakeId,
                            startWidth, endWidth, List.copyOf(route), List.copyOf(anchors)));
                }
                return List.copyOf(result);
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Map<Long, List<Owner>> buildGrid() {
        Map<Long, List<Owner>> result = new HashMap<>();
        for (Owner owner : OWNERS) {
            int minGX = Math.floorDiv((int)Math.floor(owner.minX), GRID_SIZE);
            int maxGX = Math.floorDiv((int)Math.ceil(owner.maxX), GRID_SIZE);
            int minGZ = Math.floorDiv((int)Math.floor(owner.minZ), GRID_SIZE);
            int maxGZ = Math.floorDiv((int)Math.ceil(owner.maxZ), GRID_SIZE);
            for (int gx = minGX; gx <= maxGX; gx++) for (int gz = minGZ; gz <= maxGZ; gz++) {
                result.computeIfAbsent(gridKey(gx, gz), ignored -> new ArrayList<>()).add(owner);
            }
        }
        Map<Long, List<Owner>> frozen = new HashMap<>();
        for (var e : result.entrySet()) frozen.put(e.getKey(), List.copyOf(e.getValue()));
        return Map.copyOf(frozen);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readCount(in, 1 << 20, "string bytes");
        byte[] bytes = new byte[len]; in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream in, int max, String label) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("Invalid " + label + ": " + count);
        return count;
    }

    private static long gridKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    private static double smoothstep(double a, double b, double x) {
        if (a == b) return x >= b ? 1.0D : 0.0D;
        double t = clamp((x - a) / (b - a), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static final class Owner {
        final String id, riverId, riverName, sourceLakeId, targetLakeId;
        final double startWidth, endWidth, length, minX, maxX, minZ, maxZ;
        final List<LakeGraph.Point> route;
        final List<HydraulicAnchor> hydraulicAnchors;
        final double[] station;

        Owner(String id, String riverId, String riverName, String sourceLakeId, String targetLakeId,
              double startWidth, double endWidth, List<LakeGraph.Point> route, List<HydraulicAnchor> hydraulicAnchors) {
            this.id = id; this.riverId = riverId; this.riverName = riverName;
            this.sourceLakeId = sourceLakeId; this.targetLakeId = targetLakeId;
            this.startWidth = startWidth; this.endWidth = endWidth; this.route = route;
            this.hydraulicAnchors = hydraulicAnchors.stream()
                    .sorted(java.util.Comparator.comparingDouble(HydraulicAnchor::progress)).toList();
            if (route.size() < 2) throw new IllegalStateException("Topology owner route too short: " + id);
            double previousProgress = 0.0D;
            int previousY = LakeData.waterSurfaceYById(sourceLakeId);
            for (HydraulicAnchor anchor : this.hydraulicAnchors) {
                if (anchor.progress() <= previousProgress || anchor.progress() >= 1.0D)
                    throw new IllegalStateException("Invalid topology hydraulic anchor progress: " + id + " " + anchor);
                int y = LakeData.waterSurfaceYById(anchor.lakeId());
                if (y > previousY) throw new IllegalStateException("Topology hydraulic anchor rises downstream: " + id);
                previousProgress = anchor.progress(); previousY = y;
            }
            int targetY = LakeData.waterSurfaceYById(targetLakeId);
            if (targetY > previousY) throw new IllegalStateException("Topology owner target rises downstream: " + id);
            this.station = new double[route.size()];
            double total = 0.0D;
            double loX = Double.POSITIVE_INFINITY, hiX = Double.NEGATIVE_INFINITY;
            double loZ = Double.POSITIVE_INFINITY, hiZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < route.size(); i++) {
                var p = route.get(i);
                loX = Math.min(loX, p.x()); hiX = Math.max(hiX, p.x());
                loZ = Math.min(loZ, p.z()); hiZ = Math.max(hiZ, p.z());
                if (i > 0) {
                    var a = route.get(i - 1);
                    total += Math.hypot(p.x() - a.x(), p.z() - a.z());
                }
                station[i] = total;
            }
            this.length = Math.max(1.0D, total);
            double pad = Math.max(startWidth, endWidth) * 0.5D + BANK_MARGIN + 4.0D;
            this.minX = loX - pad; this.maxX = hiX + pad; this.minZ = loZ - pad; this.maxZ = hiZ + pad;
        }

        int waterY(double progress) {
            double p = clamp(progress, 0.0D, 1.0D);
            double leftP = 0.0D;
            int leftY = LakeData.waterSurfaceYById(sourceLakeId);
            for (HydraulicAnchor anchor : hydraulicAnchors) {
                double rightP = anchor.progress();
                int rightY = LakeData.waterSurfaceYById(anchor.lakeId());
                if (p <= rightP) {
                    double t = (p - leftP) / Math.max(1.0E-9D, rightP - leftP);
                    return (int)Math.round(lerp(leftY, rightY, clamp(t, 0.0D, 1.0D)));
                }
                leftP = rightP; leftY = rightY;
            }
            int targetY = LakeData.waterSurfaceYById(targetLakeId);
            double t = (p - leftP) / Math.max(1.0E-9D, 1.0D - leftP);
            return (int)Math.round(lerp(leftY, targetY, clamp(t, 0.0D, 1.0D)));
        }

        Nearest nearest(int x, int z) {
            Nearest best = null;
            for (int i = 0; i + 1 < route.size(); i++) {
                var a = route.get(i); var b = route.get(i + 1);
                double dx = b.x() - a.x(), dz = b.z() - a.z();
                double denom = dx * dx + dz * dz;
                double t = denom <= 0.0D ? 0.0D
                        : clamp(((x - a.x()) * dx + (z - a.z()) * dz) / denom, 0.0D, 1.0D);
                double px = a.x() + dx * t, pz = a.z() + dz * t;
                double d = Math.hypot(x - px, z - pz);
                double along = station[i] + Math.sqrt(denom) * t;
                if (best == null || d < best.distance) best = new Nearest(d, along);
            }
            return best;
        }
    }

    static List<HydraulicAnchorAudit> hydraulicAnchorAudit() {
        List<HydraulicAnchorAudit> out = new ArrayList<>();
        for (Owner owner : OWNERS) for (HydraulicAnchor anchor : owner.hydraulicAnchors) {
            out.add(new HydraulicAnchorAudit(owner.id, anchor.lakeId(), anchor.progress(),
                    LakeData.waterSurfaceYById(anchor.lakeId()), owner.waterY(anchor.progress())));
        }
        return List.copyOf(out);
    }

    record HydraulicAnchorAudit(String ownerId, String lakeId, double progress, int lakeY, int ownerY) {}
    private record HydraulicAnchor(String lakeId, double progress) {}

    record OwnerAudit(String ownerId, String riverId, String riverName,
                      String sourceLakeId, String targetLakeId, double lengthBlocks,
                      int startY, int endY, int maximumRise, int maximumDrop, int routePoints) {}
    private record Nearest(double distance, double along) {}
    private record Sample(Owner owner, double progress, double distance, boolean water, int waterY) {
        static final Sample NONE = new Sample(null, 0.0D, Double.POSITIVE_INFINITY, false, Integer.MIN_VALUE);
    }
}
