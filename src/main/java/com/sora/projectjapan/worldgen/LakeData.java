package com.sora.projectjapan.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nationwide lake and lagoon footprints for Project Japan.
 *
 * <p>At PJ's 1:8 horizontal scale a literal full-resolution shoreline would add a large amount of
 * data without improving city-scale play. Compact rotated lobes retain the measured centre, area
 * and orientation, but they are only a control scaffold: deterministic multi-scale shoreline
 * displacement removes geometric ellipse edges and narrow connectors keep multi-lobe lakes
 * continuous. The generator then builds a shallow littoral shelf before approaching the recorded
 * or representative maximum depth.</p>
 */
public final class LakeData {
    private static final double BLOCKS_PER_KILOMETRE = 1000.0D
            / TerrainData.HORIZONTAL_METRES_PER_BLOCK;
    private static final int LAKE_GRID_SIZE = 4096;
    private static final int SAMPLE_CACHE_SIZE = 2048;
    private static final List<Lake> LAKES = buildLakes();
    private static final Map<Long, List<Lake>> LAKE_GRID = buildLakeGrid(LAKES);
    private static final ThreadLocal<SampleCache> SAMPLE_CACHE =
            ThreadLocal.withInitial(() -> new SampleCache(SAMPLE_CACHE_SIZE));

    private LakeData() {}

    private static List<Lake> buildLakes() {
        List<Lake> lakes = new ArrayList<>();

        // Hokkaido
        lakes.add(lake("Saroma", 0.0, 19.6, 151.63, 0.91,
                lobe(143.70, 44.13, 5.8, 2.8, 15),
                lobe(143.80, 44.12, 6.2, 2.7, 5),
                lobe(143.91, 44.11, 6.0, 2.5, -5),
                lobe(144.01, 44.10, 4.2, 2.1, -14)));
        lakes.add(lake("Kussharo", 121.0, 117.5, 79.54, 0.44132,
                lobe(144.34, 43.61, 13.0, 10.0, -8)));
        lakes.add(lake("Mashu", 351.3, 211.4, 19.22, 0.75262,
                lobe(144.53, 43.58, 3.6, 3.0, 2)));
        lakes.add(lake("Akan", 420.0, 44.8, 13.25, 0.37485,
                lobe(144.10, 43.45, 6.3, 4.0, 18),
                lobe(144.16, 43.44, 2.4, 2.0, -10)));
        lakes.add(lake("Shikotsu", 248.0, 360.1, 78.48, 0.79405,
                lobe(141.35, 42.77, 7.2, 5.5, -8)));
        lakes.add(lake("Toya", 83.948, 179.7, 70.72, 0.97007,
                lobe(140.84, 42.60, 5.2, 4.6, 8)));
        lakes.add(lake("Furen", 0.0, 13.0, 64.17, 0.88,
                lobe(145.24, 43.30, 4.0, 2.2, -18),
                lobe(145.31, 43.29, 4.6, 2.0, 4),
                lobe(145.38, 43.28, 3.4, 1.7, 18),
                lobe(145.29, 43.34, 2.2, 1.2, -25)));
        lakes.add(lake("Abashiri", 0.0, 16.3, 32.27, 0.79619,
                lobe(144.17, 43.96, 5.4, 3.0, -18)));
        lakes.add(lake("Kuttara", 258.0, 148.0, 4.7, 0.87597,
                lobe(141.18, 42.50, 1.5, 1.3, 0)));
        lakes.add(lake("Onuma", 129.0, 11.6, 9.12, 0.54141,
                lobe(140.67, 41.98, 3.6, 2.2, 15),
                lobe(140.73, 41.99, 1.8, 1.1, -18)));

        // Tohoku and Kanto
        lakes.add(lake("Ogawara", 0.0, 26.5, 61.96, 0.89527,
                lobe(141.34, 40.75, 6.0, 4.1, -5)));
        lakes.add(lake("Jusan", 0.0, 1.5, 17.81, 0.72,
                lobe(140.34, 41.02, 2.8, 1.8, -12),
                lobe(140.38, 41.01, 2.5, 1.5, 18),
                lobe(140.34, 41.05, 1.6, 1.2, -28)));
        lakes.add(lake("Towada", 400.0, 326.8, 61.1, 0.74888,
                lobe(140.88, 40.46, 5.7, 5.1, 8),
                lobe(140.92, 40.42, 2.0, 2.8, -20)));
        lakes.add(lake("Tazawa", 249.0, 423.4, 25.8, 0.97276,
                lobe(140.66, 39.72, 3.1, 2.8, 0)));
        lakes.add(lake("Inawashiro", 514.0, 93.5, 103.3, 0.85741,
                lobe(140.10, 37.48, 8.6, 5.2, -10)));
        lakes.add(lake("Hibara", 822.0, 30.5, 10.86, 0.56397,
                lobe(140.05, 37.70, 4.7, 1.8, -15),
                lobe(140.08, 37.66, 2.0, 1.2, 20)));
        lakes.add(lake("Chuzenji", 1269.0, 163.0, 11.9, 0.53097,
                lobe(139.46, 36.74, 5.6, 2.4, 14)));
        lakes.add(lake("Kasumigaura", 0.16, 11.9, 168.2, 0.65737,
                lobe(140.38, 36.05, 12.0, 7.2, 23),
                lobe(140.48, 36.02, 8.0, 3.3, -8),
                lobe(140.31, 36.12, 5.2, 2.4, 36)));
        lakes.add(lake("Kitaura", 0.26, 10.0, 35.04, 0.50,
                // Kitaura is a north-south lake; the old east-west radii were transposed.
                lobe(140.57, 36.04, 2.7, 5.5, -5),
                lobe(140.56, 36.13, 2.5, 5.8, 5),
                lobe(140.55, 36.21, 2.1, 4.0, -8)));

        // Chubu. The final shape-scale value on every entry calibrates the simplified
        // multi-lobe footprint to the official/reference surface area while preserving the
        // hand-authored broad outline. This prevents nominal widths from being misused as radii.
        lakes.add(lake("Suwa", 759.0, 7.6, 12.81, 0.97943,
                lobe(138.08, 36.05, 2.5, 1.7, 12)));
        lakes.add(lake("Nojiri", 656.84, 38.3, 4.45, 0.6705,
                lobe(138.21, 36.83, 2.1, 1.5, -12)));
        lakes.add(lake("Haruna", 1084.0, 13.0, 1.19, 0.79794,
                lobe(138.87, 36.48, 0.85, 0.70, 0)));
        lakes.add(lake("Ashinoko", 724.5, 40.6, 7.03, 0.5383,
                lobe(139.00, 35.20, 3.7, 1.6, 8),
                lobe(139.02, 35.16, 1.8, 1.0, -25)));
        lakes.add(lake("Yamanaka", 980.5, 12.9, 6.57, 0.68323,
                lobe(138.87, 35.42, 3.2, 1.4, 5)));
        lakes.add(lake("Kawaguchi", 830.5, 14.0, 5.52, 0.62476,
                lobe(138.76, 35.51, 3.0, 1.5, -10)));
        lakes.add(lake("Sai", 900.0, 71.5, 2.1, 0.51704,
                lobe(138.68, 35.50, 2.5, 1.0, 5)));
        lakes.add(lake("Shoji", 900.0, 12.6, 0.51, 0.41118,
                lobe(138.61, 35.49, 1.2, 0.8, -8)));
        lakes.add(lake("Motosu", 900.0, 121.2, 4.7, 0.5583,
                lobe(138.59, 35.46, 2.4, 2.0, -18)));
        lakes.add(lake("Hamana", 0.0, 16.1, 70.28, 0.725,
                lobe(137.60, 34.70, 4.2, 2.8, 12),
                lobe(137.61, 34.75, 3.4, 3.8, -12),
                lobe(137.64, 34.80, 2.8, 3.5, 8),
                lobe(137.56, 34.75, 2.8, 1.4, -22),
                lobe(137.67, 34.76, 2.4, 1.3, 18)));

        // Kinki and Chugoku. Lake Biwa remains predominantly north-south; its calibrated
        // multi-lobe outline now also matches the official/reference surface area.
        lakes.add(lake("Biwa", 84.547, 103.8, 669.22, 0.85984,
                lobe(136.04, 35.27, 8.8, 25.5, -8),
                lobe(135.99, 35.08, 5.8, 10.5, -18),
                lobe(136.08, 35.45, 5.8, 9.0, 8)));
        lakes.add(lake("Shinji", 0.0, 5.8, 79.26, 1.01277,
                lobe(132.96, 35.45, 8.2, 3.0, -5)));
        lakes.add(lake("Nakaumi", 0.0, 18.4, 85.83, 0.70818,
                lobe(133.20, 35.48, 9.5, 4.5, 7),
                lobe(133.28, 35.52, 4.5, 2.6, -20)));

        // Kyushu
        lakes.add(lake("Ikeda", 66.0, 233.0, 10.91, 0.56007,
                lobe(130.58, 31.23, 4.1, 2.7, -10)));

        return List.copyOf(lakes);
    }

    private static Map<Long, List<Lake>> buildLakeGrid(List<Lake> lakes) {
        Map<Long, List<Lake>> mutable = new HashMap<>();
        for (Lake lake : lakes) {
            int minCellX = gridCoord(lake.minX);
            int maxCellX = gridCoord(lake.maxX);
            int minCellZ = gridCoord(lake.minZ);
            int maxCellZ = gridCoord(lake.maxZ);
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    mutable.computeIfAbsent(gridKey(cellX, cellZ), ignored -> new ArrayList<>())
                            .add(lake);
                }
            }
        }
        Map<Long, List<Lake>> result = new HashMap<>(mutable.size());
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static List<Lake> candidateLakes(int worldX, int worldZ) {
        return LAKE_GRID.getOrDefault(gridKey(gridCoord(worldX), gridCoord(worldZ)), List.of());
    }

    private static int gridCoord(double coordinate) {
        return (int)Math.floor(coordinate / LAKE_GRID_SIZE);
    }

    private static long gridKey(int cellX, int cellZ) {
        return ((long)cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    private static long pointKey(int worldX, int worldZ) {
        return ((long)worldX << 32) ^ (worldZ & 0xffffffffL);
    }

    public static LakeSample sampleWorld(int worldX, int worldZ) {
        long key = pointKey(worldX, worldZ);
        SampleCache cache = SAMPLE_CACHE.get();
        LakeSample cached = cache.get(key);
        if (cached != null) return cached;

        LakeSample result = sampleWorldUncached(worldX, worldZ);
        cache.put(key, result);
        return result;
    }

    /**
     * LR-FINAL pair-local bank isolation. The LR-11 W09 shoreline refresh exposed two places
     * where the old authored-lobe terrain apron is much wider than the real land saddle between
     * neighbouring lakes. Keep the broad support in every other direction, but stop the apron
     * locally before it can blend across the other lake's shoreline. These are geometry-derived
     * isolation corridors, not lake-surface or topology overrides.
     */
    private static final List<BankIsolation> LR_FINAL_BANK_ISOLATIONS = List.of(
            new BankIsolation("AUTHORED:Kawaguchi", "AUTHORED:Sai", 177.805D, 84.0D, 84.0D),
            new BankIsolation("AUTHORED:Biwa", "W09:gc01_424", 161.563D, 120.0D, 32.0D)
    );

    private record BankIsolation(String lakeA, String lakeB, double shorelineGap,
                                 double bankLimitA, double bankLimitB) {
        boolean contains(String id) { return lakeA.equals(id) || lakeB.equals(id); }
        String other(String id) { return lakeA.equals(id) ? lakeB : lakeA; }
        double limit(String id) { return lakeA.equals(id) ? bankLimitA : bankLimitB; }
    }

    static double spatialAuditPairBankWidth(String lakeId, String otherLakeId, double configuredWidth) {
        for (BankIsolation isolation : LR_FINAL_BANK_ISOLATIONS) {
            if ((isolation.lakeA.equals(lakeId) && isolation.lakeB.equals(otherLakeId))
                    || (isolation.lakeB.equals(lakeId) && isolation.lakeA.equals(otherLakeId))) {
                return Math.min(configuredWidth, isolation.limit(lakeId));
            }
        }
        return configuredWidth;
    }

    private static boolean authoredBankInfluenceAllowed(Lake lake, int worldX, int worldZ,
                                                        double ownDistance) {
        String lakeId = "AUTHORED:" + lake.name;
        for (BankIsolation isolation : LR_FINAL_BANK_ISOLATIONS) {
            if (!isolation.contains(lakeId)) continue;
            String otherId = isolation.other(lakeId);
            SpatialDistance other = spatialAuditDistance(otherId, worldX, worldZ);
            if (other.inside()) return false;
            // Activate the directional stop only in the short saddle between these two shorelines.
            // Elsewhere the original DEM-derived authored bank support remains untouched.
            if (ownDistance + other.distanceToShore() <= isolation.shorelineGap + 24.0D
                    && ownDistance > isolation.limit(lakeId)) {
                return false;
            }
        }
        return true;
    }

    private static LakeSample sampleWorldUncached(int worldX, int worldZ) {
        LakeSample authoredShore = LakeSample.NONE;
        double bestOutsideDistance = Double.POSITIVE_INFINITY;

        // LR-11 keeps the 33 authored lakes authoritative for identity, water Y, depth and manual
        // topology, while their water footprint now comes from the unified refined W09 polygon
        // layer. The retained layer is still consulted only after authored water ownership.
        for (Lake lake : candidateLakes(worldX, worldZ)) {
            if (!lake.mayContain(worldX, worldZ)) continue;
            LakeDistance distance = lake.distance(worldX, worldZ);
            if (distance.inside()) {
                double depthBlend = smoothstep(0.0D, lake.shoreWidth, distance.distance());
                int depth = 1 + (int)Math.round((lake.maxDepthBlocks - 1)
                        * Math.pow(depthBlend, 0.72D));
                int bedY = lake.waterSurfaceY - Math.max(1, depth);
                return new LakeSample(true, true, lake.name, lake.waterSurfaceY, bedY,
                        depth, lake.maxDepthBlocks, 0.0D, lake.bankSlope,
                        distance.distance(), WaterbodyType.AUTHORED);
            }

            if (distance.distance() <= lake.bankWidth
                    && distance.distance() < bestOutsideDistance
                    && authoredBankInfluenceAllowed(lake, worldX, worldZ, distance.distance())) {
                bestOutsideDistance = distance.distance();
                double bankBlend = clamp(distance.distance() / lake.bankWidth, 0.0D, 1.0D);
                authoredShore = new LakeSample(true, false, lake.name, lake.waterSurfaceY,
                        lake.waterSurfaceY - 1, 0, lake.maxDepthBlocks,
                        bankBlend, lake.bankSlope, distance.distance(), WaterbodyType.AUTHORED);
            }
        }

        LakeSample retained = RetainedLakeData.sampleWorld(worldX, worldZ);
        if (retained.water()) return retained;
        if (!authoredShore.corridor()) return retained;
        if (!retained.corridor()) return authoredShore;
        return authoredShore.distanceToShore() <= retained.distanceToShore()
                ? authoredShore : retained;
    }

    public static boolean isLakeWaterWorld(int worldX, int worldZ) {
        LakeSample lake = sampleWorld(worldX, worldZ);
        return lake.rendersWaterOn(TerrainData.sampleWorld(worldX, worldZ));
    }

    public static int authoredLakeCount() { return LAKES.size(); }

    public static int retainedLakeCount() { return RetainedLakeData.lakeCount(); }

    /** RFIX-03 keeps the full 190-candidate W09 pool for topology/audit evidence. */
    public static int retainedCandidateLakeCount() { return RetainedLakeData.candidateLakeCount(); }

    /** Retained candidates intentionally excluded from active runtime ownership at PJ 1:8 scale. */
    public static int deferredRetainedLakeCount() { return RetainedLakeData.deferredLakeCount(); }

    public static int lakeCount() {
        return authoredLakeCount() + retainedLakeCount();
    }

    public static int lakeCandidateCount() {
        return authoredLakeCount() + retainedCandidateLakeCount();
    }

    /** Returns the configured water surface for a named lake. */
    public static int waterSurfaceY(String name) {
        for (Lake lake : LAKES) {
            if (lake.name.equals(name)) return lake.waterSurfaceY;
        }
        RetainedLakeData.RuntimeMetadata retained = RetainedLakeData.metadata(name);
        if (retained != null) return retained.waterSurfaceY();
        throw new IllegalArgumentException("Unknown Project Japan lake: " + name);
    }

    /** Stable lookup by LakeGraph runtime ID (AUTHORED:name or W09 identifier). */
    public static LakeMetadata metadataById(String lakeId) {
        if (lakeId == null || lakeId.isEmpty()) return null;
        if (lakeId.startsWith("AUTHORED:")) {
            String name = lakeId.substring("AUTHORED:".length());
            for (LakeMetadata lake : metadata()) {
                if (lake.lakeId().equals(lakeId) && lake.name().equals(name)) return lake;
            }
            return null;
        }
        RetainedLakeData.RuntimeMetadata retained = RetainedLakeData.metadataById(lakeId);
        if (retained == null) return null;
        return new LakeMetadata(retained.lakeId(), retained.name(), retained.waterSurfaceY(),
                retained.maximumDepthBlocks(), retained.targetAreaKm2(), 1.0D,
                RetentionStatus.RETAINED, false, retained.waterbodyType(), retained.surfaceSource(),
                retained.coastalConnection(), retained.inletRiverIds(), retained.outletRiverIds(),
                retained.sourceRiverIds(), retained.terminalRiverIds(), retained.vertexCount(),
                retained.interiorRingCount(), retained.representativeX(), retained.representativeZ());
    }

    /** Candidate-pool lookup used by RFIX audits; deferred retained lakes never become runtime owners. */
    public static LakeMetadata candidateMetadataById(String lakeId) {
        LakeMetadata active = metadataById(lakeId);
        if (active != null) return active;
        if (lakeId == null || lakeId.isEmpty() || lakeId.startsWith("AUTHORED:")) return null;
        RetainedLakeData.RuntimeMetadata retained = RetainedLakeData.candidateMetadataById(lakeId);
        if (retained == null) return null;
        return new LakeMetadata(retained.lakeId(), retained.name(), retained.waterSurfaceY(),
                retained.maximumDepthBlocks(), retained.targetAreaKm2(), 1.0D,
                RetentionStatus.DEFER, false, retained.waterbodyType(), retained.surfaceSource(),
                retained.coastalConnection(), retained.inletRiverIds(), retained.outletRiverIds(),
                retained.sourceRiverIds(), retained.terminalRiverIds(), retained.vertexCount(),
                retained.interiorRingCount(), retained.representativeX(), retained.representativeZ());
    }

    public static int waterSurfaceYById(String lakeId) {
        LakeMetadata lake = metadataById(lakeId);
        if (lake == null) throw new IllegalArgumentException("Unknown Project Japan lake id: " + lakeId);
        return lake.waterSurfaceY();
    }

    public static String nameById(String lakeId) {
        LakeMetadata lake = metadataById(lakeId);
        if (lake == null) throw new IllegalArgumentException("Unknown Project Japan lake id: " + lakeId);
        return lake.name();
    }

    /** Returns the configured maximum depth, in blocks, for a named lake. */
    public static int maximumDepthBlocks(String name) {
        for (Lake lake : LAKES) {
            if (lake.name.equals(name)) return lake.maxDepthBlocks;
        }
        RetainedLakeData.RuntimeMetadata retained = RetainedLakeData.metadata(name);
        if (retained != null) return retained.maximumDepthBlocks();
        throw new IllegalArgumentException("Unknown Project Japan lake: " + name);
    }

    /** Stable metadata used by offline validation and the F3/debug toolchain. */
    public static List<LakeMetadata> metadata() {
        List<LakeMetadata> result = new ArrayList<>(lakeCount());
        for (Lake lake : LAKES) {
            WaterbodyType type = lake.waterSurfaceY <= PJChunkGenerator.SEA_LEVEL + 1
                    ? WaterbodyType.LAGOON : WaterbodyType.LAKE;
            result.add(new LakeMetadata("AUTHORED:" + lake.name, lake.name, lake.waterSurfaceY,
                    lake.maxDepthBlocks, lake.targetAreaKm2, lake.shapeScale,
                    RetentionStatus.AUTHORED_LOCKED, true, type, SurfaceSource.AUTHORED,
                    false, List.of(), List.of(), List.of(), List.of(),
                    AuthoredLakeFootprintData.vertexCount(lake.name),
                    AuthoredLakeFootprintData.holeCount(lake.name),
                    AuthoredLakeFootprintData.representativeX(lake.name),
                    AuthoredLakeFootprintData.representativeZ(lake.name)));
        }
        for (RetainedLakeData.RuntimeMetadata lake : RetainedLakeData.metadata()) {
            result.add(new LakeMetadata(lake.lakeId(), lake.name(), lake.waterSurfaceY(),
                    lake.maximumDepthBlocks(), lake.targetAreaKm2(), 1.0D,
                    RetentionStatus.RETAINED, false, lake.waterbodyType(), lake.surfaceSource(),
                    lake.coastalConnection(), lake.inletRiverIds(), lake.outletRiverIds(),
                    lake.sourceRiverIds(), lake.terminalRiverIds(), lake.vertexCount(),
                    lake.interiorRingCount(), lake.representativeX(), lake.representativeZ()));
        }
        return List.copyOf(result);
    }

    /** Full 223-member candidate pool: 33 authored + 190 W09, including 18 RFIX deferred. */
    public static List<LakeMetadata> candidateMetadata() {
        List<LakeMetadata> result = new ArrayList<>(lakeCandidateCount());
        result.addAll(metadata().stream().filter(LakeMetadata::authoredOverride).toList());
        for (RetainedLakeData.RuntimeMetadata lake : RetainedLakeData.candidateMetadata()) {
            boolean active = RetainedLakeData.isActiveLakeId(lake.lakeId());
            result.add(new LakeMetadata(lake.lakeId(), lake.name(), lake.waterSurfaceY(),
                    lake.maximumDepthBlocks(), lake.targetAreaKm2(), 1.0D,
                    active ? RetentionStatus.RETAINED : RetentionStatus.DEFER,
                    false, lake.waterbodyType(), lake.surfaceSource(), lake.coastalConnection(),
                    lake.inletRiverIds(), lake.outletRiverIds(), lake.sourceRiverIds(),
                    lake.terminalRiverIds(), lake.vertexCount(), lake.interiorRingCount(),
                    lake.representativeX(), lake.representativeZ()));
        }
        return List.copyOf(result);
    }

    /** Package-private numerical footprint check used by the offline geography audit. */
    static double estimatedWaterAreaKm2(String name, int spacingBlocks) {
        if (spacingBlocks < 1) throw new IllegalArgumentException("spacingBlocks must be positive");
        for (Lake lake : LAKES) {
            if (lake.name.equals(name)) return lake.estimatedWaterAreaKm2(spacingBlocks);
        }
        return RetainedLakeData.estimatedWaterAreaKm2(name);
    }

    /** RFIX-01 candidate-pool geometry snapshot using the actual runtime bank widths. */
    static List<LakeSpatialCandidate> spatialAuditCandidates() {
        List<LakeSpatialCandidate> result = new ArrayList<>(lakeCandidateCount());
        for (Lake lake : LAKES) result.add(lake.spatialAuditCandidate());
        result.addAll(RetainedLakeData.spatialAuditCandidates());
        return List.copyOf(result);
    }

    static SpatialDistance spatialAuditDistance(String lakeId, int x, int z) {
        if (lakeId.startsWith("AUTHORED:")) {
            String name = lakeId.substring("AUTHORED:".length());
            for (Lake lake : LAKES) {
                if (!lake.name.equals(name)) continue;
                LakeDistance distance = lake.distance(x, z);
                return new SpatialDistance(distance.inside(), distance.distance());
            }
            return SpatialDistance.NONE;
        }
        return RetainedLakeData.spatialAuditDistance(lakeId, x, z);
    }

    static record AuditPoint(int x, int z) {}
    static record SpatialDistance(boolean inside, double distanceToShore) {
        static final SpatialDistance NONE = new SpatialDistance(false, Double.POSITIVE_INFINITY);
    }
    static record LakeSpatialCandidate(String lakeId, String name, boolean authored,
                                       boolean active, int waterSurfaceY, double bankWidth,
                                       double areaKm2, int topologyImportance,
                                       int minX, int maxX, int minZ, int maxZ,
                                       List<AuditPoint> boundarySamples) {}

    public enum RetentionStatus { AUTHORED_LOCKED, RETAINED, DEFER }

    public enum WaterbodyType {
        AUTHORED, LAKE, LAGOON, RESERVOIR, COASTAL_WATERBODY, CHANNEL_CONNECTOR, DEFER
    }

    public enum SurfaceSource { AUTHORED, W09, DEM_RESOLVED }

    public record LakeMetadata(String lakeId, String name, int waterSurfaceY,
                               int maximumDepthBlocks, double targetAreaKm2, double shapeScale,
                               RetentionStatus retentionStatus, boolean authoredOverride,
                               WaterbodyType waterbodyType, SurfaceSource surfaceSource,
                               boolean coastalConnection,
                               List<String> inletRiverIds, List<String> outletRiverIds,
                               List<String> sourceRiverIds, List<String> terminalRiverIds,
                               int simplifiedVertexCount, int interiorRingCount,
                               int representativeX, int representativeZ) {}

    private static Lake lake(String name, double surfaceElevationMetres,
                             double maximumDepthMetres, double targetAreaKm2,
                             double shapeScale, LobeDefinition... definitions) {
        return new Lake(name, surfaceElevationMetres, maximumDepthMetres, targetAreaKm2,
                shapeScale, List.of(definitions));
    }

    private static LobeDefinition lobe(double longitude, double latitude,
                                       double radiusEastWestKm, double radiusNorthSouthKm,
                                       double rotationDegrees) {
        return new LobeDefinition(longitude, latitude, radiusEastWestKm,
                radiusNorthSouthKm, rotationDegrees);
    }

    public record LakeSample(boolean corridor, boolean water, String name,
                             int waterSurfaceY, int bedY, int depth,
                             int maximumDepth, double bankBlend,
                             double bankSlope, double distanceToShore,
                             WaterbodyType waterbodyType) {
        public static final LakeSample NONE = new LakeSample(false, false, "",
                PJChunkGenerator.SEA_LEVEL, PJChunkGenerator.SEA_LEVEL - 1,
                0, 0, 1.0D, 0.20D, Double.POSITIVE_INFINITY, WaterbodyType.AUTHORED);

        public boolean suppressesVegetation() {
            return water || (corridor && distanceToShore <= 48.0D);
        }

        /**
         * Sea-level lagoons need a narrow physical spit where the coarse land mask cuts through
         * their shore. Named inlet/outlet rivers are allowed to open this barrier in the chunk
         * generator, but an arbitrary overlap with an ocean pixel is not.
         */
        public boolean rendersWaterOn(TerrainData.TerrainSample terrain) {
            if (!water) return false;
            if (waterbodyType == WaterbodyType.COASTAL_WATERBODY) {
                // Open/semi-open coastal water is already represented by the ocean. The W09
                // polygon only carves coastal land/fringe pixels; it must never seal an open bay.
                return terrain.land() || terrain.landCoverage() >= 0.42D;
            }
            if (waterbodyType == WaterbodyType.LAGOON) {
                return terrain.land() || terrain.landCoverage() >= 0.28D;
            }
            if (waterbodyType != WaterbodyType.AUTHORED
                    || waterSurfaceY > PJChunkGenerator.SEA_LEVEL + 1) return true;
            // Preserve the long-tested authored sea-level lobe behaviour exactly.
            return terrain.land() || terrain.landCoverage() >= 0.32D;
        }

        public boolean coastalBarrier(TerrainData.TerrainSample terrain) {
            if (waterbodyType == WaterbodyType.COASTAL_WATERBODY) return false;
            double coverageFloor = waterbodyType == WaterbodyType.LAGOON ? 0.18D : 0.22D;
            return corridor && !water && !terrain.land()
                    && waterSurfaceY <= PJChunkGenerator.SEA_LEVEL + 1
                    && terrain.landCoverage() >= coverageFloor
                    && distanceToShore <= 6.0D;
        }
    }

    private record LobeDefinition(double longitude, double latitude,
                                  double radiusEastWestKm, double radiusNorthSouthKm,
                                  double rotationDegrees) {}

    private record LakeDistance(boolean inside, double distance) {}

    private static final class Lake {
        private final String name;
        private final int waterSurfaceY;
        private final int maxDepthBlocks;
        private final double targetAreaKm2;
        private final double shapeScale;
        private final double shoreWidth;
        private final double bankWidth;
        private final double bankSlope;
        private final List<Lobe> lobes;
        private final List<Bridge> bridges;
        private final long shorelineSeed;
        private final double shorelineScale;
        private final double shorelineAmplitude;
        private final double lobeBlendWidth;
        private final double minX;
        private final double maxX;
        private final double minZ;
        private final double maxZ;

        private Lake(String name, double surfaceElevationMetres, double maximumDepthMetres,
                     double targetAreaKm2, double shapeScale,
                     List<LobeDefinition> definitions) {
            this.name = name;
            this.targetAreaKm2 = targetAreaKm2;
            this.shapeScale = shapeScale;
            this.maxDepthBlocks = Math.max(1, (int)Math.round(
                    maximumDepthMetres / TerrainData.VERTICAL_METRES_PER_BLOCK));

            List<Lobe> converted = new ArrayList<>();
            double smallestRadius = Double.POSITIVE_INFINITY;
            for (LobeDefinition definition : definitions) {
                double radiusX = Math.max(6.0D,
                        definition.radiusEastWestKm() * shapeScale * BLOCKS_PER_KILOMETRE);
                double radiusZ = Math.max(6.0D,
                        definition.radiusNorthSouthKm() * shapeScale * BLOCKS_PER_KILOMETRE);
                Lobe lobe = new Lobe(
                        TerrainData.worldXFromLongitude(definition.longitude()),
                        TerrainData.worldZFromLatitude(definition.latitude()),
                        radiusX, radiusZ, Math.toRadians(definition.rotationDegrees()));
                converted.add(lobe);
                smallestRadius = Math.min(smallestRadius, Math.min(radiusX, radiusZ));
            }
            this.lobes = List.copyOf(converted);

            // Reality supplies the lake's nominal absolute elevation, but the active PJ DEM is
            // the vertical authority for generated terrain. A few high-altitude control lakes
            // were inherited from the older, coarser DEM and can sit tens of real metres above
            // the 0.6.x basin. In that case shoreline blending merely builds a broad artificial
            // pedestal and any outlet river appears to start in mid-air. Fit only clear positive
            // mismatches back to the DEM basin; correctly aligned lakes keep their recorded level.
            int configuredSurfaceY = PJChunkGenerator.SEA_LEVEL + (int)Math.round(
                    surfaceElevationMetres / TerrainData.VERTICAL_METRES_PER_BLOCK);
            this.waterSurfaceY = fitWaterSurfaceToDem(configuredSurfaceY, converted);
            double effectiveSurfaceElevationMetres = Math.max(0.0D,
                    (waterSurfaceY - PJChunkGenerator.SEA_LEVEL)
                            * TerrainData.VERTICAL_METRES_PER_BLOCK);

            this.bridges = buildBridges(converted);
            this.shorelineSeed = mix64(name.hashCode() * 0x9e3779b97f4a7c15L);
            this.shorelineScale = clamp(smallestRadius * 0.48D, 64.0D, 420.0D);
            this.shorelineAmplitude = clamp(smallestRadius * 0.20D, 7.0D, 160.0D);
            this.lobeBlendWidth = clamp(smallestRadius * 0.25D, 18.0D, 96.0D);
            this.shoreWidth = clamp(smallestRadius * 0.18D, 12.0D, 96.0D);

            // The lake polygon owns its water plus a bounded shoreline-repair corridor. Earlier
            // versions either clipped that corridor too early (leaving a wall) or spread a fixed
            // basin envelope too far (lowering valid mountains). The width below is derived from
            // nearby terrain and capped for lowland lakes, so the shore meets natural land before
            // the lake stops modifying columns.
            this.bankSlope = effectiveSurfaceElevationMetres > 120.0D ? 0.90D : 0.35D;
            double compactOutline = clamp(smallestRadius * 0.08D, 32.0D, 128.0D);
            // The 8192x6656 GSI-derived DEM usually resolves a lake rim directly, so there is no
            // reason to assign every elevated lake the broad fixed apron used by the compact
            // 0.5.x terrain. Measure both low and high terrain around the expanded shoreline and
            // grow only as far as needed to meet actual nearby land. This keeps containment local
            // while still bridging an occasional undersampled outlet or steep lake edge.
            double lowTerrainSupport = requiredLowTerrainSupport(effectiveSurfaceElevationMetres,
                    converted, bankSlope, compactOutline);
            double highTerrainSupport = requiredHighTerrainSupport(effectiveSurfaceElevationMetres,
                    converted, bankSlope, compactOutline);
            double maximumBankWidth = effectiveSurfaceElevationMetres <= 120.0D ? 384.0D : 768.0D;
            highTerrainSupport = Math.min(highTerrainSupport, maximumBankWidth);
            lowTerrainSupport = Math.min(lowTerrainSupport, maximumBankWidth);
            this.bankWidth = clamp(Math.max(compactOutline,
                    Math.max(lowTerrainSupport, highTerrainSupport)), 32.0D, maximumBankWidth);

            // signedDistance() measures distance in the ellipse's normalized space, scaled by
            // its smaller radius. Therefore an elongated ellipse's bank corridor extends farther
            // than simply `largestRadius + bankWidth`. The old bounding box clipped that corridor
            // early, sometimes while bankBlend was only about 0.7, producing the abrupt basin
            // walls seen beside Towada and Suwa. Compute the exact rotated expanded-ellipse bounds.
            double localMinX = Double.POSITIVE_INFINITY;
            double localMaxX = Double.NEGATIVE_INFINITY;
            double localMinZ = Double.POSITIVE_INFINITY;
            double localMaxZ = Double.NEGATIVE_INFINITY;
            for (Lobe lobe : converted) {
                double extentX = lobe.expandedExtentX(bankWidth + shorelineAmplitude);
                double extentZ = lobe.expandedExtentZ(bankWidth + shorelineAmplitude);
                localMinX = Math.min(localMinX, lobe.centerX - extentX);
                localMaxX = Math.max(localMaxX, lobe.centerX + extentX);
                localMinZ = Math.min(localMinZ, lobe.centerZ - extentZ);
                localMaxZ = Math.max(localMaxZ, lobe.centerZ + extentZ);
            }
            if (AuthoredLakeFootprintData.has(name)) {
                this.minX = AuthoredLakeFootprintData.minX(name) - bankWidth;
                this.maxX = AuthoredLakeFootprintData.maxX(name) + bankWidth;
                this.minZ = AuthoredLakeFootprintData.minZ(name) - bankWidth;
                this.maxZ = AuthoredLakeFootprintData.maxZ(name) + bankWidth;
            } else {
                this.minX = localMinX;
                this.maxX = localMaxX;
                this.minZ = localMinZ;
                this.maxZ = localMaxZ;
            }
        }

        /**
         * Clamp only obviously floating high-altitude lakes to the local 0.6.x DEM basin.
         * We intentionally use robust percentiles rather than one centre pixel: shoreline lobes
         * may contain steep banks and the GSI raster can have a few locally noisy cells. If the
         * configured plane is no more than four blocks above the basin's 90th percentile it is
         * considered aligned and remains untouched. Otherwise the lower-quartile basin height is
         * used, which keeps the lake contained without manufacturing a raised platform.
         */
        private static int fitWaterSurfaceToDem(int configuredSurfaceY, List<Lobe> lobes) {
            if (configuredSurfaceY <= PJChunkGenerator.SEA_LEVEL + 2 || lobes.isEmpty()) {
                return configuredSurfaceY;
            }

            List<Integer> basinHeights = new ArrayList<>();
            final double[] radii = {0.18D, 0.36D, 0.54D, 0.72D, 0.86D};
            for (Lobe lobe : lobes) {
                for (double radius : radii) {
                    for (int angleIndex = 0; angleIndex < 48; angleIndex++) {
                        double angle = Math.PI * 2.0D * angleIndex / 48.0D;
                        int sampleX = (int)Math.round(lobe.pointX(radius, angle));
                        int sampleZ = (int)Math.round(lobe.pointZ(radius, angle));
                        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(sampleX, sampleZ);
                        if (!terrain.land()) continue;
                        double coastRise = smoothstep(0.50D, 0.84D, terrain.landCoverage());
                        int elevationBlocks = Math.max(1, (int)Math.round(
                                terrain.elevationMetres()
                                        / TerrainData.VERTICAL_METRES_PER_BLOCK * coastRise));
                        basinHeights.add(PJChunkGenerator.SEA_LEVEL + elevationBlocks);
                    }
                }
            }
            if (basinHeights.size() < 24) return configuredSurfaceY;

            basinHeights.sort(Integer::compareTo);
            int lowerQuartile = basinHeights.get(basinHeights.size() / 4);
            int upperDecile = basinHeights.get((basinHeights.size() * 9) / 10);
            if (configuredSurfaceY <= upperDecile + 4) return configuredSurfaceY;

            return Math.min(configuredSurfaceY, lowerQuartile);
        }

        private static List<Bridge> buildBridges(List<Lobe> lobes) {
            if (lobes.size() < 2) return List.of();

            // Each new control lobe joins the nearest already-connected lobe. This is a compact
            // minimum-tree approximation, not a second shape primitive: most of each capsule lies
            // inside the lobes and only the otherwise disconnected neck remains visible.
            List<Bridge> result = new ArrayList<>();
            for (int index = 1; index < lobes.size(); index++) {
                Lobe current = lobes.get(index);
                Lobe nearest = lobes.get(0);
                double nearestDistance = Double.POSITIVE_INFINITY;
                for (int candidateIndex = 0; candidateIndex < index; candidateIndex++) {
                    Lobe candidate = lobes.get(candidateIndex);
                    double distance = Math.hypot(current.centerX - candidate.centerX,
                            current.centerZ - candidate.centerZ);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = candidate;
                    }
                }
                double halfWidth = clamp(Math.min(current.minimumRadius(),
                        nearest.minimumRadius()) * 0.45D, 18.0D, 160.0D);
                result.add(new Bridge(current.centerX, current.centerZ,
                        nearest.centerX, nearest.centerZ, halfWidth));
            }
            return List.copyOf(result);
        }

        private static double requiredLowTerrainSupport(double waterElevationMetres,
                                                        List<Lobe> lobes, double slope,
                                                        double outlineSupport) {
            double width = outlineSupport;
            for (int iteration = 0; iteration < 7; iteration++) {
                double boundaryMinimumMetres = waterElevationMetres;
                boolean sampledLand = false;
                for (Lobe lobe : lobes) {
                    double scale = 1.0D + width / Math.min(lobe.radiusX, lobe.radiusZ);
                    for (int angleIndex = 0; angleIndex < 128; angleIndex++) {
                        double angle = Math.PI * 2.0D * angleIndex / 128.0D;
                        int sampleX = (int)Math.round(lobe.pointX(scale, angle));
                        int sampleZ = (int)Math.round(lobe.pointZ(scale, angle));
                        boolean insideAnotherLobe = false;
                        for (Lobe other : lobes) {
                            if (other != lobe && other.signedDistance(sampleX, sampleZ) >= 0.0D) {
                                insideAnotherLobe = true;
                                break;
                            }
                        }
                        if (insideAnotherLobe) continue;
                        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(sampleX, sampleZ);
                        if (!terrain.land()) continue;
                        sampledLand = true;
                        boundaryMinimumMetres = Math.min(boundaryMinimumMetres,
                                terrain.elevationMetres());
                    }
                }
                if (!sampledLand) return width;
                double deficitBlocks = Math.max(0.0D,
                        (waterElevationMetres - boundaryMinimumMetres)
                                / TerrainData.VERTICAL_METRES_PER_BLOCK);
                double required = Math.max(outlineSupport,
                        32.0D + deficitBlocks / Math.max(0.05D, slope));
                if (required <= width + 8.0D) return width;
                width = Math.min(1536.0D, required);
            }
            return width;
        }

        private static double requiredHighTerrainSupport(double waterElevationMetres,
                                                         List<Lobe> lobes, double slope,
                                                         double outlineSupport) {
            double width = outlineSupport;
            for (int iteration = 0; iteration < 7; iteration++) {
                double boundaryMaximumMetres = waterElevationMetres;
                for (Lobe lobe : lobes) {
                    double scale = 1.0D + width / Math.min(lobe.radiusX, lobe.radiusZ);
                    for (int angleIndex = 0; angleIndex < 128; angleIndex++) {
                        double angle = Math.PI * 2.0D * angleIndex / 128.0D;
                        int sampleX = (int)Math.round(lobe.pointX(scale, angle));
                        int sampleZ = (int)Math.round(lobe.pointZ(scale, angle));
                        boolean insideAnotherLobe = false;
                        for (Lobe other : lobes) {
                            if (other != lobe && other.signedDistance(sampleX, sampleZ) >= 0.0D) {
                                insideAnotherLobe = true;
                                break;
                            }
                        }
                        if (insideAnotherLobe) continue;
                        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(sampleX, sampleZ);
                        if (terrain.land()) {
                            boundaryMaximumMetres = Math.max(boundaryMaximumMetres,
                                    terrain.elevationMetres());
                        }
                    }
                }
                double riseBlocks = Math.max(0.0D,
                        (boundaryMaximumMetres - waterElevationMetres)
                                / TerrainData.VERTICAL_METRES_PER_BLOCK);
                double required = Math.max(outlineSupport,
                        48.0D + riseBlocks / Math.max(0.05D, slope));
                if (required <= width + 8.0D) return width;
                width = Math.min(1536.0D, required);
            }
            return width;
        }

        private LakeSpatialCandidate spatialAuditCandidate() {
            List<AuditPoint> boundary = auditBoundarySamples();
            int waterMinX = boundary.stream().mapToInt(AuditPoint::x).min().orElse((int)Math.floor(minX));
            int waterMaxX = boundary.stream().mapToInt(AuditPoint::x).max().orElse((int)Math.ceil(maxX));
            int waterMinZ = boundary.stream().mapToInt(AuditPoint::z).min().orElse((int)Math.floor(minZ));
            int waterMaxZ = boundary.stream().mapToInt(AuditPoint::z).max().orElse((int)Math.ceil(maxZ));
            return new LakeSpatialCandidate("AUTHORED:" + name, name, true, true, waterSurfaceY,
                    bankWidth, targetAreaKm2, 1, waterMinX, waterMaxX, waterMinZ, waterMaxZ, boundary);
        }

        private List<AuditPoint> auditBoundarySamples() {
            if (AuthoredLakeFootprintData.has(name)) return AuthoredLakeFootprintData.boundarySamples(name);
            List<AuditPoint> result = new ArrayList<>();
            for (Lobe lobe : lobes) {
                for (int angleIndex = 0; angleIndex < 128; angleIndex++) {
                    double angle = Math.PI * 2.0D * angleIndex / 128.0D;
                    double cx = lobe.centerX, cz = lobe.centerZ;
                    double px = lobe.pointX(1.0D, angle), pz = lobe.pointZ(1.0D, angle);
                    double dx = px - cx, dz = pz - cz;
                    double high = 1.5D;
                    int hx = (int)Math.round(cx + dx * high);
                    int hz = (int)Math.round(cz + dz * high);
                    while (distance(hx, hz).inside() && high < 8.0D) {
                        high *= 1.5D;
                        hx = (int)Math.round(cx + dx * high);
                        hz = (int)Math.round(cz + dz * high);
                    }
                    double low = 0.0D;
                    for (int iteration = 0; iteration < 14; iteration++) {
                        double mid = (low + high) * 0.5D;
                        int mx = (int)Math.round(cx + dx * mid);
                        int mz = (int)Math.round(cz + dz * mid);
                        if (distance(mx, mz).inside()) low = mid; else high = mid;
                    }
                    result.add(new AuditPoint((int)Math.round(cx + dx * low),
                            (int)Math.round(cz + dz * low)));
                }
            }
            return List.copyOf(result);
        }

        private boolean mayContain(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        private LakeDistance distance(int x, int z) {
            if (AuthoredLakeFootprintData.has(name)) {
                SpatialDistance refined = AuthoredLakeFootprintData.distance(name, x, z);
                return new LakeDistance(refined.inside(), refined.distanceToShore());
            }
            double signedDistance = lobes.get(0).signedDistance(x, z);
            for (int index = 1; index < lobes.size(); index++) {
                signedDistance = smoothMaximum(signedDistance,
                        lobes.get(index).signedDistance(x, z), lobeBlendWidth);
            }
            for (Bridge bridge : bridges) {
                signedDistance = smoothMaximum(signedDistance,
                        bridge.signedDistance(x, z), lobeBlendWidth * 0.60D);
            }

            // Only the actual shoreline needs the two noise lookups. Lake support corridors can
            // extend kilometres into coarse high terrain, and evaluating shoreline detail there
            // was measurable during chunk generation. Fade the displacement to zero outside two
            // amplitudes; the signed field remains continuous while distant columns take a cheap
            // lobe/bridge-only path.
            double noiseInfluence = 1.0D - smoothstep(shorelineAmplitude,
                    shorelineAmplitude * 2.0D, Math.abs(signedDistance));
            if (noiseInfluence > 0.0D) {
                double shorelineOffset = shorelineAmplitude * (0.72D
                        * valueNoise(x, z, shorelineScale, shorelineSeed)
                        + 0.28D * valueNoise(x, z, shorelineScale * 0.43D,
                        shorelineSeed ^ 0xd1b54a32d192ed03L));
                signedDistance += shorelineOffset * noiseInfluence;
            }
            return new LakeDistance(signedDistance >= 0.0D, Math.abs(signedDistance));
        }

        private double estimatedWaterAreaKm2(int spacingBlocks) {
            long waterSamples = 0L;
            int startX = (int)Math.floor(minX / spacingBlocks) * spacingBlocks;
            int endX = (int)Math.ceil(maxX / spacingBlocks) * spacingBlocks;
            int startZ = (int)Math.floor(minZ / spacingBlocks) * spacingBlocks;
            int endZ = (int)Math.ceil(maxZ / spacingBlocks) * spacingBlocks;
            for (int z = startZ; z <= endZ; z += spacingBlocks) {
                for (int x = startX; x <= endX; x += spacingBlocks) {
                    if (distance(x, z).inside()) waterSamples++;
                }
            }
            double squareMetresPerSample = spacingBlocks * spacingBlocks
                    * TerrainData.HORIZONTAL_METRES_PER_BLOCK
                    * TerrainData.HORIZONTAL_METRES_PER_BLOCK;
            return waterSamples * squareMetresPerSample / 1_000_000.0D;
        }
    }

    private record Bridge(double ax, double az, double bx, double bz, double halfWidth) {
        private double signedDistance(double x, double z) {
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared <= 1.0D ? 0.0D
                    : clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared, 0.0D, 1.0D);
            double nearestX = ax + dx * t;
            double nearestZ = az + dz * t;
            return halfWidth - Math.hypot(x - nearestX, z - nearestZ);
        }
    }

    private static final class Lobe {
        private final double centerX;
        private final double centerZ;
        private final double radiusX;
        private final double radiusZ;
        private final double cos;
        private final double sin;

        private Lobe(double centerX, double centerZ, double radiusX, double radiusZ,
                     double rotation) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radiusX = radiusX;
            this.radiusZ = radiusZ;
            this.cos = Math.cos(rotation);
            this.sin = Math.sin(rotation);
        }


        private double pointX(double scale, double angle) {
            double localX = radiusX * scale * Math.cos(angle);
            double localZ = radiusZ * scale * Math.sin(angle);
            return centerX + localX * cos - localZ * sin;
        }

        private double pointZ(double scale, double angle) {
            double localX = radiusX * scale * Math.cos(angle);
            double localZ = radiusZ * scale * Math.sin(angle);
            return centerZ + localX * sin + localZ * cos;
        }

        private double expandedExtentX(double distance) {
            double scale = 1.0D + distance / Math.min(radiusX, radiusZ);
            double expandedX = radiusX * scale;
            double expandedZ = radiusZ * scale;
            return Math.sqrt(expandedX * expandedX * cos * cos
                    + expandedZ * expandedZ * sin * sin);
        }

        private double expandedExtentZ(double distance) {
            double scale = 1.0D + distance / Math.min(radiusX, radiusZ);
            double expandedX = radiusX * scale;
            double expandedZ = radiusZ * scale;
            return Math.sqrt(expandedX * expandedX * sin * sin
                    + expandedZ * expandedZ * cos * cos);
        }

        private double minimumRadius() {
            return Math.min(radiusX, radiusZ);
        }

        /** Positive inside, negative outside, approximately measured in blocks. */
        private double signedDistance(double x, double z) {
            double dx = x - centerX;
            double dz = z - centerZ;
            double localX = dx * cos + dz * sin;
            double localZ = -dx * sin + dz * cos;
            double normalized = Math.sqrt((localX * localX) / (radiusX * radiusX)
                    + (localZ * localZ) / (radiusZ * radiusZ));
            return (1.0D - normalized) * Math.min(radiusX, radiusZ);
        }
    }

    private static double valueNoise(double x, double z, double scale, long seed) {
        double scaledX = x / scale;
        double scaledZ = z / scale;
        long cellX = (long)Math.floor(scaledX);
        long cellZ = (long)Math.floor(scaledZ);
        double tx = smoothCurve(scaledX - cellX);
        double tz = smoothCurve(scaledZ - cellZ);
        double north = lerp(hashNoise(cellX, cellZ, seed),
                hashNoise(cellX + 1, cellZ, seed), tx);
        double south = lerp(hashNoise(cellX, cellZ + 1, seed),
                hashNoise(cellX + 1, cellZ + 1, seed), tx);
        return lerp(north, south, tz);
    }

    private static double hashNoise(long x, long z, long seed) {
        long mixed = mix64(seed ^ x * 0x632be59bd9b4e019L ^ z * 0x9e3779b97f4a7c15L);
        return ((mixed >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static double smoothCurve(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double smoothMaximum(double a, double b, double blendWidth) {
        double amount = clamp(0.5D + 0.5D * (a - b) / blendWidth, 0.0D, 1.0D);
        return lerp(b, a, amount) + blendWidth * amount * (1.0D - amount);
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static final class SampleCache {
        private final long[] keys;
        private final LakeSample[] values;
        private final boolean[] valid;
        private final int mask;

        private SampleCache(int size) {
            if (Integer.bitCount(size) != 1) {
                throw new IllegalArgumentException("Sample cache size must be a power of two");
            }
            this.keys = new long[size];
            this.values = new LakeSample[size];
            this.valid = new boolean[size];
            this.mask = size - 1;
        }

        private int slot(long key) {
            long mixed = key ^ (key >>> 33);
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            return ((int)mixed) & mask;
        }

        private LakeSample get(long key) {
            int slot = slot(key);
            return valid[slot] && keys[slot] == key ? values[slot] : null;
        }

        private void put(long key, LakeSample value) {
            int slot = slot(key);
            keys[slot] = key;
            values[slot] = value;
            valid[slot] = true;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / Math.max(0.0001D, edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }
}
