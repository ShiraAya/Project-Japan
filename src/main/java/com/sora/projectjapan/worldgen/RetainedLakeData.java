package com.sora.projectjapan.worldgen;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;

/**
 * L-stage candidate geometry plus RFIX-03 active-set freeze for reviewed W09 retained lakes.
 *
 * <p>The compact derivative intentionally keeps all 190 reviewed candidates so later topology
 * audits can still inspect deferred official evidence. RFIX-03 supplies a separate deterministic
 * active-ID resource; only those 172 retained candidates enter the runtime spatial index/water
 * owner set. Authored lakes remain in LakeData as the 33 authoritative hand-tuned overrides.</p>
 */
final class RetainedLakeData {
    private static final String RESOURCE =
            "/assets/projectjapan/hydrology/generated/retained_lakes.bin.gz";
    private static final String ACTIVE_IDS_RESOURCE =
            "/assets/projectjapan/hydrology/generated/active_retained_lake_ids.txt";
    private static final int MAGIC = 0x504A4C4B; // PJLK
    private static final int VERSION = 1;
    private static final int GRID_SIZE = 2048;
    private static final List<PolygonLake> CANDIDATE_LAKES = load();
    private static final Set<String> ACTIVE_IDS = loadActiveIds();
    private static final List<PolygonLake> LAKES = CANDIDATE_LAKES.stream()
            .filter(lake -> ACTIVE_IDS.contains(lake.id)).toList();
    private static final Map<Long, List<PolygonLake>> GRID = buildGrid(LAKES);
    private static final Map<String, PolygonLake> BY_NAME = buildByName(LAKES);
    private static final Map<String, PolygonLake> BY_ID = buildById(LAKES);
    private static final Map<String, PolygonLake> CANDIDATE_BY_ID = buildById(CANDIDATE_LAKES);

    private RetainedLakeData() {}

    static int lakeCount() { return LAKES.size(); }
    static int candidateLakeCount() { return CANDIDATE_LAKES.size(); }
    static int deferredLakeCount() { return CANDIDATE_LAKES.size() - LAKES.size(); }
    static boolean isActiveLakeId(String lakeId) { return ACTIVE_IDS.contains(lakeId); }

    static List<RuntimeMetadata> metadata() {
        return LAKES.stream().map(PolygonLake::metadata).toList();
    }

    static List<RuntimeMetadata> candidateMetadata() {
        return CANDIDATE_LAKES.stream().map(PolygonLake::metadata).toList();
    }

    static RuntimeMetadata metadata(String name) {
        PolygonLake lake = BY_NAME.get(name);
        return lake == null ? null : lake.metadata();
    }

    static RuntimeMetadata metadataById(String lakeId) {
        PolygonLake lake = BY_ID.get(lakeId);
        return lake == null ? null : lake.metadata();
    }

    static RuntimeMetadata candidateMetadataById(String lakeId) {
        PolygonLake lake = CANDIDATE_BY_ID.get(lakeId);
        return lake == null ? null : lake.metadata();
    }

    static double estimatedWaterAreaKm2(String name) {
        PolygonLake lake = BY_NAME.get(name);
        if (lake == null) throw new IllegalArgumentException("Unknown retained Project Japan lake: " + name);
        return lake.areaKm2;
    }

    static LakeData.LakeSample sampleLake(String lakeId, int worldX, int worldZ) {
        PolygonLake lake = BY_ID.get(lakeId);
        return lake == null ? LakeData.LakeSample.NONE : lake.sample(worldX, worldZ);
    }

    static LakeData.LakeSample sampleCandidateLake(String lakeId, int worldX, int worldZ) {
        PolygonLake lake = CANDIDATE_BY_ID.get(lakeId);
        return lake == null ? LakeData.LakeSample.NONE : lake.sample(worldX, worldZ);
    }

    /** LR-04 validation hook: exact polygon coverage of one Minecraft block column. */
    static double rasterAuditCoverage(String lakeId, int blockX, int blockZ) {
        PolygonLake lake = BY_ID.get(lakeId);
        return lake == null ? 0.0D : lake.blockCoverage(blockX, blockZ);
    }

    /** LR-04 validation hook for the tie-break centre sample. */
    static boolean rasterAuditCentreInside(String lakeId, int blockX, int blockZ) {
        PolygonLake lake = BY_ID.get(lakeId);
        return lake != null && lake.waterContains(blockX + 0.5D, blockZ + 0.5D);
    }

    static List<LakeData.LakeSpatialCandidate> spatialAuditCandidates() {
        return CANDIDATE_LAKES.stream().map(lake -> {
            List<LakeData.AuditPoint> boundary = lake.auditBoundarySamples();
            int minX = boundary.stream().mapToInt(LakeData.AuditPoint::x).min().orElse(lake.minX);
            int maxX = boundary.stream().mapToInt(LakeData.AuditPoint::x).max().orElse(lake.maxX);
            int minZ = boundary.stream().mapToInt(LakeData.AuditPoint::z).min().orElse(lake.minZ);
            int maxZ = boundary.stream().mapToInt(LakeData.AuditPoint::z).max().orElse(lake.maxZ);
            int topologyImportance = lake.inletRiverIds.size() + lake.outletRiverIds.size();
            return new LakeData.LakeSpatialCandidate(lake.id, lake.name, false,
                    ACTIVE_IDS.contains(lake.id), lake.waterSurfaceY, lake.bankWidth, lake.areaKm2,
                    topologyImportance, minX, maxX, minZ, maxZ, boundary);
        }).toList();
    }

    static LakeData.SpatialDistance spatialAuditDistance(String lakeId, int x, int z) {
        PolygonLake lake = CANDIDATE_BY_ID.get(lakeId);
        if (lake == null) return LakeData.SpatialDistance.NONE;
        PolygonDistance distance = lake.distance(x, z);
        return new LakeData.SpatialDistance(distance.inside, distance.distance);
    }

    static LakeData.LakeSample sampleWorld(int worldX, int worldZ) {
        LakeData.LakeSample nearest = LakeData.LakeSample.NONE;
        double bestOutside = Double.POSITIVE_INFINITY;
        for (PolygonLake lake : candidates(worldX, worldZ)) {
            if (!lake.mayContain(worldX, worldZ)) continue;
            PolygonDistance distance = lake.distance(worldX, worldZ);
            if (distance.inside) {
                double depthBlend = smoothstep(0.0D, lake.shoreWidth, distance.distance);
                int depth = 1 + (int)Math.round((lake.maximumDepthBlocks - 1)
                        * Math.pow(depthBlend, 0.72D));
                int bedY = lake.waterSurfaceY - Math.max(1, depth);
                return new LakeData.LakeSample(true, true, lake.name, lake.waterSurfaceY, bedY,
                        depth, lake.maximumDepthBlocks, 0.0D, lake.bankSlope,
                        distance.distance, lake.type);
            }
            if (distance.distance <= lake.bankWidth && distance.distance < bestOutside) {
                bestOutside = distance.distance;
                double blend = clamp(distance.distance / lake.bankWidth, 0.0D, 1.0D);
                nearest = new LakeData.LakeSample(true, false, lake.name, lake.waterSurfaceY,
                        lake.waterSurfaceY - 1, 0, lake.maximumDepthBlocks,
                        blend, lake.bankSlope, distance.distance, lake.type);
            }
        }
        return nearest;
    }

    private static List<PolygonLake> candidates(int x, int z) {
        return GRID.getOrDefault(gridKey(gridCoord(x), gridCoord(z)), List.of());
    }

    private static int gridCoord(int coordinate) {
        return Math.floorDiv(coordinate, GRID_SIZE);
    }

    private static long gridKey(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }

    private static Map<Long, List<PolygonLake>> buildGrid(List<PolygonLake> lakes) {
        Map<Long, List<PolygonLake>> mutable = new HashMap<>();
        for (PolygonLake lake : lakes) {
            int minCellX = gridCoord(lake.minX);
            int maxCellX = gridCoord(lake.maxX);
            int minCellZ = gridCoord(lake.minZ);
            int maxCellZ = gridCoord(lake.maxZ);
            for (int cx = minCellX; cx <= maxCellX; cx++) {
                for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                    mutable.computeIfAbsent(gridKey(cx, cz), ignored -> new ArrayList<>()).add(lake);
                }
            }
        }
        Map<Long, List<PolygonLake>> result = new HashMap<>(mutable.size());
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static Map<String, PolygonLake> buildById(List<PolygonLake> lakes) {
        Map<String, PolygonLake> result = new HashMap<>();
        for (PolygonLake lake : lakes) {
            if (result.put(lake.id, lake) != null) {
                throw new IllegalStateException("Duplicate retained lake id: " + lake.id);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, PolygonLake> buildByName(List<PolygonLake> lakes) {
        Map<String, PolygonLake> result = new HashMap<>();
        for (PolygonLake lake : lakes) {
            // W09 contains a few unnamed/placeholder labels. Runtime identity is lake_id; the
            // name lookup remains a compatibility convenience and therefore keeps the first.
            result.putIfAbsent(lake.name, lake);
        }
        return Map.copyOf(result);
    }

    private static Set<String> loadActiveIds() {
        InputStream raw = RetainedLakeData.class.getResourceAsStream(ACTIVE_IDS_RESOURCE);
        if (raw == null) throw new IllegalStateException("Missing RFIX-03 active lake resource " + ACTIVE_IDS_RESOURCE);
        try (raw) {
            String text = new String(raw.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> ids = new HashSet<>();
            for (String line : text.split("\\R")) {
                String id = line.trim();
                if (id.isEmpty() || id.startsWith("#")) continue;
                if (!ids.add(id)) throw new IOException("Duplicate active retained lake id " + id);
            }
            if (ids.size() != 172) throw new IOException("Expected 172 active retained lakes, got " + ids.size());
            Set<String> candidates = new HashSet<>();
            for (PolygonLake lake : CANDIDATE_LAKES) candidates.add(lake.id);
            if (!candidates.containsAll(ids)) {
                Set<String> missing = new HashSet<>(ids);
                missing.removeAll(candidates);
                throw new IOException("Active retained lake IDs missing from candidate resource: " + missing);
            }
            return Set.copyOf(ids);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load RFIX-03 active lake IDs", exception);
        }
    }

    private static List<PolygonLake> load() {
        InputStream raw = RetainedLakeData.class.getResourceAsStream(RESOURCE);
        if (raw == null) throw new IllegalStateException("Missing retained-lake resource " + RESOURCE);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if (input.readInt() != MAGIC) throw new IOException("Bad retained-lake magic");
            int version = input.readInt();
            if (version != VERSION) throw new IOException("Unsupported retained-lake version " + version);
            int count = input.readInt();
            if (count != 190) throw new IOException("Expected 190 retained lakes, got " + count);
            List<PolygonLake> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String id = readString(input);
                String name = readString(input);
                LakeData.WaterbodyType type = switch (input.readUnsignedByte()) {
                    case 0 -> LakeData.WaterbodyType.LAKE;
                    case 1 -> LakeData.WaterbodyType.LAGOON;
                    case 2 -> LakeData.WaterbodyType.RESERVOIR;
                    case 3 -> LakeData.WaterbodyType.COASTAL_WATERBODY;
                    default -> throw new IOException("Unknown retained-lake waterbody type");
                };
                boolean demResolved = input.readUnsignedByte() == 1;
                boolean coastal = input.readUnsignedByte() != 0;
                double areaKm2 = input.readFloat();
                float elevationMetres = input.readFloat();
                float maximumDepthMetres = input.readFloat();
                Ring exterior = readRing(input);
                int holeCount = input.readInt();
                List<Ring> holes = new ArrayList<>(holeCount);
                for (int i = 0; i < holeCount; i++) holes.add(readRing(input));
                List<String> inlet = readStringList(input);
                List<String> outlet = readStringList(input);
                List<String> source = readStringList(input);
                List<String> terminal = readStringList(input);
                result.add(new PolygonLake(id, name, type, demResolved, coastal, areaKm2,
                        elevationMetres, maximumDepthMetres, exterior, List.copyOf(holes),
                        inlet, outlet, source, terminal));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load retained W09 lake derivative", exception);
        }
    }

    private static Ring readRing(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 3 || count > 100_000) throw new IOException("Invalid polygon ring size " + count);
        int[] xs = new int[count];
        int[] zs = new int[count];
        for (int i = 0; i < count; i++) {
            xs[i] = input.readInt();
            zs[i] = input.readInt();
        }
        return new Ring(xs, zs);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 1_000_000) throw new IOException("Invalid UTF-8 string length " + length);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated retained-lake string");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> readStringList(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 100_000) throw new IOException("Invalid retained-lake string list size");
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(readString(input));
        return List.copyOf(result);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) return value >= edge1 ? 1.0D : 0.0D;
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static record RuntimeMetadata(String lakeId, String name, int waterSurfaceY,
                                  int maximumDepthBlocks, double targetAreaKm2,
                                  LakeData.WaterbodyType waterbodyType,
                                  LakeData.SurfaceSource surfaceSource,
                                  boolean coastalConnection,
                                  List<String> inletRiverIds, List<String> outletRiverIds,
                                  List<String> sourceRiverIds, List<String> terminalRiverIds,
                                  int vertexCount, int interiorRingCount,
                                  int representativeX, int representativeZ,
                                  float rawW09ElevationMetres, float rawMaximumDepthMetres,
                                  int rawW09SurfaceY,
                                  int regionalTransformedSurfaceY, int terrainP25Y,
                                  int terrainP50Y, int terrainP90Y, int verticalSampleCount) {}

    private record PolygonDistance(boolean inside, double distance) {}
    private record VerticalResolution(int finalWaterY, int regionalTransformedY,
                                      int terrainP25Y, int terrainP50Y, int terrainP90Y,
                                      int sampleCount) {}
    private record TerrainQuartiles(int p25Y, int p50Y, int p90Y, int sampleCount) {}

    private static final class PolygonLake {
        private final String id;
        private final String name;
        private final LakeData.WaterbodyType type;
        private final LakeData.SurfaceSource surfaceSource;
        private final boolean coastalConnection;
        private final double areaKm2;
        private final int waterSurfaceY;
        private final int maximumDepthBlocks;
        private final float rawElevationMetres;
        private final float rawMaximumDepthMetres;
        private final int rawW09SurfaceY;
        private final int regionalTransformedSurfaceY;
        private final int terrainP25Y;
        private final int terrainP50Y;
        private final int terrainP90Y;
        private final int verticalSampleCount;
        private final Ring exterior;
        private final List<Ring> interiors;
        private final List<String> inletRiverIds;
        private final List<String> outletRiverIds;
        private final List<String> sourceRiverIds;
        private final List<String> terminalRiverIds;
        private final int minX, maxX, minZ, maxZ;
        private final double shoreWidth;
        private final double bankWidth;
        private final double bankSlope;
        private final int vertexCount;
        private final int representativeX, representativeZ;

        private PolygonLake(String id, String name, LakeData.WaterbodyType type,
                            boolean demResolved, boolean coastalConnection, double areaKm2,
                            float elevationMetres, float maximumDepthMetres,
                            Ring exterior, List<Ring> interiors,
                            List<String> inletRiverIds, List<String> outletRiverIds,
                            List<String> sourceRiverIds, List<String> terminalRiverIds) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.surfaceSource = demResolved ? LakeData.SurfaceSource.DEM_RESOLVED : LakeData.SurfaceSource.W09;
            this.coastalConnection = coastalConnection;
            this.areaKm2 = areaKm2;
            this.exterior = exterior;
            this.interiors = interiors;
            this.inletRiverIds = inletRiverIds;
            this.outletRiverIds = outletRiverIds;
            this.sourceRiverIds = sourceRiverIds;
            this.terminalRiverIds = terminalRiverIds;
            this.minX = exterior.minX;
            this.maxX = exterior.maxX;
            this.minZ = exterior.minZ;
            this.maxZ = exterior.maxZ;
            this.vertexCount = exterior.size() + interiors.stream().mapToInt(Ring::size).sum();
            int[] representative = representativePoint(exterior, interiors);
            this.representativeX = representative[0];
            this.representativeZ = representative[1];
            this.rawElevationMetres = elevationMetres;
            this.rawMaximumDepthMetres = maximumDepthMetres;
            this.rawW09SurfaceY = Float.isFinite(elevationMetres)
                    ? PJChunkGenerator.SEA_LEVEL
                            + (int)Math.round(elevationMetres / TerrainData.VERTICAL_METRES_PER_BLOCK)
                    : Integer.MIN_VALUE;
            VerticalResolution vertical = resolveVerticalSurface(type, coastalConnection, elevationMetres,
                    exterior, interiors);
            this.waterSurfaceY = vertical.finalWaterY;
            this.regionalTransformedSurfaceY = vertical.regionalTransformedY;
            this.terrainP25Y = vertical.terrainP25Y;
            this.terrainP50Y = vertical.terrainP50Y;
            this.terrainP90Y = vertical.terrainP90Y;
            this.verticalSampleCount = vertical.sampleCount;
            this.maximumDepthBlocks = !Float.isFinite(maximumDepthMetres) || maximumDepthMetres <= 0.0F
                    ? representativeDepth(type, areaKm2)
                    : Math.max(1, (int)Math.round(maximumDepthMetres / TerrainData.VERTICAL_METRES_PER_BLOCK));
            this.shoreWidth = clamp(10.0D + Math.sqrt(areaKm2) * 1.8D, 12.0D, 56.0D);
            this.bankWidth = type == LakeData.WaterbodyType.COASTAL_WATERBODY ? 16.0D
                    : type == LakeData.WaterbodyType.LAGOON ? 28.0D
                    : clamp(24.0D + Math.sqrt(areaKm2) * 2.0D, 32.0D, 96.0D);
            this.bankSlope = waterSurfaceY > 30 ? 0.75D : 0.35D;
        }

        private RuntimeMetadata metadata() {
            return new RuntimeMetadata(id, name, waterSurfaceY, maximumDepthBlocks, areaKm2,
                    type, surfaceSource, coastalConnection, inletRiverIds, outletRiverIds,
                    sourceRiverIds, terminalRiverIds, vertexCount, interiors.size(),
                    representativeX, representativeZ, rawElevationMetres, rawMaximumDepthMetres,
                    rawW09SurfaceY, regionalTransformedSurfaceY, terrainP25Y, terrainP50Y, terrainP90Y,
                    verticalSampleCount);
        }

        private LakeData.LakeSample sample(int x, int z) {
            if (!mayContain(x, z)) return LakeData.LakeSample.NONE;
            PolygonDistance distance = distance(x, z);
            if (distance.inside) {
                double depthBlend = smoothstep(0.0D, shoreWidth, distance.distance);
                int depth = 1 + (int)Math.round((maximumDepthBlocks - 1)
                        * Math.pow(depthBlend, 0.72D));
                return new LakeData.LakeSample(true, true, name, waterSurfaceY,
                        waterSurfaceY - Math.max(1, depth), depth, maximumDepthBlocks,
                        0.0D, bankSlope, distance.distance, type);
            }
            if (distance.distance <= bankWidth) {
                return new LakeData.LakeSample(true, false, name, waterSurfaceY,
                        waterSurfaceY - 1, 0, maximumDepthBlocks,
                        clamp(distance.distance / bankWidth, 0.0D, 1.0D), bankSlope,
                        distance.distance, type);
            }
            return LakeData.LakeSample.NONE;
        }

        private List<LakeData.AuditPoint> auditBoundarySamples() {
            List<LakeData.AuditPoint> points = new ArrayList<>();
            appendRingSamples(points, exterior);
            for (Ring ring : interiors) appendRingSamples(points, ring);
            return List.copyOf(points);
        }

        private static void appendRingSamples(List<LakeData.AuditPoint> result, Ring ring) {
            for (int i = 1; i < ring.xs.length; i++) {
                int ax = ring.xs[i - 1], az = ring.zs[i - 1];
                int bx = ring.xs[i], bz = ring.zs[i];
                double length = Math.hypot(bx - ax, bz - az);
                int steps = Math.max(1, (int)Math.ceil(length / 12.0D));
                for (int step = 0; step < steps; step++) {
                    double t = step / (double)steps;
                    result.add(new LakeData.AuditPoint((int)Math.round(ax + (bx - ax) * t),
                            (int)Math.round(az + (bz - az) * t)));
                }
            }
            if (ring.xs.length > 0) result.add(new LakeData.AuditPoint(ring.xs[ring.xs.length - 1],
                    ring.zs[ring.zs.length - 1]));
        }

        private boolean mayContain(int x, int z) {
            return x >= minX - bankWidth && x <= maxX + bankWidth
                    && z >= minZ - bankWidth && z <= maxZ + bankWidth;
        }

        private PolygonDistance distance(int x, int z) {
            // LR-04: Minecraft owns whole block columns, but testing only the integer lattice
            // point aliases detailed W09 shorelines back into long diagonal steps. Evaluate the
            // actual block centre and supersample only the one-block edge band. Away from an edge
            // the centre result is exact and keeps the hot path as cheap as before.
            double centreX = x + 0.5D;
            double centreZ = z + 0.5D;
            boolean centreInside = waterContains(centreX, centreZ);
            double minimum = exterior.distanceToEdges(centreX, centreZ);
            for (Ring hole : interiors) {
                minimum = Math.min(minimum, hole.distanceToEdges(centreX, centreZ));
            }
            if (minimum >= 1.0D) return new PolygonDistance(centreInside, minimum);

            double coverage = blockCoverage(x, z);
            // Majority coverage is area-conservative. Exact ties follow the centre sample so a
            // diagonal shoreline does not flicker between mirrored orientations. No partial water
            // is ever emitted: this only decides ownership of the complete block column.
            boolean inside = coverage > 0.5D
                    || (Math.abs(coverage - 0.5D) <= 1.0e-9D && centreInside);
            return new PolygonDistance(inside, minimum);
        }

        private boolean waterContains(double x, double z) {
            if (!exterior.contains(x, z)) return false;
            for (Ring hole : interiors) if (hole.contains(x, z)) return false;
            return true;
        }

        private double blockCoverage(int blockX, int blockZ) {
            // 4x4 deterministic coverage is limited to the edge band by distance(). Sixteen
            // samples are enough to retain bays/peninsulas at PJ's 1:8 scale without introducing
            // a fractional-water representation or a second runtime mask resource.
            final double[] offsets = {0.125D, 0.375D, 0.625D, 0.875D};
            int wet = 0;
            for (double dz : offsets) {
                for (double dx : offsets) {
                    if (waterContains(blockX + dx, blockZ + dz)) wet++;
                }
            }
            return wet / 16.0D;
        }


        private static int[] representativePoint(Ring exterior, List<Ring> interiors) {
            int spanX = Math.max(1, exterior.maxX - exterior.minX);
            int spanZ = Math.max(1, exterior.maxZ - exterior.minZ);
            for (int grid = 0; grid <= 12; grid++) {
                int divisions = 2 + grid;
                for (int iz = 1; iz < divisions; iz++) {
                    int z = exterior.minZ + spanZ * iz / divisions;
                    for (int ix = 1; ix < divisions; ix++) {
                        int x = exterior.minX + spanX * ix / divisions;
                        // LR-04 representative points follow the same block-centre convention
                        // as runtime polygon ownership; this avoids selecting a lattice corner
                        // whose actual Minecraft column centre lies just outside a narrow shore.
                        if (!exterior.contains(x + 0.5D, z + 0.5D)) continue;
                        boolean hole = false;
                        for (Ring interior : interiors) {
                            if (interior.contains(x + 0.5D, z + 0.5D)) { hole = true; break; }
                        }
                        if (!hole) return new int[] {x, z};
                    }
                }
            }
            // Valid W09 rings should always provide an interior grid point. Keep a deterministic
            // fallback just inside the first edge rather than making class initialization fail.
            int x = (exterior.xs[0] + exterior.xs[1]) / 2;
            int z = (exterior.zs[0] + exterior.zs[1]) / 2;
            return new int[] {x, z};
        }

        private static VerticalResolution resolveVerticalSurface(
                LakeData.WaterbodyType type, boolean coastalConnection, float elevationMetres,
                Ring exterior, List<Ring> interiors) {
            if (!Float.isFinite(elevationMetres)) {
                int resolved = resolveSurfaceFromDem(exterior, interiors);
                TerrainQuartiles terrain = terrainQuartiles(exterior, interiors);
                return new VerticalResolution(resolved, resolved, terrain.p25Y, terrain.p50Y,
                        terrain.p90Y, terrain.sampleCount);
            }

            int rawY = PJChunkGenerator.SEA_LEVEL
                    + (int)Math.round(elevationMetres / TerrainData.VERTICAL_METRES_PER_BLOCK);
            // Coastal/sea-connected retained waterbodies keep their existing sea-level/coastal
            // semantics. Regional basin fitting is only for inland retained LAKE/RESERVOIR and
            // non-coastal LAGOON polygons.
            if (type == LakeData.WaterbodyType.COASTAL_WATERBODY || coastalConnection) {
                return new VerticalResolution(rawY, rawY, Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Integer.MIN_VALUE, 0);
            }

            return resolveW09SurfaceY(elevationMetres, exterior, interiors);
        }

        private static VerticalResolution resolveW09SurfaceY(
                float elevationMetres, Ring exterior, List<Ring> interiors) {
            List<int[]> points = interiorSamplePoints(exterior, interiors, 24);
            List<Integer> transformedMetres = new ArrayList<>(points.size());
            for (int[] point : points) {
                transformedMetres.add(TerrainData.transformRegionalElevationAtWorld(
                        point[0], point[1], elevationMetres));
            }
            int transformed = transformedMetres.isEmpty()
                    ? Math.max(0, (int)Math.round(elevationMetres))
                    : median(transformedMetres);
            int configuredY = PJChunkGenerator.SEA_LEVEL
                    + (int)Math.round(transformed / TerrainData.VERTICAL_METRES_PER_BLOCK);

            TerrainQuartiles terrain = terrainQuartiles(points);
            int finalY = configuredY;
            if (terrain.sampleCount > 0 && configuredY > terrain.p90Y + 4) {
                finalY = Math.min(configuredY, terrain.p25Y);
            }
            return new VerticalResolution(finalY, configuredY, terrain.p25Y, terrain.p50Y,
                    terrain.p90Y, terrain.sampleCount);
        }

        private static List<int[]> interiorSamplePoints(Ring exterior, List<Ring> interiors,
                                                        int minimumPoints) {
            int spanX = Math.max(1, exterior.maxX - exterior.minX);
            int spanZ = Math.max(1, exterior.maxZ - exterior.minZ);
            int[] divisionsToTry = {8, 12, 16, 24, 32};
            List<int[]> best = List.of();
            for (int divisions : divisionsToTry) {
                List<int[]> points = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                for (int iz = 1; iz < divisions; iz++) {
                    int z = exterior.minZ + (int)Math.round(spanZ * iz / (double)divisions);
                    for (int ix = 1; ix < divisions; ix++) {
                        int x = exterior.minX + (int)Math.round(spanX * ix / (double)divisions);
                        if (!exterior.contains(x + 0.5D, z + 0.5D)) continue;
                        boolean hole = false;
                        for (Ring interior : interiors) {
                            if (interior.contains(x + 0.5D, z + 0.5D)) { hole = true; break; }
                        }
                        if (hole) continue;
                        long key = ((long)x << 32) ^ (z & 0xffffffffL);
                        if (seen.add(key)) points.add(new int[] {x, z});
                    }
                }
                if (points.size() > best.size()) best = points;
                if (points.size() >= minimumPoints) return points;
            }
            if (!best.isEmpty()) return best;
            int[] representative = representativePoint(exterior, interiors);
            return List.of(new int[] {representative[0], representative[1]});
        }

        private static TerrainQuartiles terrainQuartiles(Ring exterior, List<Ring> interiors) {
            return terrainQuartiles(interiorSamplePoints(exterior, interiors, 24));
        }

        private static TerrainQuartiles terrainQuartiles(List<int[]> points) {
            List<Integer> heights = new ArrayList<>();
            for (int[] point : points) {
                TerrainData.TerrainSample terrain = TerrainData.sampleWorld(point[0], point[1]);
                if (!terrain.land()) continue;
                heights.add(PJChunkGenerator.SEA_LEVEL + Math.max(1, (int)Math.round(
                        terrain.elevationMetres() / TerrainData.VERTICAL_METRES_PER_BLOCK)));
            }
            if (heights.isEmpty()) {
                return new TerrainQuartiles(Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Integer.MIN_VALUE, 0);
            }
            heights.sort(Integer::compareTo);
            return new TerrainQuartiles(quantile(heights, 0.25D), quantile(heights, 0.50D),
                    quantile(heights, 0.90D), heights.size());
        }

        private static int median(List<Integer> values) {
            List<Integer> sorted = new ArrayList<>(values);
            sorted.sort(Integer::compareTo);
            int middle = sorted.size() / 2;
            return sorted.size() % 2 == 1
                    ? sorted.get(middle)
                    : (int)Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0D);
        }

        private static int quantile(List<Integer> sorted, double quantile) {
            int index = (int)Math.round((sorted.size() - 1) * quantile);
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }

        private static int representativeDepth(LakeData.WaterbodyType type, double areaKm2) {
            if (type == LakeData.WaterbodyType.COASTAL_WATERBODY) {
                return (int)Math.round(clamp(2.0D + Math.sqrt(areaKm2) * 0.45D, 2.0D, 6.0D));
            }
            if (type == LakeData.WaterbodyType.LAGOON) {
                return (int)Math.round(clamp(2.0D + Math.sqrt(areaKm2) * 0.65D, 2.0D, 8.0D));
            }
            if (type == LakeData.WaterbodyType.RESERVOIR) {
                return (int)Math.round(clamp(4.0D + Math.sqrt(areaKm2) * 1.25D, 4.0D, 18.0D));
            }
            return (int)Math.round(clamp(3.0D + Math.sqrt(areaKm2), 3.0D, 20.0D));
        }

        private static int resolveSurfaceFromDem(Ring exterior, List<Ring> interiors) {
            List<Integer> heights = new ArrayList<>();
            int spanX = Math.max(1, exterior.maxX - exterior.minX);
            int spanZ = Math.max(1, exterior.maxZ - exterior.minZ);
            for (int iz = 1; iz <= 7; iz++) {
                int z = exterior.minZ + spanZ * iz / 8;
                for (int ix = 1; ix <= 7; ix++) {
                    int x = exterior.minX + spanX * ix / 8;
                    if (!exterior.contains(x, z)) continue;
                    boolean hole = false;
                    for (Ring interior : interiors) {
                        if (interior.contains(x, z)) { hole = true; break; }
                    }
                    if (hole) continue;
                    TerrainData.TerrainSample terrain = TerrainData.sampleWorld(x, z);
                    if (!terrain.land()) continue;
                    double coastRise = smoothstep(0.50D, 0.84D, terrain.landCoverage());
                    heights.add(Math.max(1, (int)Math.round(
                            terrain.elevationMetres() / TerrainData.VERTICAL_METRES_PER_BLOCK
                                    * coastRise)));
                }
            }
            if (heights.isEmpty()) return PJChunkGenerator.SEA_LEVEL;
            heights.sort(Integer::compareTo);
            // Lower quartile tracks the basin floor while resisting a single outlet notch.
            return Math.max(PJChunkGenerator.SEA_LEVEL, heights.get(heights.size() / 4));
        }
    }

    private static final class Ring {
        private final int[] xs;
        private final int[] zs;
        private final int minX, maxX, minZ, maxZ;

        private Ring(int[] xs, int[] zs) {
            this.xs = xs;
            this.zs = zs;
            this.minX = Arrays.stream(xs).min().orElse(0);
            this.maxX = Arrays.stream(xs).max().orElse(0);
            this.minZ = Arrays.stream(zs).min().orElse(0);
            this.maxZ = Arrays.stream(zs).max().orElse(0);
        }

        private int size() { return xs.length; }

        private boolean contains(int x, int z) { return contains((double)x, (double)z); }

        private boolean contains(double x, double z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
            boolean inside = false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                double xi = xs[i], zi = zs[i], xj = xs[j], zj = zs[j];
                boolean crosses = ((zi > z) != (zj > z))
                        && x < (xj - xi) * (z - zi) / (zj - zi) + xi;
                if (crosses) inside = !inside;
            }
            return inside;
        }

        private double distanceToEdges(double x, double z) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 1; i < xs.length; i++) {
                best = Math.min(best, pointSegmentDistance(x, z,
                        xs[i-1], zs[i-1], xs[i], zs[i]));
            }
            if (xs[0] != xs[xs.length-1] || zs[0] != zs[zs.length-1]) {
                best = Math.min(best, pointSegmentDistance(x, z,
                        xs[xs.length-1], zs[zs.length-1], xs[0], zs[0]));
            }
            return best;
        }

        private static double pointSegmentDistance(double px, double pz,
                                                   double ax, double az,
                                                   double bx, double bz) {
            double dx = bx - ax, dz = bz - az;
            double lengthSquared = dx*dx + dz*dz;
            if (lengthSquared <= 1.0e-9D) return Math.hypot(px-ax, pz-az);
            double t = ((px-ax)*dx + (pz-az)*dz) / lengthSquared;
            t = clamp(t, 0.0D, 1.0D);
            return Math.hypot(px - (ax + dx*t), pz - (az + dz*t));
        }
    }
}
