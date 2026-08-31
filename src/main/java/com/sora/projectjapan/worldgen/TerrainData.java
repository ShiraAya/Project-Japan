package com.sora.projectjapan.worldgen;

import com.sora.projectjapan.ProjectJapan;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public final class TerrainData {
    public static final double TOKYO_LAT = 35.681236D;
    public static final double TOKYO_LON = 139.767125D;
    private static final double MIN_LON = 122.0D;
    private static final double MAX_LON = 154.0D;
    private static final double MIN_LAT = 20.0D;
    private static final double MAX_LAT = 46.0D;

    /** Eight real horizontal metres are represented by one Minecraft block (scale 0.125). */
    public static final double HORIZONTAL_METRES_PER_BLOCK = 8.0D;
    /**
     * Four real vertical metres are represented by one Minecraft block (scale 0.25).
     * Together with the horizontal scale this is 0.125:0.25:0.125 (X:Y:Z).
     */
    public static final double VERTICAL_METRES_PER_BLOCK = 4.0D;

    public static final double METRES_PER_DEGREE_LAT = 111_320.0D;
    public static final double METRES_PER_DEGREE_LON =
            METRES_PER_DEGREE_LAT * Math.cos(Math.toRadians(TOKYO_LAT));

    /**
     * Four times the former coastline resolution in each direction. The grayscale edge is
     * intentionally retained and sampled bilinearly so coastlines are no longer one-pixel steps.
     */
    private static final int SAMPLE_CACHE_SIZE = 4096;
    private static final GrayRaster LAND = load("japan_land_hd.png");
    private static final GrayRaster HEIGHT = load("japan_height.png");
    /**
     * 0.6.x uses matching 8192x6656 height and land grids. In that common case, reading LAND
     * directly avoids a second 52 MiB byte array and a 54.5-million-pixel startup conversion.
     * Keep the resampled mask only as a compatibility path for replacement rasters of other sizes.
     */
    private static final byte[] HEIGHT_LAND_MASK =
            HEIGHT.getWidth() == LAND.getWidth() && HEIGHT.getHeight() == LAND.getHeight()
                    ? null : buildHeightLandMask();
    private static final ThreadLocal<SampleCache> SAMPLE_CACHE =
            ThreadLocal.withInitial(() -> new SampleCache(SAMPLE_CACHE_SIZE));

    private TerrainData() {}

    public static int landRasterWidth() { return LAND.getWidth(); }
    public static int landRasterHeight() { return LAND.getHeight(); }
    public static int heightRasterWidth() { return HEIGHT.getWidth(); }
    public static int heightRasterHeight() { return HEIGHT.getHeight(); }

    public static TerrainSample sampleWorld(int worldX, int worldZ) {
        long key = pointKey(worldX, worldZ);
        SampleCache cache = SAMPLE_CACHE.get();
        TerrainSample cached = cache.get(key);
        if (cached != null) return cached;

        TerrainSample result = sampleWorldUncached(worldX, worldZ);
        cache.put(key, result);
        return result;
    }

    private static TerrainSample sampleWorldUncached(int worldX, int worldZ) {
        double longitude = TOKYO_LON
                + worldX * HORIZONTAL_METRES_PER_BLOCK / METRES_PER_DEGREE_LON;
        double latitude = TOKYO_LAT
                - worldZ * HORIZONTAL_METRES_PER_BLOCK / METRES_PER_DEGREE_LAT;

        if (longitude < MIN_LON || longitude > MAX_LON || latitude < MIN_LAT || latitude > MAX_LAT) {
            return TerrainSample.OCEAN;
        }

        double u = (longitude - MIN_LON) / (MAX_LON - MIN_LON);
        double v = (MAX_LAT - latitude) / (MAX_LAT - MIN_LAT);
        double coverage = sampleNormalized(LAND, u, v) / 255.0D;
        boolean land = coverage >= 0.5D;
        int elevation = land ? Math.max(0, (int)Math.round(sampleElevation(u, v))) : 0;
        if (land) {
            elevation = applyLandmarkElevationCorrections(longitude, latitude, elevation);
            elevation = applyRegionalElevationCorrections(longitude, latitude, elevation);
        }
        return new TerrainSample(land, elevation, coverage);
    }


    /**
     * Applies the same position-dependent regional elevation transfer used by the final PJ
     * terrain to an externally supplied real-world elevation. This intentionally excludes
     * landmark mountain envelopes: those correct under-resolved DEM summits and must never lift
     * a hydrology surface such as a lake.
     */
    static int transformRegionalElevationAtWorld(int worldX, int worldZ, double elevationMetres) {
        double longitude = TOKYO_LON
                + worldX * HORIZONTAL_METRES_PER_BLOCK / METRES_PER_DEGREE_LON;
        double latitude = TOKYO_LAT
                - worldZ * HORIZONTAL_METRES_PER_BLOCK / METRES_PER_DEGREE_LAT;
        int elevation = Math.max(0, (int)Math.round(elevationMetres));
        return applyRegionalElevationCorrections(longitude, latitude, elevation);
    }

    public static int worldXFromLongitude(double longitude) {
        return (int)Math.round((longitude - TOKYO_LON) * METRES_PER_DEGREE_LON
                / HORIZONTAL_METRES_PER_BLOCK);
    }

    public static int worldZFromLatitude(double latitude) {
        return (int)Math.round((TOKYO_LAT - latitude) * METRES_PER_DEGREE_LAT
                / HORIZONTAL_METRES_PER_BLOCK);
    }

    public static boolean isLandWorld(int worldX, int worldZ) {
        return sampleWorld(worldX, worldZ).land();
    }

    /**
     * The bundled national DEM is deliberately compact and smooth enough for city construction.
     * A few narrow famous summits are therefore under-resolved. Correct those landmarks locally
     * without adding high-frequency noise to every plain.
     */
    private static int applyLandmarkElevationCorrections(double longitude, double latitude,
                                                          int sampledElevation) {
        // Mount Kobushi: shared watershed of the Shinano/Chikuma, Arakawa and Fuji systems.
        return Math.max(sampledElevation, mountainEnvelope(
                longitude, latitude, sampledElevation,
                138.72889D, 35.90889D, 2475, 22_000.0D));
    }

    private static int applyRegionalElevationCorrections(double longitude, double latitude,
                                                         int sampledElevation) {
        int elevation = sampledElevation;

        // PJ is primarily a city-building map. Apply a continuous monotone transfer curve inside
        // the two largest construction regions: low ground is compressed strongly, while higher
        // terrain keeps more of its relief. Unlike the former elevation-eligibility blend, this
        // cannot make a higher input elevation receive less correction abruptly and turn a real
        // foothill into an artificial escarpment.
        elevation = urbanPlain(longitude, latitude, elevation,
                139.72D, 35.82D, 105_000.0D, 88_000.0D,
                12.0D, 260.0D, 0.24D, 0.48D); // Kanto
        elevation = urbanPlain(longitude, latitude, elevation,
                135.50D, 34.68D, 54_000.0D, 38_000.0D,
                8.0D, 200.0D, 0.22D, 0.34D);  // Osaka/Kobe basin

        return elevation;
    }

    private static int urbanPlain(double longitude, double latitude, int baseElevation,
                                  double centreLongitude, double centreLatitude,
                                  double radiusEastWestMetres, double radiusNorthSouthMetres,
                                  double floorElevation, double lowReliefCeiling,
                                  double lowReliefRetention, double mountainReliefRetention) {
        if (baseElevation <= floorElevation) return baseElevation;
        double dxMetres = (longitude - centreLongitude) * METRES_PER_DEGREE_LON;
        if (Math.abs(dxMetres) >= radiusEastWestMetres) return baseElevation;
        double dzMetres = (latitude - centreLatitude) * METRES_PER_DEGREE_LAT;
        if (Math.abs(dzMetres) >= radiusNorthSouthMetres) return baseElevation;
        double dx = dxMetres / radiusEastWestMetres;
        double dz = dzMetres / radiusNorthSouthMetres;
        double normalizedSquared = dx * dx + dz * dz;
        if (normalizedSquared >= 1.0D) return baseElevation;

        // Keep the centre fully influenced and spend more than half of the ellipse radius on the
        // transition. This distributes restoration over many kilometres instead of drawing a
        // visible rim around the construction region.
        double spatialInfluence = normalizedSquared <= 0.45D * 0.45D ? 1.0D
                : 1.0D - smoothstep(0.45D, 1.0D, Math.sqrt(normalizedSquared));
        if (spatialInfluence <= 0.0D) return baseElevation;

        // Both segments have a positive slope no greater than one. The function is continuous at
        // lowReliefCeiling, so increasing source elevation can never create the correction reversal
        // responsible for the former 400-metre one-kilometre step.
        double lowSegmentTop = floorElevation
                + (lowReliefCeiling - floorElevation) * lowReliefRetention;
        double corrected = baseElevation <= lowReliefCeiling
                ? floorElevation + (baseElevation - floorElevation) * lowReliefRetention
                : lowSegmentTop
                        + (baseElevation - lowReliefCeiling) * mountainReliefRetention;
        return (int)Math.round(baseElevation
                + (corrected - baseElevation) * spatialInfluence);
    }

    private static int mountainEnvelope(double longitude, double latitude, int baseElevation,
                                        double peakLongitude, double peakLatitude,
                                        int peakElevation, double radiusMetres) {
        double dx = (longitude - peakLongitude) * METRES_PER_DEGREE_LON;
        double dz = (latitude - peakLatitude) * METRES_PER_DEGREE_LAT;
        double distance = Math.hypot(dx, dz);
        if (distance >= radiusMetres) return baseElevation;
        double normalized = distance / radiusMetres;
        double influence = 1.0D - normalized * normalized * (3.0D - 2.0D * normalized);
        influence = Math.pow(influence, 1.65D);
        return (int)Math.round(baseElevation + (peakElevation - baseElevation) * influence);
    }

    private static double sampleElevation(double u, double v) {
        double px = u * (HEIGHT.getWidth() - 1);
        double py = v * (HEIGHT.getHeight() - 1);
        int x0 = clamp((int)Math.floor(px), 0, HEIGHT.getWidth() - 1);
        int y0 = clamp((int)Math.floor(py), 0, HEIGHT.getHeight() - 1);
        int x1 = Math.min(x0 + 1, HEIGHT.getWidth() - 1);
        int y1 = Math.min(y0 + 1, HEIGHT.getHeight() - 1);
        double tx = px - x0;
        double ty = py - y0;

        double sum = 0.0D;
        double weight = 0.0D;
        double w00 = (1 - tx) * (1 - ty);
        double w10 = tx * (1 - ty);
        double w01 = (1 - tx) * ty;
        double w11 = tx * ty;

        if (isHeightPixelOnLand(x0, y0)) {
            sum += HEIGHT.getSample(x0, y0, 0) * w00;
            weight += w00;
        }
        if (isHeightPixelOnLand(x1, y0)) {
            sum += HEIGHT.getSample(x1, y0, 0) * w10;
            weight += w10;
        }
        if (isHeightPixelOnLand(x0, y1)) {
            sum += HEIGHT.getSample(x0, y1, 0) * w01;
            weight += w01;
        }
        if (isHeightPixelOnLand(x1, y1)) {
            sum += HEIGHT.getSample(x1, y1, 0) * w11;
            weight += w11;
        }
        return weight > 0.0D ? sum / weight : 0.0D;
    }

    private static boolean isHeightPixelOnLand(int x, int y) {
        if (HEIGHT_LAND_MASK == null) return LAND.getSample(x, y, 0) >= 128;
        return HEIGHT_LAND_MASK[y * HEIGHT.getWidth() + x] != 0;
    }

    private static byte[] buildHeightLandMask() {
        byte[] result = new byte[HEIGHT.getWidth() * HEIGHT.getHeight()];
        int index = 0;
        for (int y = 0; y < HEIGHT.getHeight(); y++) {
            double v = y / (double)(HEIGHT.getHeight() - 1);
            for (int x = 0; x < HEIGHT.getWidth(); x++) {
                double u = x / (double)(HEIGHT.getWidth() - 1);
                if (sampleNormalized(LAND, u, v) >= 127.5D) result[index] = 1;
                index++;
            }
        }
        return result;
    }

    private static double sampleNormalized(GrayRaster image, double u, double v) {
        double px = clamp(u, 0.0D, 1.0D) * (image.getWidth() - 1);
        double py = clamp(v, 0.0D, 1.0D) * (image.getHeight() - 1);
        int x0 = clamp((int)Math.floor(px), 0, image.getWidth() - 1);
        int y0 = clamp((int)Math.floor(py), 0, image.getHeight() - 1);
        int x1 = Math.min(x0 + 1, image.getWidth() - 1);
        int y1 = Math.min(y0 + 1, image.getHeight() - 1);
        double tx = px - x0;
        double ty = py - y0;

        double a = image.getSample(x0, y0, 0);
        double b = image.getSample(x1, y0, 0);
        double c = image.getSample(x0, y1, 0);
        double d = image.getSample(x1, y1, 0);
        return a * (1 - tx) * (1 - ty)
                + b * tx * (1 - ty)
                + c * (1 - tx) * ty
                + d * tx * ty;
    }

    private static GrayRaster load(String name) {
        String path = "/assets/projectjapan/terrain/" + name;
        try (InputStream in = TerrainData.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing terrain resource: " + path);
            BufferedImage image = ImageIO.read(in);
            if (image == null) throw new IllegalStateException("Unreadable terrain resource: " + path);
            return GrayRaster.copyOf(image);
        } catch (IOException e) {
            ProjectJapan.LOGGER.error("Failed to load {}", path, e);
            throw new IllegalStateException("Failed to load terrain resource " + path, e);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static long pointKey(int worldX, int worldZ) {
        return ((long)worldX << 32) ^ (worldZ & 0xffffffffL);
    }

    private static final class SampleCache {
        private final long[] keys;
        private final TerrainSample[] values;
        private final boolean[] valid;
        private final int mask;

        private SampleCache(int size) {
            if (Integer.bitCount(size) != 1) {
                throw new IllegalArgumentException("Sample cache size must be a power of two");
            }
            this.keys = new long[size];
            this.values = new TerrainSample[size];
            this.valid = new boolean[size];
            this.mask = size - 1;
        }

        private int slot(long key) {
            long mixed = key ^ (key >>> 33);
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            return ((int)mixed) & mask;
        }

        private TerrainSample get(long key) {
            int slot = slot(key);
            return valid[slot] && keys[slot] == key ? values[slot] : null;
        }

        private void put(long key, TerrainSample value) {
            int slot = slot(key);
            keys[slot] = key;
            values[slot] = value;
            valid[slot] = true;
        }
    }

    /** Flat one-band image data avoids the allocation and virtual-call overhead of Raster#getSample. */
    private static final class GrayRaster {
        private final int width;
        private final int height;
        private final byte[] bytes;
        private final short[] shorts;

        private GrayRaster(int width, int height, byte[] bytes, short[] shorts) {
            this.width = width;
            this.height = height;
            this.bytes = bytes;
            this.shorts = shorts;
        }

        private static GrayRaster copyOf(BufferedImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            Object samples = image.getRaster().getDataElements(0, 0, width, height, null);
            if (samples instanceof byte[] bytes && bytes.length == width * height) {
                return new GrayRaster(width, height, bytes, null);
            }
            if (samples instanceof short[] shorts && shorts.length == width * height) {
                return new GrayRaster(width, height, null, shorts);
            }
            throw new IllegalStateException("Terrain image must be single-band byte or unsigned short");
        }

        private int getWidth() {
            return width;
        }

        private int getHeight() {
            return height;
        }

        private int getSample(int x, int y, int band) {
            if (band != 0) throw new IllegalArgumentException("Project Japan terrain is single-band");
            int index = y * width + x;
            return bytes != null ? bytes[index] & 0xff : shorts[index] & 0xffff;
        }
    }

    public record TerrainSample(boolean land, int elevationMetres, double landCoverage) {
        public static final TerrainSample OCEAN = new TerrainSample(false, 0, 0.0D);
    }
}
