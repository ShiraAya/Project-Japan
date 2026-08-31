package com.sora.projectjapan.worldgen;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** RFIX-09 official W09 shoreline clip for the authored Hibara water footprint. */
final class HibaraFootprintData {
    private static final String RESOURCE = "/assets/projectjapan/hydrology/generated/hibara_footprint.bin.gz";
    private static final int MAGIC = 0x504A4846; // PJHF
    private static final int VERSION = 1;
    private static final Polygon POLYGON = load();

    private HibaraFootprintData() {}

    static LakeData.SpatialDistance distance(int x, int z) {
        // LR-04 uses the same block-centre + edge supersampling policy as retained W09 lakes.
        // Hibara is authored, but RFIX-09 deliberately replaced its runtime footprint with this
        // W09 polygon, so letting it keep the old integer-lattice rasterizer would reintroduce a
        // visible quality seam between authored and retained polygon lakes.
        double centreX = x + 0.5D;
        double centreZ = z + 0.5D;
        boolean centreInside = waterContains(centreX, centreZ);
        double distance = edgeDistance(POLYGON.exterior, centreX, centreZ);
        for (Ring hole : POLYGON.holes) distance = Math.min(distance, edgeDistance(hole, centreX, centreZ));
        if (distance >= 1.0D) return new LakeData.SpatialDistance(centreInside, distance);

        double coverage = blockCoverage(x, z);
        boolean inside = coverage > 0.5D
                || (Math.abs(coverage - 0.5D) <= 1.0e-9D && centreInside);
        return new LakeData.SpatialDistance(inside, distance);
    }

    private static boolean waterContains(double x, double z) {
        if (!inside(POLYGON.exterior, x, z)) return false;
        for (Ring hole : POLYGON.holes) if (inside(hole, x, z)) return false;
        return true;
    }

    private static double blockCoverage(int blockX, int blockZ) {
        final double[] offsets = {0.125D, 0.375D, 0.625D, 0.875D};
        int wet = 0;
        for (double dz : offsets) for (double dx : offsets) {
            if (waterContains(blockX + dx, blockZ + dz)) wet++;
        }
        return wet / 16.0D;
    }

    static double rasterAuditCoverage(int blockX, int blockZ) {
        return blockCoverage(blockX, blockZ);
    }

    static boolean rasterAuditCentreInside(int blockX, int blockZ) {
        return waterContains(blockX + 0.5D, blockZ + 0.5D);
    }

    static List<LakeData.AuditPoint> boundarySamples() {
        List<LakeData.AuditPoint> result = new ArrayList<>(POLYGON.exterior.size());
        for (int i = 0; i < POLYGON.exterior.size(); i++) {
            result.add(new LakeData.AuditPoint(POLYGON.exterior.x[i], POLYGON.exterior.z[i]));
        }
        return List.copyOf(result);
    }

    static int minX() { return POLYGON.exterior.minX; }
    static int maxX() { return POLYGON.exterior.maxX; }
    static int minZ() { return POLYGON.exterior.minZ; }
    static int maxZ() { return POLYGON.exterior.maxZ; }

    private static Polygon load() {
        try (InputStream raw = HibaraFootprintData.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("Missing " + RESOURCE);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid Hibara footprint magic");
                if (in.readInt() != VERSION) throw new IOException("Unsupported Hibara footprint version");
                Ring exterior = readRing(in, 20_000);
                int holeCount = readCount(in, 512);
                List<Ring> holes = new ArrayList<>(holeCount);
                for (int i = 0; i < holeCount; i++) holes.add(readRing(in, 20_000));
                return new Polygon(exterior, List.copyOf(holes));
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Ring readRing(DataInputStream in, int max) throws IOException {
        int count = readCount(in, max);
        if (count < 3) throw new IOException("Hibara polygon ring too small: " + count);
        int[] x = new int[count], z = new int[count];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            x[i] = in.readInt(); z[i] = in.readInt();
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minZ = Math.min(minZ, z[i]); maxZ = Math.max(maxZ, z[i]);
        }
        return new Ring(x, z, minX, maxX, minZ, maxZ);
    }

    private static int readCount(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("Invalid Hibara footprint count " + count);
        return count;
    }

    private static boolean inside(Ring ring, double x, double z) {
        if (x < ring.minX || x > ring.maxX || z < ring.minZ || z > ring.maxZ) return false;
        boolean inside = false;
        int j = ring.size() - 1;
        for (int i = 0; i < ring.size(); i++) {
            double xi = ring.x[i], zi = ring.z[i], xj = ring.x[j], zj = ring.z[j];
            if ((zi > z) != (zj > z)) {
                double crossX = xi + (xj - xi) * (z - zi) / (zj - zi);
                if (x < crossX) inside = !inside;
            }
            j = i;
        }
        return inside;
    }

    private static double edgeDistance(Ring ring, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        int j = ring.size() - 1;
        for (int i = 0; i < ring.size(); i++) {
            best = Math.min(best, pointSegmentDistance(x, z,
                    ring.x[j], ring.z[j], ring.x[i], ring.z[i]));
            j = i;
        }
        return best;
    }

    private static double pointSegmentDistance(double px, double pz, double ax, double az,
                                               double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double denom = dx * dx + dz * dz;
        double t = denom <= 1.0e-12 ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, ((px - ax) * dx + (pz - az) * dz) / denom));
        double qx = ax + dx * t, qz = az + dz * t;
        return Math.hypot(px - qx, pz - qz);
    }

    private record Polygon(Ring exterior, List<Ring> holes) {}
    private static final class Ring {
        final int[] x, z; final int minX, maxX, minZ, maxZ;
        Ring(int[] x, int[] z, int minX, int maxX, int minZ, int maxZ) {
            this.x = x; this.z = z; this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
        }
        int size() { return x.length; }
    }
}
