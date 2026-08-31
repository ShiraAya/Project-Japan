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
import java.util.zip.GZIPInputStream;

/** LR-11 refined W09 polygon footprints for all 33 authored lakes. */
final class AuthoredLakeFootprintData {
    private static final String RESOURCE = "/assets/projectjapan/hydrology/generated/lr_refined_authored_lakes.bin.gz";
    private static final int MAGIC = 0x504A414C; // PJAL
    private static final int VERSION = 1;
    private static final Map<String, Polygon> BY_NAME = load();

    private AuthoredLakeFootprintData() {}

    static boolean has(String name) { return BY_NAME.containsKey(name); }

    static LakeData.SpatialDistance distance(String name, int x, int z) {
        Polygon polygon = require(name);
        double cx = x + 0.5D, cz = z + 0.5D;
        boolean centreInside = polygon.waterContains(cx, cz);
        double minimum = polygon.exterior.distanceToEdges(cx, cz);
        for (Ring hole : polygon.holes) minimum = Math.min(minimum, hole.distanceToEdges(cx, cz));
        if (minimum >= 1.0D) return new LakeData.SpatialDistance(centreInside, minimum);
        double coverage = polygon.blockCoverage(x, z);
        boolean inside = coverage > 0.5D || (Math.abs(coverage - 0.5D) <= 1.0e-9D && centreInside);
        return new LakeData.SpatialDistance(inside, minimum);
    }

    static int minX(String name) { return require(name).exterior.minX; }
    static int maxX(String name) { return require(name).exterior.maxX; }
    static int minZ(String name) { return require(name).exterior.minZ; }
    static int maxZ(String name) { return require(name).exterior.maxZ; }
    static int vertexCount(String name) { return require(name).vertexCount; }
    static int holeCount(String name) { return require(name).holes.size(); }
    static String officialLakeId(String name) { return require(name).officialLakeId; }
    static String matchConfidence(String name) { return require(name).confidence; }

    static int representativeX(String name) { return require(name).representativeX; }
    static int representativeZ(String name) { return require(name).representativeZ; }

    static List<LakeData.AuditPoint> boundarySamples(String name) {
        Polygon p = require(name);
        List<LakeData.AuditPoint> result = new ArrayList<>();
        appendSamples(result, p.exterior);
        for (Ring h : p.holes) appendSamples(result, h);
        return List.copyOf(result);
    }

    static double rasterAuditCoverage(String name, int x, int z) { return require(name).blockCoverage(x, z); }
    static boolean rasterAuditCentreInside(String name, int x, int z) {
        return require(name).waterContains(x + 0.5D, z + 0.5D);
    }

    private static void appendSamples(List<LakeData.AuditPoint> result, Ring ring) {
        int n = ring.xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double len = Math.hypot(ring.xs[j] - ring.xs[i], ring.zs[j] - ring.zs[i]);
            int steps = Math.max(1, (int)Math.ceil(len / 12.0D));
            for (int s = 0; s < steps; s++) {
                double t = s / (double)steps;
                result.add(new LakeData.AuditPoint((int)Math.round(ring.xs[i] + (ring.xs[j]-ring.xs[i])*t),
                        (int)Math.round(ring.zs[i] + (ring.zs[j]-ring.zs[i])*t)));
            }
        }
    }

    private static Polygon require(String name) {
        Polygon p = BY_NAME.get(name);
        if (p == null) throw new IllegalArgumentException("Unknown authored refined lake " + name);
        return p;
    }

    private static Map<String, Polygon> load() {
        InputStream raw = AuthoredLakeFootprintData.class.getResourceAsStream(RESOURCE);
        if (raw == null) throw new IllegalStateException("Missing LR-11 authored lake resource " + RESOURCE);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid LR authored lake magic");
            if (in.readInt() != VERSION) throw new IOException("Unsupported LR authored lake version");
            int count = in.readInt();
            if (count != 33) throw new IOException("Expected 33 authored refined lakes, got " + count);
            Map<String, Polygon> result = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String name = readString(in), officialId = readString(in), confidence = readString(in);
                Ring exterior = readRing(in);
                int holes = in.readInt();
                if (holes < 0 || holes > 4096) throw new IOException("Invalid authored hole count " + holes);
                List<Ring> interiors = new ArrayList<>(holes);
                for (int h = 0; h < holes; h++) interiors.add(readRing(in));
                Polygon p = new Polygon(name, officialId, confidence, exterior, List.copyOf(interiors));
                if (result.put(name, p) != null) throw new IOException("Duplicate authored lake " + name);
            }
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load LR-11 authored lake polygons", e);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt(); if (len < 0 || len > 1_000_000) throw new IOException("Invalid string length");
        byte[] b = in.readNBytes(len); if (b.length != len) throw new IOException("Truncated string");
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Ring readRing(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 3 || count > 100_000) throw new IOException("Invalid ring size " + count);
        int[] xs = new int[count], zs = new int[count];
        for (int i = 0; i < count; i++) { xs[i] = in.readInt(); zs[i] = in.readInt(); }
        return new Ring(xs, zs);
    }

    private static final class Polygon {
        final String name, officialLakeId, confidence; final Ring exterior; final List<Ring> holes;
        final int vertexCount, representativeX, representativeZ;
        Polygon(String name, String officialLakeId, String confidence, Ring exterior, List<Ring> holes) {
            this.name=name; this.officialLakeId=officialLakeId; this.confidence=confidence; this.exterior=exterior; this.holes=holes;
            this.vertexCount = exterior.size() + holes.stream().mapToInt(Ring::size).sum();
            int[] rp = representativePoint(exterior, holes); representativeX=rp[0]; representativeZ=rp[1];
        }
        boolean waterContains(double x,double z) {
            if (!exterior.contains(x,z)) return false;
            for (Ring h:holes) if (h.contains(x,z)) return false;
            return true;
        }
        double blockCoverage(int bx,int bz) {
            final double[] o={0.125D,0.375D,0.625D,0.875D}; int wet=0;
            for(double dz:o) for(double dx:o) if(waterContains(bx+dx,bz+dz)) wet++;
            return wet/16.0D;
        }
    }

    private static int[] representativePoint(Ring exterior, List<Ring> holes) {
        int spanX=Math.max(1,exterior.maxX-exterior.minX), spanZ=Math.max(1,exterior.maxZ-exterior.minZ);
        for(int grid=0;grid<=16;grid++) {
            int div=2+grid;
            for(int iz=1;iz<div;iz++) for(int ix=1;ix<div;ix++) {
                int x=exterior.minX+spanX*ix/div, z=exterior.minZ+spanZ*iz/div;
                if(!exterior.contains(x+0.5D,z+0.5D)) continue;
                boolean hole=false; for(Ring h:holes) if(h.contains(x+0.5D,z+0.5D)){hole=true;break;}
                if(!hole) return new int[]{x,z};
            }
        }
        return new int[]{exterior.xs[0], exterior.zs[0]};
    }

    private static final class Ring {
        private static final int SCAN_BAND = 64;
        private static final int BVH_LEAF = 12;

        final int[] xs, zs;
        final int minX, maxX, minZ, maxZ;
        final int scanBandMin;
        final int[][] scanEdges;
        final int[] edgeOrder;
        final BvhNode edgeBvh;

        Ring(int[] xs, int[] zs) {
            this.xs = xs;
            this.zs = zs;
            minX = Arrays.stream(xs).min().orElse(0);
            maxX = Arrays.stream(xs).max().orElse(0);
            minZ = Arrays.stream(zs).min().orElse(0);
            maxZ = Arrays.stream(zs).max().orElse(0);

            scanBandMin = Math.floorDiv(minZ, SCAN_BAND);
            int scanBandMax = Math.floorDiv(maxZ, SCAN_BAND);
            @SuppressWarnings("unchecked")
            ArrayList<Integer>[] bands = new ArrayList[scanBandMax - scanBandMin + 1];
            for (int i = 0; i < xs.length; i++) {
                int j = (i + 1) % xs.length;
                int lo = Math.floorDiv(Math.min(zs[i], zs[j]), SCAN_BAND) - scanBandMin;
                int hi = Math.floorDiv(Math.max(zs[i], zs[j]), SCAN_BAND) - scanBandMin;
                for (int band = lo; band <= hi; band++) {
                    ArrayList<Integer> list = bands[band];
                    if (list == null) bands[band] = list = new ArrayList<>();
                    list.add(i);
                }
            }
            scanEdges = new int[bands.length][];
            for (int band = 0; band < bands.length; band++) {
                ArrayList<Integer> list = bands[band];
                if (list == null) {
                    scanEdges[band] = new int[0];
                } else {
                    scanEdges[band] = new int[list.size()];
                    for (int i = 0; i < list.size(); i++) scanEdges[band][i] = list.get(i);
                }
            }

            Integer[] boxed = new Integer[xs.length];
            for (int i = 0; i < boxed.length; i++) boxed[i] = i;
            edgeBvh = buildBvh(boxed, 0, boxed.length);
            edgeOrder = new int[boxed.length];
            for (int i = 0; i < boxed.length; i++) edgeOrder[i] = boxed[i];
        }

        int size() { return xs.length; }

        boolean contains(double x, double z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
            int band = Math.floorDiv((int)Math.floor(z), SCAN_BAND) - scanBandMin;
            if (band < 0 || band >= scanEdges.length) return false;
            boolean inside = false;
            for (int i : scanEdges[band]) {
                int j = (i + 1) % xs.length;
                double xi = xs[i], zi = zs[i], xj = xs[j], zj = zs[j];
                boolean crosses = ((zi > z) != (zj > z))
                        && x < (xj - xi) * (z - zi) / (zj - zi) + xi;
                if (crosses) inside = !inside;
            }
            return inside;
        }

        double distanceToEdges(double x, double z) {
            return nearestDistance(edgeBvh, x, z, Double.POSITIVE_INFINITY);
        }

        private BvhNode buildBvh(Integer[] order, int from, int to) {
            int nodeMinX = Integer.MAX_VALUE, nodeMaxX = Integer.MIN_VALUE;
            int nodeMinZ = Integer.MAX_VALUE, nodeMaxZ = Integer.MIN_VALUE;
            for (int k = from; k < to; k++) {
                int i = order[k], j = (i + 1) % xs.length;
                nodeMinX = Math.min(nodeMinX, Math.min(xs[i], xs[j]));
                nodeMaxX = Math.max(nodeMaxX, Math.max(xs[i], xs[j]));
                nodeMinZ = Math.min(nodeMinZ, Math.min(zs[i], zs[j]));
                nodeMaxZ = Math.max(nodeMaxZ, Math.max(zs[i], zs[j]));
            }
            if (to - from <= BVH_LEAF) {
                return new BvhNode(nodeMinX, nodeMaxX, nodeMinZ, nodeMaxZ, from, to, null, null);
            }
            boolean splitX = nodeMaxX - nodeMinX >= nodeMaxZ - nodeMinZ;
            Arrays.sort(order, from, to, (a, b) -> {
                int aj = (a + 1) % xs.length, bj = (b + 1) % xs.length;
                long ac = splitX ? (long)xs[a] + xs[aj] : (long)zs[a] + zs[aj];
                long bc = splitX ? (long)xs[b] + xs[bj] : (long)zs[b] + zs[bj];
                return Long.compare(ac, bc);
            });
            int mid = (from + to) >>> 1;
            BvhNode left = buildBvh(order, from, mid);
            BvhNode right = buildBvh(order, mid, to);
            return new BvhNode(nodeMinX, nodeMaxX, nodeMinZ, nodeMaxZ, from, to, left, right);
        }

        private double nearestDistance(BvhNode node, double x, double z, double best) {
            if (node == null || node.distanceToBox(x, z) >= best) return best;
            if (node.left == null) {
                for (int k = node.from; k < node.to; k++) {
                    int i = edgeOrder[k], j = (i + 1) % xs.length;
                    best = Math.min(best, pointSegmentDistance(x, z, xs[i], zs[i], xs[j], zs[j]));
                }
                return best;
            }
            double leftDistance = node.left.distanceToBox(x, z);
            double rightDistance = node.right.distanceToBox(x, z);
            if (leftDistance <= rightDistance) {
                best = nearestDistance(node.left, x, z, best);
                return nearestDistance(node.right, x, z, best);
            }
            best = nearestDistance(node.right, x, z, best);
            return nearestDistance(node.left, x, z, best);
        }

        static double pointSegmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
            double dx = bx - ax, dz = bz - az, d2 = dx * dx + dz * dz;
            double t = d2 <= 1e-9 ? 0 : ((px - ax) * dx + (pz - az) * dz) / d2;
            t = Math.max(0, Math.min(1, t));
            return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
        }

        private record BvhNode(int minX, int maxX, int minZ, int maxZ, int from, int to,
                               BvhNode left, BvhNode right) {
            double distanceToBox(double x, double z) {
                double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0D);
                double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0D);
                return Math.hypot(dx, dz);
            }
        }
    }
}
