package com.sora.projectjapan.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Simplified nationwide Japanese river centre-lines for Project Japan.
 *
 * <p>The routes are intentionally low-detail and city-oriented. They are not intended for
 * navigation. Sea-bound routes grow wider toward their mouths, while tributaries terminate at configured
 * confluences or lake levels. Controlled water terraces and bank corridors keep mountain
 * headwaters contained without re-enabling vanilla caves or carving.</p>
 */
public final class RiverData {
    /** Coarse index used to avoid testing all 115 rivers for every terrain column. */
    private static final int COURSE_GRID_SIZE = 4096;
    /** Fine per-river segment index; merged per-cell query lists keep 512-block cells cheap. */
    private static final int SEGMENT_GRID_SIZE = 512;
    /** Small deterministic per-thread point cache for repeated height/decorating queries. */
    private static final int SAMPLE_CACHE_SIZE = 2048;
    /** Sub-block coverage retained when a smooth vector corridor is sampled on block columns. */
    private static final double WATER_RASTER_MARGIN = 2.0D;
    private static final int ELEVATED_SOURCE_LAKE_BLOCKS = (int)Math.round(
            96.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    private static final int SOURCE_LAKE_TRANSITION_DROP_BLOCKS = (int)Math.round(
            32.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    private static final int SOURCE_GRADE_HARD_LIMIT_BLOCKS = (int)Math.ceil(
            96.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    /**
     * CF-01/04: a high-resolution shoreline can briefly leave and re-enter the same lake mask
     * where a narrow peninsula, raster edge or one-block neck crosses the frozen RiverCourse.
     * Treat only short same-lake gaps as one hydraulic reach; this changes no LR/AR geometry.
     */
    private static final double CF_SAME_LAKE_FRAGMENT_GAP = 128.0D;
    /** Fix4: shoreline topology only deglitches one/two 8-block raster samples; hydraulic merge stays 128..192. */
    private static final double CF_SHORELINE_GATE_DEGLITCH_GAP = 16.0D;

    /*
     * High-altitude source protection, distilled from the useful part of the experimental
     * tellus-overlay branch.  That branch clamped every generated river column to the DEM and
     * therefore allowed the terrain raster to make water run uphill.  Here the same shallow-source
     * visual limits are applied once while building the headwater longitudinal profile; the final
     * water surface remains one monotonic RiverData profile and is never re-fit per column.
     */
    private static final int HIGH_HEADWATER_MIN_NATURAL_Y = PJChunkGenerator.SEA_LEVEL
            + (int)Math.round(192.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    private static final int HEADWATER_PREFERRED_SURFACE_CUT = 2;
    private static final int HEADWATER_MAX_WET_TERRAIN_CUT = 4;
    private static final int HEADWATER_MAX_DRY_BANK_CUT = 3;
    private static final double HEADWATER_MIN_PROFILE_LENGTH = 384.0D;
    private static final double HEADWATER_MAX_PROFILE_LENGTH = 6144.0D;
    /** Look ahead far enough to spread an approaching steep descent before it becomes a wall. */
    private static final double HEADWATER_GRADE_LOOKAHEAD = 640.0D;
    /** Prefer to rejoin the ordinary profile only after it is again reasonably close to terrain. */
    private static final int HEADWATER_MERGE_MAX_CENTRE_CUT = 18;
    /** A source-profile water terrace may sit at most this far below the local centre-line terrain
     * before mountain carving, not terrain-following, becomes the intended behaviour. */
    private static final int HEADWATER_GRADE_TERRAIN_CLEARANCE = 1;
    private static final double HEADWATER_VISUAL_GUARD_LENGTH = 640.0D;
    private static final double HEADWATER_BANK_MARGIN = 32.0D;

    /*
     * Whole-river terrain fitting. These limits borrow Tellus' useful principle that the river
     * profile, terrain cut and shoreline ramp are separate concerns, but RiverData remains the
     * sole owner of water Y. DEM samples are consulted while the longitudinal profile is built and
     * while dry banks are shaped; they are never allowed to raise a generated water column.
     */
    private static final int RIVER_PROFILE_PREFERRED_MAX_CUT = 4;
    private static final int RIVER_PROFILE_EMERGENCY_MAX_CUT = 12;
    private static final int RIVER_MAX_TERRAIN_AWARE_DROP = 6;
    private static final double RIVER_BANK_MIN_SLOPE = 0.26D;
    private static final double RIVER_BANK_MAX_SLOPE = 0.64D;
    private static final double RIVER_BANK_MIN_MARGIN = 24.0D;
    private static final double RIVER_BANK_MAX_MARGIN = 2048.0D;
    private static final double RIVER_BANK_BLEND_EXTRA = 20.0D;
    /** Any incision beyond this is treated as an actual mountain cut and must be blended. */
    private static final double RIVER_CARVE_BLEND_START = 5.0D;
    /** Deep cuts get progressively gentler rather than progressively steeper. */
    private static final double RIVER_DEEP_CARVE_FULL = 72.0D;
    /*
     * Tight mountain bends can put two very different hydraulic terraces almost the same
     * world-space distance from a bank column.  Dry-bank terrain therefore gets a small 2-D
     * spatial low-pass, while the actual water surface remains the monotonic RiverData profile.
     */

    private static final List<RiverCourse> COURSES = buildCourses();
    private static final Map<Long, List<RiverCourse>> COURSE_GRID = buildCourseGrid(COURSES);
    private static final ThreadLocal<SampleCache> SAMPLE_CACHE =
            ThreadLocal.withInitial(() -> new SampleCache(SAMPLE_CACHE_SIZE));

    private RiverData() {}

    /**
     * First nationwide hydrology expansion. The centre-lines are simplified at PJ's 1:8
     * horizontal scale: major first-class systems, important urban rivers and selected tributaries
     * are retained, while tiny headwater branches are intentionally omitted.
     */
    private static List<RiverCourse> buildCourses() {
        List<RiverCourse> rivers = new ArrayList<>();

        // Hokkaido
        // P1.1: the former coarse Ishikari chord missed the Sorachi W05 junction by more than
        // 4,000 PJ blocks.  Use W05 main-stem samples and the two protected branch nodes rather
        // than rubber-sheeting a long authored reach into each confluence.
        rivers.add(seaCourse("Ishikari", 920, 10, 185, 3,
                point(142.9780325, 43.5678047), point(142.2172824, 43.7267043),
                point(141.8962657, 43.5250874), point(141.7158927, 43.3508569),
                point(141.6593448, 43.2519059), point(141.4485056, 43.1543212),
                point(141.3831408, 43.2139946), point(141.3702954, 43.2713888)));
        rivers.add(seaCourse("Tokachi", 850, 9, 145, 3,
                point(142.89, 43.35), point(143.05, 43.10), point(143.22, 42.95),
                point(143.45, 42.80), point(143.65, 42.69)));
        rivers.add(seaCourse("Teshio", 620, 8, 120, 2,
                point(142.35, 44.27), point(142.10, 44.05), point(141.90, 44.00),
                point(141.75, 44.15), point(141.67, 44.45)));
        // From Lake Kussharo the Kushiro River runs through Teshikaga and Shibecha, then turns
        // south-west along the eastern side of the Kushiro wetland. The former line bent west into
        // Lake Akan's support corridor and created an unrelated high-lake/river cut.
        rivers.add(seaCourse("Kushiro", 121, 8, 125, 2,
                point(144.34, 43.61), point(144.44, 43.50), point(144.52, 43.35),
                point(144.60, 43.22), point(144.50, 43.08), point(144.38, 42.98)));
        rivers.add(seaCourse("Abashiri", 0, 10, 85, 1,
                point(144.17, 43.96), point(144.21, 44.02), point(144.28, 44.02)));
        rivers.add(seaCourse("Tokoro", 720, 6, 95, 2,
                point(143.45, 43.65), point(143.65, 43.80), point(143.86, 44.00),
                point(144.08, 44.12)));
        rivers.add(seaCourse("Yubetsu", 850, 6, 92, 2,
                point(143.10, 43.55), point(143.28, 43.82), point(143.42, 44.05),
                point(143.62, 44.22)));
        // Shokotsu rises at Mt Teshio and then descends north-east through Takinoue toward
        // Monbetsu. The former first point was about 45 km too far south and crossed the Ishikari
        // headwater, creating a 190-block water-level seam between unrelated rivers.
        rivers.add(seaCourse("Shokotsu", 1558, 6, 86, 2,
                point(142.92, 43.96), point(143.00, 44.07), point(143.10, 44.17),
                point(143.20, 44.25), point(143.35, 44.32)));
        rivers.add(seaCourse("Shiribetsu", 900, 6, 90, 2,
                point(140.70, 42.90), point(140.58, 42.78), point(140.42, 42.76),
                point(140.25, 42.80)));
        rivers.add(seaCourse("Mu", 920, 6, 82, 2,
                point(142.60, 42.80), point(142.36, 42.68), point(142.15, 42.60),
                point(141.90, 42.55)));
        rivers.add(seaCourse("Saru", 1050, 6, 86, 2,
                point(142.75, 42.85), point(142.55, 42.70), point(142.30, 42.61),
                point(142.05, 42.50)));
        rivers.add(tributaryCourse("Sorachi", 900, 20, 6, 90, 2,
                point(142.70, 43.20), point(142.40, 43.15), point(142.10, 43.18),
                point(141.85, 43.19)));
        rivers.add(tributaryCourse("Toyohira", 700, 8, 5, 58, 2,
                point(141.20, 42.85), point(141.30, 43.00), point(141.42, 43.12),
                point(141.45, 43.20)));

        // Tohoku
        rivers.add(seaCourse("Mabechi", 780, 6, 105, 2,
                point(141.25, 40.13), point(141.25, 40.28), point(141.38, 40.45),
                point(141.50, 40.55)));
        rivers.add(seaCourse("Takase", 420, 5, 72, 2,
                point(141.08, 40.70), point(141.20, 40.78), point(141.35, 40.82)));
        rivers.add(tributaryCourse("Iwaki", 680, 0, 6, 98, 2,
                point(140.14, 40.52), point(140.30, 40.58), point(140.47, 40.63),
                point(140.46, 40.75), point(140.43, 40.85), point(140.38, 40.96),
                point(140.36, 41.02)));
        rivers.add(seaCourse("Yoneshiro", 850, 7, 120, 2,
                point(140.90, 40.10), point(140.62, 40.08), point(140.38, 40.15),
                point(140.14, 40.19), point(139.98, 40.20)));
        rivers.add(seaCourse("Omono", 900, 7, 120, 2,
                point(140.80, 39.45), point(140.58, 39.56), point(140.34, 39.66),
                point(140.12, 39.76), point(140.00, 39.82)));
        rivers.add(seaCourse("Koyoshi", 620, 5, 82, 2,
                point(140.25, 39.18), point(140.12, 39.25), point(140.02, 39.30)));
        // P1.1: these two neighbouring but independent W05 systems used to be represented by
        // diagonal coarse chords which crossed twice around Shonai.  Keep them authored, but use
        // W05-derived control points for the problematic lower/middle routes so their physical
        // masks remain independent without any hydraulic "reconciliation" hack.
        rivers.add(seaCourse("Mogami", 720, 7, 112, 2,
                point(140.12908, 37.83903), point(140.12186, 37.93086),
                point(140.04706, 38.07996), point(140.04671, 38.11207),
                point(140.10280, 38.20045), point(140.11778, 38.26785),
                point(140.13167, 38.29846), point(140.19879, 38.34940),
                point(140.21780, 38.38228), point(140.33182, 38.40808),
                point(140.34760, 38.47742), point(140.36340, 38.52472),
                point(140.38481, 38.56342), point(140.33384, 38.62555),
                point(140.33128, 38.66847), point(140.22488, 38.70233),
                point(140.22535, 38.72965), point(140.13050, 38.74190),
                point(140.07771, 38.75053), point(140.02889, 38.78187),
                point(139.98523, 38.81306), point(139.93418, 38.87385),
                point(139.83681, 38.89399), point(139.80795, 38.92189)));
        rivers.add(seaCourse("Aka", 1771, 5, 85, 3,
                point(139.83038, 38.36595), point(139.81824, 38.37786),
                point(139.80158, 38.40016), point(139.81252, 38.42593),
                point(139.78146, 38.46074), point(139.75986, 38.48577),
                point(139.78874, 38.50945), point(139.80885, 38.54288),
                point(139.82850, 38.58927), point(139.84257, 38.62364),
                point(139.86068, 38.67534), point(139.85396, 38.76659),
                point(139.81526, 38.84043), point(139.78361, 38.84793)));
        rivers.add(seaCourse("Kitakami", 760, 8, 135, 2,
                point(141.16, 39.98), point(141.13, 39.85), point(141.15, 39.45),
                point(141.15, 39.10), point(141.20, 38.75), point(141.30, 38.42)));
        rivers.add(seaCourse("Naruse", 650, 6, 92, 2,
                point(140.62, 38.72), point(140.78, 38.58), point(141.00, 38.46),
                point(141.15, 38.40)));
        rivers.add(seaCourse("Natori", 760, 6, 96, 2,
                point(140.50, 38.25), point(140.68, 38.22), point(140.88, 38.20),
                point(141.03, 38.17)));
        rivers.add(seaCourse("Abukuma", 680, 7, 112, 2,
                point(140.18, 37.15), point(140.37, 37.35), point(140.54, 37.58),
                point(140.73, 37.80), point(140.89, 38.04)));

        // Kanto
        // P1.1: W05-derived Kanto main-stem controls make every protected branch node part of
        // the route itself.  This replaces P1's kilometre-scale local displacement around Kinu,
        // Kokai, Watarase and Karasu and prevents the repair kinks from crossing the tributaries.
        rivers.add(seaCourse("Tone", 1050, 12, 190, 3,
                point(139.0498352, 36.8375168), point(139.0216980, 36.6512184),
                point(139.0175629, 36.5013466), point(139.0576172, 36.3948937),
                point(139.1835253, 36.2655778), point(139.4078980, 36.2241325),
                point(139.6987553, 36.1512512), point(139.7900172, 36.1011821),
                point(139.9517338, 35.9362991), point(140.1273658, 35.8696080),
                point(140.3730928, 35.9017808), point(140.6022514, 35.8736913),
                point(140.6377633, 35.8571610), point(140.6714970, 35.8516269),
                point(140.7084551, 35.8344471), point(140.7225114, 35.8100941),
                point(140.7416555, 35.7854533), point(140.7769510, 35.7653149),
                point(140.8015640, 35.7494719), point(140.8401460, 35.7392103)));
        // Edo is a distributary of Tone, not an unrelated seaCourse which happens to cross it.
        // The W05-derived route below starts at the branch node and stays on its own downstream
        // corridor; resolveConnections() attaches the first point to Tone with BifurcationAnchor.
        rivers.add(seaCourse("Edo", 65, 24, 125, 1,
                point(139.77634, 36.09614), point(139.77993, 36.08007),
                point(139.79250, 36.05725), point(139.81754, 36.03042),
                point(139.81641, 36.00116), point(139.83494, 35.96117),
                point(139.87184, 35.91589), point(139.89892, 35.86749),
                point(139.89119, 35.81556), point(139.88271, 35.76173),
                point(139.89848, 35.72366), point(139.94477, 35.67954)));
        // P1.1: preserve the authored upper-Arakawa approach (including the permanent
        // high-mountain rapid regression near -11344,-3301), then join the connected W05
        // main-stem component for the Iruma junction and lower river.  Replacing the whole
        // main stem moved the upper river about 1000 blocks away from its validated corridor,
        // which was a 2-D geometry regression rather than a bank/rapid-edge problem.
        rivers.add(seaCourse("Arakawa", 2475, 5, 150, 4,
                point(138.738, 35.906), point(138.790, 35.930),
                point(138.8481706, 35.9375322), point(138.9087022, 35.9406542),
                point(138.9634128, 35.9513519), point(139.0266100, 35.9594597),
                point(139.0683060, 36.0005469), point(139.0857842, 36.0291592),
                point(139.0862875, 36.0678386), point(139.1129994, 36.1045564),
                point(139.1305494, 36.1337950), point(139.1560675, 36.1240667),
                point(139.2040547, 36.1162867), point(139.2529173, 36.1200928),
                point(139.2970603, 36.1343422), point(139.3398719, 36.1386664),
                point(139.3855969, 36.1339489), point(139.4215064, 36.1081589),
                point(139.4476642, 36.0838528), point(139.4767919, 36.0600497),
                point(139.5050089, 36.0276219), point(139.5203533, 35.9816578),
                point(139.5414425, 35.9358133), point(139.5467303, 35.9087892),
                point(139.5600022, 35.8991372), point(139.5879356, 35.8582758),
                point(139.6216878, 35.8116925), point(139.6745480, 35.7990673),
                point(139.6944417, 35.8000327), point(139.7200000, 35.8200000),
                point(139.8200000, 35.7200000), point(139.8300000, 35.6300000)));
        rivers.add(seaCourse("Sumida", 18, 28, 64, 1,
                point(139.72, 35.79), point(139.74, 35.75), point(139.76, 35.71),
                point(139.78, 35.67), point(139.80, 35.64)));
        rivers.add(seaCourse("Tama", 900, 7, 110, 2,
                point(138.83, 35.82), point(139.02, 35.79), point(139.25, 35.74),
                point(139.45, 35.66), point(139.65, 35.58), point(139.76, 35.53)));
        rivers.add(seaCourse("Sagami", 981, 7, 90, 2,
                point(138.87, 35.42), point(138.94, 35.50), point(139.05, 35.58),
                point(139.18, 35.61), point(139.23, 35.54), point(139.28, 35.45),
                point(139.34, 35.36), point(139.37, 35.32)));
        rivers.add(seaCourse("Naka", 980, 7, 118, 2,
                point(140.10, 36.95), point(140.20, 36.72), point(140.36, 36.50),
                point(140.50, 36.36), point(140.60, 36.30)));
        rivers.add(seaCourse("Kuji", 720, 6, 88, 2,
                point(140.50, 36.85), point(140.58, 36.65), point(140.62, 36.55)));
        rivers.add(seaCourse("Tsurumi", 120, 5, 62, 1,
                point(139.48, 35.60), point(139.57, 35.55), point(139.65, 35.52),
                point(139.69, 35.49)));
        rivers.add(tributaryCourse("Kinu", 1800, 18, 7, 112, 3,
                point(139.39, 36.87), point(139.54, 36.83), point(139.68, 36.72),
                point(139.70, 36.55), point(139.76, 36.38), point(139.88, 36.18),
                point(139.90, 36.10)));
        rivers.add(tributaryCourse("Kokai", 500, 10, 5, 72, 2,
                point(140.05, 36.55), point(140.04, 36.38), point(140.00, 36.15)));
        rivers.add(tributaryCourse("Watarase", 1100, 24, 6, 82, 2,
                point(139.42, 36.60), point(139.55, 36.42), point(139.70, 36.20)));
        rivers.add(tributaryCourse("Agatsuma", 1800, 150, 5, 74, 3,
                point(138.55, 36.65), point(138.78, 36.60), point(139.02, 36.50)));
        rivers.add(tributaryCourse("Karasu", 1050, 80, 5, 68, 2,
                point(138.78, 36.36), point(139.00, 36.31), point(139.20, 36.28)));
        rivers.add(tributaryCourse("Iruma", 800, 15, 5, 58, 2,
                point(139.05, 35.95), point(139.25, 35.92), point(139.45, 35.90)));
        rivers.add(tributaryCourse("Asakawa", 650, 42, 4, 44, 2,
                point(139.12, 35.72), point(139.25, 35.68), point(139.38, 35.66)));

        // Shinetsu and Hokuriku
        rivers.add(seaCourse("Shinano", 2475, 5, 165, 4,
                point(138.716, 35.916), point(138.700, 35.950), point(138.650, 35.995),
                point(138.590, 36.040), point(138.540, 36.100), point(138.500, 36.180),
                point(138.480, 36.220), point(138.250, 36.405), point(138.180, 36.650),
                point(138.355, 36.855), point(138.575, 37.100), point(138.780, 37.320),
                point(138.850, 37.500),
                point(138.892291, 37.622943), point(138.929539, 37.631280),
                point(138.955183, 37.650130), point(138.968886, 37.663544),
                point(139.007854, 37.677259), point(139.034596, 37.704656),
                point(139.058363, 37.725087), point(139.067143, 37.752899),
                point(139.070634, 37.774879), point(139.064263, 37.803326),
                point(139.055067, 37.839269), point(139.022560, 37.855049),
                point(139.021160, 37.878232), point(139.024954, 37.906331),
                point(139.048472, 37.914826), point(139.067003, 37.947652)));
        rivers.add(seaCourse("Agano", 950, 8, 135, 2,
                point(139.82, 37.55), point(139.62, 37.68),
                point(139.367632, 37.720240), point(139.343926, 37.728401),
                point(139.307861, 37.736416), point(139.279387, 37.737017),
                point(139.240749, 37.745782), point(139.211112, 37.770431),
                point(139.185264, 37.782081), point(139.169158, 37.806308),
                point(139.160673, 37.829081), point(139.161886, 37.855125),
                point(139.174614, 37.867476), point(139.150911, 37.900874),
                point(139.144914, 37.932380), point(139.130511, 37.957887)));
        // Seki and Shinano are different W05 water systems.  The old three-point Seki chord cut
        // across Shinano near (-15350,-17125).  This local W05 route keeps the two masks separate.
        rivers.add(seaCourse("Seki", 1100, 5, 78, 2,
                point(138.10206, 36.84327), point(138.11853, 36.84869),
                point(138.20131, 36.85619), point(138.20953, 36.86610),
                point(138.21414, 36.88006), point(138.21797, 36.89757),
                point(138.22816, 36.91400), point(138.26622, 36.95251),
                point(138.26246, 37.00819), point(138.27909, 37.08704),
                point(138.24544, 37.14090), point(138.24582, 37.18337)));
        rivers.add(seaCourse("Hime", 800, 5, 76, 2,
                point(137.85, 36.65), point(137.86, 36.74), point(137.86, 36.84),
                point(137.86, 36.94), point(137.86, 37.04)));
        rivers.add(seaCourse("Kurobe", 2400, 5, 82, 4,
                point(137.70, 36.60), point(137.60, 36.75), point(137.50, 36.88),
                point(137.45, 36.95)));
        rivers.add(seaCourse("Joganji", 2200, 5, 80, 4,
                point(137.55, 36.55), point(137.43, 36.65), point(137.30, 36.73),
                point(137.25, 36.78)));
        rivers.add(seaCourse("Jinzu", 1900, 6, 105, 3,
                point(137.25, 36.15), point(137.22, 36.40), point(137.18, 36.62),
                point(137.15, 36.75)));
        rivers.add(seaCourse("Sho", 1700, 6, 82, 3,
                point(136.95, 36.10), point(136.96, 36.38),
                point(137.004313, 36.642203), point(137.007146, 36.654748),
                point(137.006231, 36.673199), point(137.005016, 36.679409),
                point(137.005676, 36.687658), point(137.014196, 36.703954),
                point(137.025461, 36.714768), point(137.029927, 36.720781),
                point(137.042335, 36.733957), point(137.046278, 36.745531),
                point(137.052757, 36.758532), point(137.055593, 36.769640),
                point(137.061633, 36.779729), point(137.076465, 36.789685)));
        rivers.add(seaCourse("Oyabe", 1572, 6, 78, 3,
                point(136.77, 36.35), point(136.82, 36.50),
                point(136.890535, 36.627709), point(136.874775, 36.645431),
                point(136.869456, 36.665737), point(136.886672, 36.690970),
                point(136.893061, 36.697136), point(136.920146, 36.710590),
                point(136.940764, 36.723773), point(136.958663, 36.741271),
                point(136.977111, 36.747473), point(136.978983, 36.764536),
                point(137.006478, 36.766879), point(137.031428, 36.772146),
                point(137.053084, 36.783917), point(137.067736, 36.794205)));
        rivers.add(seaCourse("Tedori", 2100, 5, 88, 4,
                point(136.70, 36.15), point(136.60, 36.28), point(136.50, 36.42),
                point(136.45, 36.50)));
        rivers.add(seaCourse("Kuzuryu", 1400, 6, 105, 3,
                point(136.50, 35.85), point(136.35, 36.00), point(136.20, 36.10),
                point(136.10, 36.15)));
        rivers.add(tributaryCourse("Sai", 1800, 350, 5, 78, 3,
                point(137.65, 36.30), point(137.85, 36.43), point(138.05, 36.55),
                point(138.20, 36.65)));
        rivers.add(tributaryCourse("Uono", 1600, 45, 5, 80, 3,
                point(139.05, 36.85), point(138.95, 37.05), point(138.88, 37.25),
                point(138.85, 37.38)));

        // Chubu and Kinki
        rivers.add(seaCourse("Fuji", 980, 7, 105, 3,
                point(138.36, 35.86), point(138.45, 35.65), point(138.55, 35.42),
                point(138.62, 35.20), point(138.65, 35.12)));
        rivers.add(seaCourse("Kano", 1000, 5, 82, 3,
                point(138.99, 34.85), point(138.95, 34.92), point(138.93, 35.00),
                point(138.91, 35.07), point(138.88, 35.11)));
        rivers.add(seaCourse("Abe", 1800, 5, 86, 3,
                point(138.35, 35.30), point(138.38, 35.15), point(138.40, 34.95)));
        rivers.add(seaCourse("Oi", 2200, 5, 98, 4,
                point(138.15, 35.35), point(138.12, 35.12), point(138.16, 34.92),
                point(138.22, 34.78)));
        rivers.add(seaCourse("Kiku", 900, 5, 75, 2,
                point(138.05, 34.95), point(138.04, 34.82), point(138.05, 34.70)));
        rivers.add(seaCourse("Tenryu", 759, 8, 120, 2,
                point(138.0555, 36.0462), point(138.047, 36.039), point(138.00, 35.91), point(137.98, 35.78), point(137.93, 35.45),
                point(137.85, 35.15), point(137.82, 34.85), point(137.78, 34.64)));
        rivers.add(seaCourse("Toyo", 1100, 5, 90, 2,
                point(137.50, 35.05), point(137.48, 34.88), point(137.42, 34.75),
                point(137.35, 34.68)));
        rivers.add(seaCourse("Yahagi", 1200, 6, 105, 3,
                point(137.40, 35.25), point(137.28, 35.15), point(137.14, 35.03),
                point(137.05, 34.90)));
        rivers.add(seaCourse("Shonai", 850, 5, 85, 2,
                point(137.10, 35.40), point(137.02, 35.25), point(136.92, 35.12),
                point(136.85, 35.05)));
        rivers.add(seaCourse("Kiso", 1250, 8, 145, 3,
                point(137.62, 35.83), point(137.60, 35.66), point(137.45, 35.55),
                // P1.1 keeps the Hida confluence itself pinned to the exact W05 anchor below via
                // snapRouteThrough().  This nearby 2-D staging control is deliberately ~68 m
                // south of that node: it preserves the W05 lower-Kiso shape while preventing the
                // longer official splice from shifting two pre-existing one-block terrace
                // boundaries close enough for their falling-water connectors to overlap.  No
                // vertical profile / DropScheduler rule is changed here.
                point(137.25, 35.50), point(137.15, 35.44), point(137.05233, 35.44450),
                point(137.0348, 35.4284),
                point(137.006704, 35.430055), point(136.976612, 35.422268),
                point(136.932656, 35.386100), point(136.899508, 35.377727),
                point(136.855307, 35.373580), point(136.801589, 35.368995),
                point(136.760165, 35.358552), point(136.751812, 35.340238),
                point(136.729297, 35.301571), point(136.701894, 35.271836),
                point(136.678693, 35.232563), point(136.681467, 35.197543),
                point(136.681472, 35.158982), point(136.687000, 35.132560),
                point(136.713200, 35.104261), point(136.724157, 35.069470),
                point(136.746378, 35.031842)));
        rivers.add(seaCourse("Nagara", 950, 7, 105, 2,
                point(136.80, 35.95), point(136.72, 35.62),
                point(136.690299, 35.366029), point(136.689341, 35.341199),
                point(136.671233, 35.320991), point(136.669696, 35.298549),
                point(136.672382, 35.269314), point(136.667080, 35.250380),
                point(136.673815, 35.224796), point(136.675006, 35.198141),
                point(136.671328, 35.174817), point(136.671203, 35.142962),
                point(136.676544, 35.123884), point(136.682112, 35.096457),
                point(136.698330, 35.077911), point(136.709482, 35.054975)));
        rivers.add(seaCourse("Ibi", 860, 6, 92, 2,
                point(136.52, 35.65), point(136.49, 35.40),
                point(136.628702, 35.189568), point(136.629806, 35.187923),
                point(136.646943, 35.165076), point(136.656819, 35.155892),
                point(136.665272, 35.142884), point(136.670436, 35.128936),
                point(136.666062, 35.115781), point(136.672541, 35.098744),
                point(136.683637, 35.089312), point(136.692422, 35.076735),
                point(136.702718, 35.064852), point(136.709482, 35.054975),
                point(136.712099, 35.041849), point(136.717869, 35.025661)));
        rivers.add(seaCourse("Suzuka", 750, 5, 74, 2,
                point(136.45, 35.05), point(136.55, 35.00), point(136.65, 34.95)));
        rivers.add(seaCourse("Kumozu", 850, 5, 82, 2,
                point(136.25, 34.80), point(136.40, 34.74), point(136.55, 34.70)));
        rivers.add(seaCourse("Kushida", 900, 5, 82, 2,
                point(136.15, 34.55), point(136.38, 34.56), point(136.65, 34.55)));
        rivers.add(seaCourse("Miya", 820, 5, 76, 2,
                point(136.45, 34.35), point(136.58, 34.42), point(136.75, 34.48)));
        rivers.add(seaCourse("Yodo", 84, 24, 155, 1,
                point(135.96187, 35.02741), point(135.906, 34.982), point(135.91, 34.95), point(135.84, 34.94), point(135.78, 34.88),
                point(135.70, 34.82), point(135.61, 34.78), point(135.50, 34.72),
                point(135.42, 34.67)));
        rivers.add(seaCourse("Yura", 959, 5, 92, 2,
                point(135.66, 35.29), point(135.50, 35.25), point(135.30, 35.29),
                point(135.12, 35.30), point(135.17, 35.42), point(135.25, 35.49),
                point(135.28, 35.52)));
        rivers.add(seaCourse("Kakogawa", 850, 6, 100, 2,
                point(134.90, 35.15), point(134.87, 34.98), point(134.83, 34.84),
                point(134.80, 34.75)));
        rivers.add(seaCourse("Ibo", 1139, 5, 82, 3,
                point(134.61, 35.25), point(134.58, 35.10), point(134.55, 34.96),
                point(134.57, 34.84), point(134.58, 34.75)));
        rivers.add(seaCourse("Kino", 1200, 6, 112, 3,
                point(135.85, 34.20), point(135.60, 34.22), point(135.38, 34.25),
                point(135.15, 34.25)));
        rivers.add(seaCourse("Yamato", 650, 6, 90, 2,
                point(135.75, 34.60), point(135.60, 34.57), point(135.48, 34.55),
                point(135.43, 34.58)));
        rivers.add(tributaryCourse("Hida", 2200, 150, 5, 84, 4,
                point(137.55, 36.10), point(137.28, 36.12), point(137.20, 35.88),
                point(137.15, 35.65), point(137.10, 35.52), point(137.055, 35.47),
                point(137.0348, 35.4284)));
        rivers.add(tributaryCourse("Katsura", 900, 12, 5, 72, 2,
                point(135.65, 35.25), point(135.62, 35.05), point(135.60, 34.88),
                point(135.70, 34.80)));
        rivers.add(tributaryCourse("Kizu", 850, 12, 5, 68, 2,
                point(135.90, 34.70), point(135.82, 34.76), point(135.75, 34.82),
                point(135.70, 34.85)));

        // Chugoku
        rivers.add(seaCourse("Sendai-Tottori", 1319, 5, 86, 3,
                point(134.05, 35.25), point(134.10, 35.36), point(134.16, 35.46),
                point(134.20, 35.55)));
        rivers.add(seaCourse("Gono", 1000, 6, 108, 3,
                point(132.55, 34.78), point(132.42, 34.90), point(132.30, 34.98),
                point(132.22, 35.02)));
        rivers.add(seaCourse("Takatsu", 850, 5, 82, 2,
                point(131.90, 34.65), point(131.82, 34.67), point(131.75, 34.68)));
        rivers.add(seaCourse("Yoshii", 950, 6, 102, 2,
                point(134.10, 35.05), point(134.05, 34.90), point(134.00, 34.76),
                point(134.05, 34.65)));
        rivers.add(seaCourse("Asahi", 1050, 6, 105, 2,
                point(133.90, 35.15), point(133.88, 34.98), point(133.86, 34.80),
                point(133.95, 34.65)));
        rivers.add(seaCourse("Takahashi", 1100, 6, 108, 3,
                point(133.55, 35.10), point(133.54, 34.92), point(133.58, 34.72),
                point(133.70, 34.55)));
        rivers.add(seaCourse("Ashida", 700, 5, 78, 2,
                point(133.35, 34.70), point(133.34, 34.60), point(133.35, 34.50)));
        rivers.add(seaCourse("Ota", 900, 6, 98, 2,
                point(132.45, 34.65), point(132.43, 34.52), point(132.40, 34.40)));
        rivers.add(seaCourse("Oze", 700, 5, 78, 2,
                point(132.15, 34.35), point(132.18, 34.20), point(132.20, 34.10)));
        rivers.add(seaCourse("Saba", 650, 5, 76, 2,
                point(131.60, 34.35), point(131.60, 34.18), point(131.60, 34.05)));
        rivers.add(tributaryCourse("Hii", 750, 0, 5, 44, 2,
                point(133.05, 35.25), point(132.98, 35.34), point(132.91, 35.40),
                point(132.88, 35.432)));
        // Ohashi is a low-gradient connecting channel between Lake Shinji and Nakaumi, not a
        // major 500 m-wide river trench. Keep a city-scale but geographically plausible width.
        rivers.add(tributaryCourse("Ohashi", 0, 0, 14, 32, 1,
                point(133.00, 35.46), point(133.06, 35.46), point(133.132, 35.474)));

        // Shikoku
        rivers.add(seaCourse("Yoshino", 980, 8, 130, 3,
                point(133.52, 33.76), point(133.75, 33.82), point(134.00, 33.88),
                point(134.25, 33.95), point(134.45, 34.02), point(134.58, 34.08)));
        rivers.add(seaCourse("Naka-Tokushima", 1050, 6, 96, 3,
                point(134.10, 33.75), point(134.25, 33.86), point(134.42, 33.97),
                point(134.58, 34.05)));
        rivers.add(seaCourse("Doki", 700, 5, 70, 2,
                point(133.80, 34.10), point(133.78, 34.18), point(133.75, 34.25)));
        rivers.add(seaCourse("Shigenobu", 850, 5, 80, 2,
                point(132.95, 33.85), point(132.82, 33.84), point(132.70, 33.82)));
        rivers.add(seaCourse("Hiji", 460, 5, 76, 2,
                point(132.80, 33.60), point(132.72, 33.57), point(132.68, 33.55),
                point(132.60, 33.56), point(132.52, 33.54), point(132.45, 33.52),
                point(132.40, 33.54)));
        rivers.add(seaCourse("Shimanto", 520, 7, 92, 2,
                point(133.05, 33.45), point(132.95, 33.20), point(132.87, 33.05),
                point(132.78, 32.94), point(132.93, 32.91)));

        // Kyushu
        rivers.add(seaCourse("Onga", 750, 6, 95, 2,
                point(130.80, 33.75), point(130.74, 33.82), point(130.70, 33.85)));
        rivers.add(seaCourse("Yamakuni", 850, 5, 82, 2,
                point(131.00, 33.50), point(131.08, 33.55), point(131.15, 33.60)));
        rivers.add(seaCourse("Oita", 900, 5, 86, 2,
                point(131.30, 33.10), point(131.45, 33.18), point(131.60, 33.25)));
        rivers.add(seaCourse("Ono", 900, 5, 90, 2,
                point(131.45, 32.95), point(131.60, 33.03), point(131.75, 33.10)));
        rivers.add(seaCourse("Banjo", 950, 5, 82, 2,
                point(131.55, 32.85), point(131.72, 32.90), point(131.90, 32.95)));
        rivers.add(seaCourse("Gokase", 1200, 5, 92, 3,
                point(131.15, 32.75), point(131.35, 32.68), point(131.52, 32.62),
                point(131.65, 32.60)));
        rivers.add(seaCourse("Oyodo", 1050, 6, 108, 3,
                point(130.95, 32.10), point(131.10, 32.02), point(131.28, 31.95),
                point(131.45, 31.90)));
        rivers.add(seaCourse("Sendai-Kagoshima", 900, 6, 102, 2,
                point(130.60, 32.10), point(130.48, 31.98), point(130.36, 31.88),
                point(130.25, 31.84), point(130.18, 31.84)));
        rivers.add(seaCourse("Kimotsuki", 700, 5, 76, 2,
                point(130.90, 31.30), point(130.95, 31.27), point(130.98, 31.25)));
        // The old Matsuura mouth was ~13.5 km from W05 and its authored inland chord crossed
        // open strait before re-landing.  Use the W05 main-stem path through the real coast; the
        // generic offshore extension still begins only after this final mouth point.
        rivers.add(seaCourse("Matsuura", 650, 5, 76, 2,
                point(129.91218, 33.21830), point(129.92698, 33.20616),
                point(129.96424, 33.22899), point(129.97053, 33.24364),
                point(129.96640, 33.25835), point(129.96944, 33.29177),
                point(129.97370, 33.32568), point(129.99319, 33.34603),
                point(130.00617, 33.36416), point(129.98261, 33.39927),
                point(129.98757, 33.41574), point(129.97967, 33.45064)));
        rivers.add(seaCourse("Rokkaku", 420, 6, 96, 1,
                point(130.25, 33.20), point(130.18, 33.17), point(130.10, 33.15)));
        rivers.add(seaCourse("Chikugo", 620, 8, 135, 2,
                point(131.17, 33.15), point(130.97, 33.23), point(130.73, 33.19),
                point(130.55, 33.13), point(130.35, 33.10)));
        rivers.add(seaCourse("Kikuchi", 850, 5, 86, 2,
                point(130.90, 32.95),
                point(130.779196, 32.954293), point(130.758027, 32.959820),
                point(130.733041, 32.984697), point(130.709629, 32.994962),
                point(130.674506, 33.016885), point(130.646630, 33.005939),
                point(130.626243, 33.023308), point(130.602532, 33.013027),
                point(130.600505, 32.991645), point(130.595317, 32.965523),
                point(130.584846, 32.935086), point(130.563136, 32.924724),
                point(130.539512, 32.899265), point(130.525315, 32.878966)));
        rivers.add(seaCourse("Midori", 1000, 5, 94, 3,
                point(131.00, 32.65), point(130.82, 32.66), point(130.65, 32.65),
                point(130.55, 32.65)));
        rivers.add(seaCourse("Shira", 1100, 5, 90, 3,
                point(131.00, 32.85),
                point(130.814748, 32.852937), point(130.794908, 32.852529),
                point(130.772341, 32.845367), point(130.761654, 32.834578),
                point(130.759342, 32.826082), point(130.731555, 32.813498),
                point(130.716400, 32.803817), point(130.699112, 32.792192),
                point(130.693724, 32.782612), point(130.681411, 32.770999),
                point(130.660893, 32.770154), point(130.640346, 32.771442),
                point(130.622326, 32.775632), point(130.602849, 32.780405)));
        rivers.add(seaCourse("Honmyo", 1057, 5, 72, 3,
                point(130.12, 32.92), point(130.10, 32.88), point(130.08, 32.86),
                point(130.045, 32.852), point(130.00, 32.84)));
        rivers.add(seaCourse("Kuma", 660, 7, 105, 2,
                point(130.95, 32.30), point(130.80, 32.28), point(130.64, 32.34),
                point(130.54, 32.49)));
        rivers.add(tributaryCourse("Kusu", 900, 22, 5, 70, 2,
                point(131.15, 33.30), point(131.00, 33.28), point(130.85, 33.25)));

        resolveConnections(rivers);
        return List.copyOf(rivers);
    }

    private static void resolveConnections(List<RiverCourse> rivers) {
        // Lake outlets/inlets. Their first/last connection zones use the lake's exact water level
        // and blend bathymetry rather than terminating against a vertical lake-bed wall.
        connectSourceLake(rivers, "Kushiro", "Kussharo");
        connectSourceLake(rivers, "Abashiri", "Abashiri");
        connectSourceLake(rivers, "Sagami", "Yamanaka");
        connectSourceLake(rivers, "Tenryu", "Suwa");
        connectSourceLake(rivers, "Yodo", "Biwa");
        connectTerminalLake(rivers, "Iwaki", "Jusan");
        connectTerminalLake(rivers, "Hii", "Shinji");
        connectSourceLake(rivers, "Ohashi", "Shinji");
        connectTerminalLake(rivers, "Ohashi", "Nakaumi");

        // Named tributary links use the exact shared W05 topology node. A few W05 entities contain
        // reversed or disconnected same-code fragments, so these are the topology nodes which are
        // also incident to the selected authored parent -- not merely terminal_nodes[0].
        connectTerminalRiver(rivers, "Sorachi", "Ishikari", 43.52509D, 141.89627D,
                "W05:810103:8101030250:空知川");
        connectTerminalRiver(rivers, "Toyohira", "Ishikari", 43.15432D, 141.44851D,
                "W05:810103:8101030031:豊平川");
        connectTerminalRiver(rivers, "Kinu", "Tone", 35.93630D, 139.95173D,
                "W05:830303:8303030203:鬼怒川");
        connectTerminalRiver(rivers, "Kokai", "Tone", 35.87062D, 140.12735D,
                "W05:830303:8303030152:小貝川");
        connectTerminalRiver(rivers, "Watarase", "Tone", 36.15125D, 139.69876D,
                "W05:830303:8303030461:渡良瀬川");
        connectTerminalRiver(rivers, "Agatsuma", "Tone", 36.49985D, 139.01624D,
                "W05:830303:8303030920:吾妻川");
        connectTerminalRiver(rivers, "Karasu", "Tone", 36.26558D, 139.18353D,
                "W05:830303:8303030689:烏川");
        connectTerminalRiver(rivers, "Iruma", "Arakawa", 35.90879D, 139.54673D,
                "W05:830304:8303040064:入間川");
        connectTerminalRiver(rivers, "Asakawa", "Tama", 35.65968D, 139.43979D,
                "W05:830305:8303050020:浅川");
        connectTerminalRiver(rivers, "Sai", "Shinano", 36.62836D, 138.25035D,
                "W05:840403:8404030705:犀川");
        connectTerminalRiver(rivers, "Uono", "Shinano", 37.27342D, 138.85163D,
                "W05:840403:8404030237:魚野川");
        connectTerminalRiver(rivers, "Hida", "Kiso", 35.44511D, 137.05233D,
                "W05:850509:8505090255:飛騨川");
        connectTerminalRiver(rivers, "Katsura", "Yodo", 34.87928D, 135.67880D,
                "W05:860604:8606040167:桂川");
        connectTerminalRiver(rivers, "Kizu", "Yodo", 34.88399D, 135.68087D,
                "W05:860604:8606040371:木津川");
        connectTerminalRiver(rivers, "Kusu", "Chikugo", 33.30222D, 130.94802D,
                "W05:890906:8909060223:玖珠川");

        // Distributaries need the opposite topology semantics from terminalRiver.  Edo begins at
        // the Tone branch node and inherits the parent's hydraulic surface there; it is no longer
        // treated as an unrelated sea-bound river which merely happens to cross Tone.
        connectBifurcation(rivers, "Edo", "Tone", 36.0961433D, 139.7763365D,
                "W05:830303:8303030304:江戸川");
        connectBifurcation(rivers, "Sumida", "Arakawa", 35.78054D, 139.73783D,
                "W05:830304:8303040011:隅田川");

        // Provenance and widening class are deliberately independent. All 115 remain authored
        // geometry overrides, while P0/P1/P2 controls only the additional downstream widening.
        assignHydrologyClasses(rivers);

        // AR-04..09: apply the frozen regional geometry policy only after source/terminal lake and
        // river topology metadata has been attached, but before any confluence snapping or water
        // profile initialization. This lets the safety gates understand legitimate lake ownership
        // while keeping the refresh strictly geometry-only.
        applyRegionalAuthoredGeometryRefresh(rivers);

        // CF-LAKE-UNION/AR-ENDPOINT-FIX: source-lake geometry is reconciled only after the AR
        // route scaffold is final. Ordinary lake-origin rivers are trimmed to the last source-lake
        // exit (with a tiny raster overlap). A route that starts on land and enters its source lake
        // later is an illegal topology and must be rebuilt from LR-12/W05 outlet evidence.
        reconcileSourceLakeGeometry(rivers);

        // Geometry and hydraulics deliberately share explicit graph anchors. Parent routes are
        // snapped locally first; then tributary tails and distributary sources are rebuilt against
        // those same nodes.  Fixed anchors protect a main stem that owns several connections.
        for (RiverCourse river : rivers) river.snapParentToConfluenceAnchor();
        for (RiverCourse river : rivers) river.snapParentToBifurcationAnchor();
        for (RiverCourse river : rivers) river.resolveTerminalGeometry();
        for (RiverCourse river : rivers) river.resolveBifurcationGeometry();

        // CF-01/CF-04: LR-FINAL shorelines and AR-FINAL RiverCourse geometry are now both frozen.
        // Re-scan every legal authored-river/active-lake overlap and attach the physical water-mask
        // interval to the RiverCourse.  This does not move either geometry; it only makes the final
        // gate owner explicit so PJChunkGenerator can open the shoreline and render one shared Y.
        resolveFinalLakeConnections(rivers);

        // J-02/J-03: the Kusu/Chikugo Fix2 prototype is now the generic scheduled-profile API
        // canary. Chikugo is initialized first because the receiving approach must be refit before
        // Kusu solves its own terminal profile. The shared W05 junction level itself is never moved.
        named(rivers, "Chikugo").initializeWaterProfile();
        convergeJunctionApproach(rivers, "Kusu", "Chikugo", JunctionApproachSide.PARENT,
                256.0D, 768.0D);

        // Build every remaining longitudinal water profile exactly once, recursively initializing
        // parent rivers before their tributaries. Chikugo is already initialized and is therefore a
        // no-op here. Earlier releases built all 115 profiles in the constructor and then rebuilt 24
        // of them as connections were attached, repeating the most expensive startup calculation.
        for (RiverCourse river : rivers) river.initializeWaterProfile();

        // J-04: Hida/Kiso no longer uses a runtime BackwaterZone. The same 384/896 receiving-reach
        // correction is baked into Kiso's longitudinalProfile and re-run through DropScheduler, so
        // falling-water geometry and the runtime water surface now read one identical profile.
        convergeJunctionApproach(rivers, "Hida", "Kiso", JunctionApproachSide.PARENT,
                384.0D, 896.0D);

        // J-06/J-08: strict J-01 exposed only these two residual one-block first-contact mismatches.
        // Both receiving rivers are the higher side; lower them upstream toward the already-shared
        // W05 anchor level. The compact per-site reaches were derived from the real contact-to-anchor
        // distances rather than copying Kusu/Chikugo's longer 256/768 terrace.
        convergeJunctionApproach(rivers, "Agatsuma", "Tone", JunctionApproachSide.PARENT,
                128.0D, 384.0D);
        convergeJunctionApproach(rivers, "Asakawa", "Tama", JunctionApproachSide.PARENT,
                192.0D, 576.0D);

        // CF-03: final generic pass after every authored profile and all historical canary refits.
        // Exact matches are byte-for-byte no-ops.  A residual mismatch may only be repaired by
        // lowering the hydraulically higher approach upstream; the lower side is never raised.
        freezeFinalJunctionHydraulics(rivers);

        // CF-04 runs only after CF-03 has frozen all river-river contacts.  Final lake Y is the
        // immutable hydraulic anchor; redistribute each river approach around that anchor and
        // re-run DropScheduler, without moving either LR-FINAL shoreline or AR-FINAL centre-line.
        for (RiverCourse river : rivers) river.freezeFinalLakeGateHydraulics();
        verifyFinalJunctionHydraulics(rivers);
    }


    /** CF-01 physical gate registry derived only from final lake masks + final authored routes. */
    private static void resolveFinalLakeConnections(List<RiverCourse> rivers) {
        Map<String, LakeData.LakeMetadata> lakeByName = new HashMap<>();
        for (LakeData.LakeMetadata lake : LakeData.metadata()) lakeByName.putIfAbsent(lake.name(), lake);
        for (RiverCourse course : rivers) {
            course.finalLakeConnections.clear();
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
            String officialId = reference == null ? "" : reference.officialIdentifier();
            final double step = 8.0D;
            int sampleCount = Math.max(1, (int)Math.ceil(course.mouthDistance / step));
            LakeOwner runOwner = lakeOwnerAtStation(course, 0.0D, officialId, lakeByName);
            double runStart = runOwner.present() ? 0.0D : Double.NaN;
            double previousStation = 0.0D;
            for (int i = 1; i <= sampleCount; i++) {
                double station = Math.min(course.mouthDistance, i * step);
                LakeOwner owner = lakeOwnerAtStation(course, station, officialId, lakeByName);
                if (!sameLakeOwner(owner, runOwner)) {
                    if (runOwner.present()) {
                        double last = refineLakeBoundary(course, runOwner, previousStation, station, false);
                        course.registerFinalLakeConnection(runOwner, runStart, last);
                    }
                    runStart = owner.present()
                            ? refineLakeBoundary(course, owner, previousStation, station, true)
                            : Double.NaN;
                    runOwner = owner;
                }
                previousStation = station;
            }
            if (runOwner.present()) {
                course.registerFinalLakeConnection(runOwner, runStart, course.mouthDistance);
            }
            course.mergeFragmentedFinalLakeConnections();
        }
    }

    private record SourceLakeGeometryState(boolean found, boolean startsInside,
                                           double firstWaterStation, double lastWaterStation,
                                           int intervalCount) {
        static final SourceLakeGeometryState NONE = new SourceLakeGeometryState(false, false,
                Double.NaN, Double.NaN, 0);
    }

    private static void reconcileSourceLakeGeometry(List<RiverCourse> rivers) {
        for (RiverCourse course : rivers) {
            if (course.sourceLakeName.isEmpty()) continue;
            SourceLakeGeometryState before = sourceLakeGeometryState(course, course.sourceLakeName);
            course.sourceLakeIntrusionBefore = before.found() ? before.lastWaterStation() : Double.NaN;
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
            String officialId = reference == null ? "" : reference.officialIdentifier();

            boolean illegalLandLakeLand = before.found() && !before.startsInside()
                    && before.firstWaterStation() > 128.0D;
            if (illegalLandLakeLand) {
                SourceLakeOutletResolver.Outlet outlet = SourceLakeOutletResolver.resolve(
                        course.sourceLakeName, officialId);
                SourceLakeOfficialRouteResolver.Route officialRoute =
                        SourceLakeOfficialRouteResolver.resolve(course.name, course.sourceLakeName, officialId);
                if (outlet == null || officialRoute == null) {
                    throw new IllegalStateException("No W05 official source-lake route prefix for "
                            + course.name + "<-" + course.sourceLakeName);
                }
                int joinIndex = -1;
                Nearest joinProjection = null;
                double bestJoinDistance = Double.POSITIVE_INFINITY;
                int bestJoinIndex = -1;
                Nearest bestJoinProjection = null;
                int startIndex = Math.max(0, Math.min(officialRoute.targetEntrySequence(),
                        officialRoute.points().size() - 1));
                for (int i = startIndex; i < officialRoute.points().size(); i++) {
                    SourceLakeOfficialRouteResolver.Point candidate = officialRoute.points().get(i);
                    Nearest projection = course.nearestUnbounded(candidate.x(), candidate.z());
                    if (projection.distance() < bestJoinDistance) {
                        bestJoinDistance = projection.distance();
                        bestJoinIndex = i;
                        bestJoinProjection = projection;
                    }
                    if (projection.distance() <= 192.0D) {
                        joinIndex = i;
                        joinProjection = projection;
                        break;
                    }
                }
                if (joinIndex < 0 && bestJoinDistance <= 384.0D) {
                    joinIndex = bestJoinIndex;
                    joinProjection = bestJoinProjection;
                }
                if (joinIndex < 0 || joinProjection == null) {
                    throw new IllegalStateException("Official source-lake chain cannot join authored route: "
                            + course.name + " nearest=" + bestJoinDistance);
                }
                List<SourceLakeOfficialRouteResolver.Point> prefix =
                        officialRoute.points().subList(0, joinIndex + 1);
                course.rebuildSourceFromOfficialRoute(prefix, joinProjection.alongDistance());
                course.sourceLakeGeometryResolution = "OFFICIAL_ROUTE_PREFIX_REBUILD";
                course.sourceLakeOfficialOutletRiverId = outlet.riverId();
                course.sourceLakeOfficialGateDistance = course.nearestUnbounded(
                        outlet.lr12GateX(), outlet.lr12GateZ()).distance();
                double prefixLength = 0.0D;
                double prefixMaxChord = 0.0D;
                for (int i = 1; i < prefix.size(); i++) {
                    SourceLakeOfficialRouteResolver.Point a = prefix.get(i - 1);
                    SourceLakeOfficialRouteResolver.Point b = prefix.get(i);
                    double chord = Math.hypot(b.x() - a.x(), b.z() - a.z());
                    prefixLength += chord;
                    prefixMaxChord = Math.max(prefixMaxChord, chord);
                }
                course.sourceLakeOfficialRouteLength = prefixLength;
                course.sourceLakeOfficialRouteMaxChord = prefixMaxChord;
                course.sourceLakeOfficialJoinDistance = joinProjection.distance();
                course.sourceLakeOfficialEntityChain = officialRoute.entityChain();
            } else if (!before.found()) {
                SourceLakeOutletResolver.Outlet outlet = SourceLakeOutletResolver.resolve(
                        course.sourceLakeName, officialId);
                if (outlet == null) {
                    throw new IllegalStateException("No LR12/W05 source-lake outlet evidence for "
                            + course.name + "<-" + course.sourceLakeName);
                }
                WorldPoint gate = new WorldPoint(outlet.routeShoreX(), outlet.routeShoreZ());
                double resume = course.nearestUnbounded(gate.x(), gate.z()).alongDistance();
                course.rebuildSourceFromOfficialOutlet(gate, resume);
                course.sourceLakeGeometryResolution = "OFFICIAL_OUTLET_REBUILD";
                course.sourceLakeOfficialOutletRiverId = outlet.riverId();
                course.sourceLakeOfficialGateDistance = course.nearestUnbounded(
                        outlet.lr12GateX(), outlet.lr12GateZ()).distance();
            } else if (before.lastWaterStation() > 32.0D) {
                course.trimSourceLakePrefix(Math.max(0.0D, before.lastWaterStation() - 16.0D));
                course.sourceLakeGeometryResolution = "LAST_EXIT_TRIM";
            } else {
                course.sourceLakeGeometryResolution = "KEEP_SHORT_OVERLAP";
            }

            SourceLakeGeometryState after = sourceLakeGeometryState(course, course.sourceLakeName);
            if (!after.found() || after.firstWaterStation() > 128.0D) {
                throw new IllegalStateException("SOURCE_FROM_LAKE route does not begin at source lake: "
                        + course.name + "<-" + course.sourceLakeName + " first="
                        + after.firstWaterStation());
            }
            if (after.lastWaterStation() > 32.5D) {
                // One final deterministic trim handles block-raster boundary jitter after an
                // official outlet rebuild without changing the resolved W05 gate itself.
                course.trimSourceLakePrefix(Math.max(0.0D, after.lastWaterStation() - 16.0D));
                after = sourceLakeGeometryState(course, course.sourceLakeName);
            }
            if (!after.found() || after.lastWaterStation() > 32.5D) {
                throw new IllegalStateException("SOURCE_FROM_LAKE intrusion remains too long: "
                        + course.name + "<-" + course.sourceLakeName + " last="
                        + after.lastWaterStation());
            }
            course.sourceLakeIntrusionAfter = after.lastWaterStation();
            // Geometry changed, so re-measure the outlet transition against the new station zero.
            course.connectSourceLake(course.sourceLakeName);
        }
    }

    private static SourceLakeGeometryState sourceLakeGeometryState(RiverCourse course, String lakeName) {
        final double step = 4.0D;
        boolean startsInside = sourceLakeWaterAt(course, lakeName, 0.0D);
        boolean inRun = false;
        double first = Double.NaN, last = Double.NaN;
        int intervals = 0;
        int count = Math.max(1, (int)Math.ceil(course.mouthDistance / step));
        for (int i = 0; i <= count; i++) {
            double station = Math.min(course.mouthDistance, i * step);
            boolean inside = sourceLakeWaterAt(course, lakeName, station);
            if (inside) {
                if (!inRun) { intervals++; if (Double.isNaN(first)) first = station; }
                last = station;
            }
            inRun = inside;
        }
        if (Double.isNaN(first)) return SourceLakeGeometryState.NONE;
        return new SourceLakeGeometryState(true, startsInside, first, last, intervals);
    }

    private static boolean sourceLakeWaterAt(RiverCourse course, String lakeName, double station) {
        WorldPoint p = course.pointAtDistance(clamp(station, 0.0D, course.mouthDistance));
        LakeData.LakeSample lake = LakeData.sampleWorld((int)Math.round(p.x()), (int)Math.round(p.z()));
        return lake.water() && lakeName.equals(lake.name());
    }

    /**
     * CF-03 exact final river-river hydraulic convergence.  This is deliberately after all fixed
     * J-stage canaries, so old site-specific settings become merely an initial guess.  At first
     * parent-water contact, only the higher approach is allowed to move, and it only moves down.
     */
    private static void freezeFinalJunctionHydraulics(List<RiverCourse> rivers) {
        for (int pass = 0; pass < 3; pass++) {
            int changed = 0;
            for (RiverCourse child : rivers) {
                if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
                ParentWaterGeometryContact contact = firstParentWaterMaskGeometryContact(child);
                if (!contact.found()) continue;
                RiverCourse parent = child.terminalRiver;
                int childY = child.waterSurfaceY(contact.childStation());
                int parentY = parent.waterSurfaceY(contact.parentStation());
                if (childY == parentY) continue;
                WorldPoint point = child.pointAtDistance(contact.childStation());
                int sharedTargetY = Math.min(childY, parentY);
                RiverCourse higher = childY > parentY ? child : parent;
                double available = childY > parentY ? contact.childStation() : contact.parentStation();
                double total = Math.min(768.0D, Math.max(64.0D,
                        roundUp64(Math.max(64.0D, available))));
                double hold = Math.min(192.0D, Math.max(32.0D, total * 0.33D));
                higher.refitUpstreamApproachToJunctionLevel(point, sharedTargetY, hold, total);
                changed++;
            }
            if (changed == 0) break;
        }
        verifyFinalJunctionHydraulics(rivers);
    }

    private static void verifyFinalJunctionHydraulics(List<RiverCourse> rivers) {
        for (RiverCourse child : rivers) {
            if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
            ParentWaterContact contact = firstParentWaterMaskContact(child);
            if (!contact.found() || contact.childY() != contact.parentY()) {
                throw new IllegalStateException("CF-03 unresolved first-contact mismatch " + child.name
                        + "->" + child.terminalRiver.name + " child=" + contact.childY()
                        + " parent=" + contact.parentY());
            }
        }
    }

    private record ARRouteSafety(int illegalLakeIntersections, int illegalCoastCrossings) {
        boolean safe() { return illegalLakeIntersections == 0 && illegalCoastCrossings == 0; }
    }

    private record NearSourceLakeCollision(String lakeName, double distanceBlocks) {
        static final NearSourceLakeCollision NONE = new NearSourceLakeCollision("", Double.POSITIVE_INFINITY);
        boolean present() { return !lakeName.isEmpty(); }
    }

    /** Detect only a legal DECLARED_CHANNEL lake crowding a non-lake source; no lake ID special case. */
    private static NearSourceLakeCollision nearSourceDeclaredChannelCollision(
            RiverCourse course, AuthoredRiverGeometryRefresher.Point source, String officialId) {
        int sx = (int)Math.round(source.x()), sz = (int)Math.round(source.z());
        NearSourceLakeCollision best = NearSourceLakeCollision.NONE;
        for (int dx = -128; dx <= 128; dx += 8) {
            for (int dz = -128; dz <= 128; dz += 8) {
                if (dx * dx + dz * dz > 128 * 128) continue;
                LakeData.LakeSample sample = LakeData.sampleWorld(sx + dx, sz + dz);
                if (!sample.corridor() || sample.name().isEmpty()) continue;
                LakeData.LakeMetadata meta = lakeMetadataByName(sample.name());
                String reason = legalLakeIntersectionReason(course, sample.name(), officialId, meta);
                if (!"DECLARED_CHANNEL".equals(reason)) continue;
                double estimate = Math.hypot(dx, dz) + Math.max(0.0D, sample.distanceToShore());
                if (estimate < best.distanceBlocks()) {
                    best = new NearSourceLakeCollision(sample.name(), estimate);
                }
            }
        }
        // A generic severe-collision gate: only a declared-channel shoreline inside the existing
        // 32-block headwater bank margin can justify a >1024 ordered source rebuild.  A reservoir
        // merely lying somewhere in the first hundred blocks is not enough evidence.
        return best.present() && best.distanceBlocks() <= HEADWATER_BANK_MARGIN
                ? best : NearSourceLakeCollision.NONE;
    }

    private static List<AuthoredRiverGeometryRefresher.Point> prependLongSourcePrefix(
            List<AuthoredRiverGeometryRefresher.Point> candidate,
            AuthoredLongSourcePrefixResolver.Route route) {
        List<AuthoredRiverGeometryRefresher.Point> out = new ArrayList<>();
        for (AuthoredLongSourcePrefixResolver.Point p : route.points()) {
            AuthoredRiverGeometryRefresher.Point q = new AuthoredRiverGeometryRefresher.Point(p.x(), p.z());
            if (out.isEmpty() || Math.hypot(q.x() - out.get(out.size() - 1).x(),
                    q.z() - out.get(out.size() - 1).z()) >= 1.0D) out.add(q);
        }
        for (AuthoredRiverGeometryRefresher.Point q : candidate) {
            if (out.isEmpty() || Math.hypot(q.x() - out.get(out.size() - 1).x(),
                    q.z() - out.get(out.size() - 1).z()) >= 1.0D) out.add(q);
        }
        return List.copyOf(out);
    }

    private static void applyRegionalAuthoredGeometryRefresh(List<RiverCourse> rivers) {
        Map<String, LakeData.LakeMetadata> lakesByName = new HashMap<>();
        for (LakeData.LakeMetadata lake : LakeData.metadata()) lakesByName.put(lake.name(), lake);
        for (RiverCourse course : rivers) {
            AuthoredRiverARPolicy.Decision decision = AuthoredRiverARPolicy.decision(course.name);
            AuthoredRiverGeometryRefresher.Mode mode = decision.mode();
            course.arRegionalBatch = AuthoredRiverRegionalPolicy.batch(course.name);
            course.arRegionalMode = mode.name();
            course.arBaselineMedianDeviationMetres = routeDeviationAudit(course).medianDeviationMetres();
            course.arBaselineLongestStraightRunBlocks = course.longestStraightRunBlocks();
            if (mode == AuthoredRiverGeometryRefresher.Mode.KEEP
                    || mode == AuthoredRiverGeometryRefresher.Mode.MANUAL_KEEP) continue;

            course.arRegionalRefreshAttempted = true;
            List<AuthoredRiverGeometryRefresher.Point> original = course.authoredRefreshScaffold(48.0D);
            List<AuthoredRiverGeometryRefresher.Point> protectedPoints = protectedRefreshPoints(course);
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);

            boolean relocateSource = mode == AuthoredRiverGeometryRefresher.Mode.REBUILD
                    && course.sourceLakeName.isEmpty() && course.sourceRiverName.isEmpty();
            if (mode == AuthoredRiverGeometryRefresher.Mode.REBUILD) {
                AuthoredRiverGeometryRefresher.SourceRelocationDecision sourceDecision =
                        AuthoredRiverGeometryRefresher.sourceRelocationDecision(course.name, original);
                course.arProposedSourceRelocationBlocks = sourceDecision.proposedShiftBlocks();
                course.arAcceptedSourceRelocationBlocks = relocateSource
                        ? sourceDecision.acceptedShiftBlocks() : 0.0D;
                course.arOldSourceX = sourceDecision.oldSource().x();
                course.arOldSourceZ = sourceDecision.oldSource().z();
                course.arProposedSourceX = sourceDecision.proposedSource().x();
                course.arProposedSourceZ = sourceDecision.proposedSource().z();
                AuthoredRiverGeometryRefresher.Point acceptedSource = relocateSource
                        ? sourceDecision.acceptedSource() : sourceDecision.oldSource();
                course.arAcceptedSourceX = acceptedSource.x();
                course.arAcceptedSourceZ = acceptedSource.z();
                course.arSourceRelocationReason = relocateSource
                        ? sourceDecision.reason() : "SOURCE_TOPOLOGY_PROTECTED";
            }
            List<AuthoredRiverGeometryRefresher.Point> candidate =
                    AuthoredRiverGeometryRefresher.runtimeCandidate(course.name, mode, original,
                            protectedPoints, relocateSource);

            // Fix3 B: >1024 correction stays forbidden as a point teleport.  It is accepted only
            // when (a) the existing source is colliding with a legal mid-route lake and (b) a
            // generated W05 graph path proves an ordered head -> old-route prefix with bounded
            // raw chords and join distance.  Nagara/Yoshii therefore remain rejected; Yoshino can
            // recover its missing upstream reach without a 3.6k-block chord.
            if (mode == AuthoredRiverGeometryRefresher.Mode.REBUILD && relocateSource
                    && course.arProposedSourceRelocationBlocks > 1024.0D && reference != null) {
                NearSourceLakeCollision collision = nearSourceDeclaredChannelCollision(
                        course, original.get(0), reference.officialIdentifier());
                course.arNearSourceLakeName = collision.lakeName();
                course.arNearSourceLakeDistanceBefore = collision.distanceBlocks();
                AuthoredLongSourcePrefixResolver.Route longRoute = collision.present()
                        ? AuthoredLongSourcePrefixResolver.resolve(course.name,
                        reference.officialIdentifier()) : null;
                if (longRoute != null && longRoute.maximumRawChord() <= 192.0D
                        && longRoute.joinDistance() <= 128.0D
                        && longRoute.sourceShift() > 1024.0D) {
                    candidate = prependLongSourcePrefix(candidate, longRoute);
                    AuthoredRiverGeometryRefresher.Point acceptedSource = candidate.get(0);
                    course.arAcceptedSourceX = acceptedSource.x();
                    course.arAcceptedSourceZ = acceptedSource.z();
                    course.arAcceptedSourceRelocationBlocks = Math.hypot(
                            acceptedSource.x() - original.get(0).x(),
                            acceptedSource.z() - original.get(0).z());
                    course.arSourceRelocationReason = "ROUTE_BACKED_LONG_SOURCE_REBUILD";
                    course.arLongSourcePrefixLength = longRoute.routeLength();
                    course.arLongSourcePrefixJoinDistance = longRoute.joinDistance();
                }
            }
            course.arRawFirstChordBlocks = candidate.size() < 2 ? 0.0D
                    : Math.hypot(candidate.get(1).x() - candidate.get(0).x(),
                            candidate.get(1).z() - candidate.get(0).z());
            course.arRawMaxChordBlocks = 0.0D;
            for (int i = 1; i < candidate.size(); i++) {
                course.arRawMaxChordBlocks = Math.max(course.arRawMaxChordBlocks,
                        Math.hypot(candidate.get(i).x() - candidate.get(i - 1).x(),
                                candidate.get(i).z() - candidate.get(i - 1).z()));
            }
            course.replaceAuthoredRefreshRoute(candidate, relocateSource);
            course.arLongestStraightRunBlocks = course.longestStraightRunBlocks();
            ARRouteSafety safety = authoredRouteSafety(course, reference, lakesByName);

            if (!safety.safe() && mode == AuthoredRiverGeometryRefresher.Mode.REBUILD) {
                boolean lakeFailure = safety.illegalLakeIntersections() > 0;
                course.replaceAuthoredRefreshRoute(original);
                List<AuthoredRiverGeometryRefresher.Point> conservative =
                        AuthoredRiverGeometryRefresher.runtimeCandidate(course.name,
                                AuthoredRiverGeometryRefresher.Mode.REFIT, original, protectedPoints);
                course.replaceAuthoredRefreshRoute(conservative);
                ARRouteSafety conservativeSafety = authoredRouteSafety(course, reference, lakesByName);
                if (conservativeSafety.safe()) {
                    course.arRegionalFallback = lakeFailure
                            ? "LAKE_GUARD_DOWNGRADE_REFIT" : "COAST_GUARD_DOWNGRADE_REFIT";
                    safety = conservativeSafety;
                } else {
                    course.replaceAuthoredRefreshRoute(original);
                    course.arRegionalFallback = lakeFailure ? "LAKE_GUARD_REVERT" : "COAST_GUARD_REVERT";
                    safety = authoredRouteSafety(course, reference, lakesByName);
                }
            } else if (!safety.safe()) {
                boolean lakeFailure = safety.illegalLakeIntersections() > 0;
                course.replaceAuthoredRefreshRoute(original);
                course.arRegionalFallback = lakeFailure ? "LAKE_GUARD_REVERT" : "COAST_GUARD_REVERT";
            }
        }
    }

    private static ARRouteSafety authoredRouteSafety(RiverCourse course,
                                                       W05AuthoredReference.Route reference,
                                                       Map<String, LakeData.LakeMetadata> lakesByName) {
        LakeIntersectionAudit lakes = authoredLakeIntersectionAudit(course, reference, lakesByName);
        int coast = course.reachesSea ? (firstInlandOceanCrossing(course) == null ? 0 : 1)
                : nonSeaOceanCrossingCount(course);
        return new ARRouteSafety(lakes.illegalCount(), coast);
    }

    private static RiverCourse named(List<RiverCourse> rivers, String name) {
        return rivers.stream().filter(river -> river.name.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown river: " + name));
    }

    private static void connectSourceLake(List<RiverCourse> rivers, String river, String lake) {
        named(rivers, river).connectSourceLake(lake);
    }

    private static void connectTerminalLake(List<RiverCourse> rivers, String river, String lake) {
        named(rivers, river).connectTerminalLake(lake);
    }

    private static void connectTerminalRiver(List<RiverCourse> rivers, String child, String parent,
                                             double latitude, double longitude,
                                             String officialIdentifier) {
        named(rivers, child).connectTerminalRiver(named(rivers, parent),
                new ConfluenceAnchor(new WorldPoint(
                        TerrainData.worldXFromLongitude(longitude),
                        TerrainData.worldZFromLatitude(latitude)), officialIdentifier));
    }

    private static void connectBifurcation(List<RiverCourse> rivers, String branch, String parent,
                                            double latitude, double longitude,
                                            String officialIdentifier) {
        named(rivers, branch).connectBifurcation(named(rivers, parent),
                new BifurcationAnchor(new WorldPoint(
                        TerrainData.worldXFromLongitude(longitude),
                        TerrainData.worldZFromLatitude(latitude)), officialIdentifier));
    }

    private static void assignHydrologyClasses(List<RiverCourse> rivers) {
        Set<String> p1 = Set.of(
                "Sorachi", "Toyohira", "Takase", "Koyoshi", "Natori", "Edo", "Sumida",
                "Tsurumi", "Kinu", "Kokai", "Watarase", "Agatsuma", "Karasu", "Iruma",
                "Asakawa", "Seki", "Hime", "Joganji", "Jinzu", "Oyabe", "Tedori", "Sai",
                "Uono", "Abe", "Shonai", "Suzuka", "Kumozu", "Ibo", "Yamato", "Hida",
                "Katsura", "Kizu", "Sendai-Tottori", "Oze", "Saba", "Ohashi", "Onga",
                "Yamakuni", "Oita", "Banjo", "Matsuura", "Rokkaku", "Shira", "Kusu");
        Set<String> p2 = Set.of("Kano", "Kiku", "Doki", "Shigenobu", "Kimotsuki", "Honmyo");
        for (RiverCourse river : rivers) {
            river.hydrologyClass = p2.contains(river.name) ? HydrologyClass.P2
                    : (p1.contains(river.name) ? HydrologyClass.P1 : HydrologyClass.P0);
        }
    }

    private enum JunctionApproachSide { CHILD, PARENT }

    /**
     * J-02 scheduled junction-level convergence API. The caller selects only the approach that is
     * hydraulically higher; the selected course is allowed to lower toward the already-shared W05
     * junction level, never to raise. The refit rewrites longitudinalProfile and re-runs
     * DropScheduler inside RiverCourse.refitUpstreamApproachToJunctionLevel().
     */
    private static void convergeJunctionApproach(
            List<RiverCourse> rivers, String child, String parent, JunctionApproachSide side,
            double flatLength, double totalLength) {
        RiverCourse childCourse = named(rivers, child);
        RiverCourse parentCourse = named(rivers, parent);
        WorldPoint junction = childCourse.confluenceAnchor != null
                ? childCourse.confluenceAnchor.officialPoint()
                : childCourse.pointAtDistance(childCourse.mouthDistance);
        parentCourse.initializeWaterProfile();
        Nearest parentAtJunction = parentCourse.nearestUnbounded(junction.x(), junction.z());
        int sharedTargetY = parentCourse.waterSurfaceY(parentAtJunction.alongDistance());
        RiverCourse target = side == JunctionApproachSide.CHILD ? childCourse : parentCourse;
        target.initializeWaterProfile();
        target.refitUpstreamApproachToJunctionLevel(junction, sharedTargetY,
                flatLength, totalLength);
    }


    private static Map<Long, List<RiverCourse>> buildCourseGrid(List<RiverCourse> courses) {
        Map<Long, List<RiverCourse>> mutable = new HashMap<>();
        for (RiverCourse course : courses) {
            // A route's overall bounding rectangle can cover large areas far from a diagonal or
            // curved river. Index the actual fine segments plus their complete influence radius;
            // the outer course loop keeps candidate precedence identical to the definition order.
            Set<Long> occupiedCells = new HashSet<>();
            for (Segment segment : course.segments) {
                double radius = course.segmentQueryRadius;
                int minCellX = gridCoord(Math.min(segment.start().x(), segment.end().x())
                        - radius, COURSE_GRID_SIZE);
                int maxCellX = gridCoord(Math.max(segment.start().x(), segment.end().x())
                        + radius, COURSE_GRID_SIZE);
                int minCellZ = gridCoord(Math.min(segment.start().z(), segment.end().z())
                        - radius, COURSE_GRID_SIZE);
                int maxCellZ = gridCoord(Math.max(segment.start().z(), segment.end().z())
                        + radius, COURSE_GRID_SIZE);
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                        occupiedCells.add(gridKey(cellX, cellZ));
                    }
                }
            }
            for (long key : occupiedCells) {
                mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(course);
            }
        }
        Map<Long, List<RiverCourse>> result = new HashMap<>(mutable.size());
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static List<RiverCourse> candidateCourses(int worldX, int worldZ) {
        return COURSE_GRID.getOrDefault(gridKey(gridCoord(worldX, COURSE_GRID_SIZE),
                gridCoord(worldZ, COURSE_GRID_SIZE)), List.of());
    }

    private static int gridCoord(double coordinate, int gridSize) {
        return (int)Math.floor(coordinate / gridSize);
    }

    private static long gridKey(int cellX, int cellZ) {
        return ((long)cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    private static long pointKey(int worldX, int worldZ) {
        return ((long)worldX << 32) ^ (worldZ & 0xffffffffL);
    }

    private static final class SampleCache {
        private final long[] keys;
        private final RiverSample[] values;
        private final boolean[] valid;
        private final int mask;

        private SampleCache(int size) {
            if (Integer.bitCount(size) != 1) {
                throw new IllegalArgumentException("Sample cache size must be a power of two");
            }
            this.keys = new long[size];
            this.values = new RiverSample[size];
            this.valid = new boolean[size];
            this.mask = size - 1;
        }

        private int slot(long key) {
            long mixed = key ^ (key >>> 33);
            mixed *= 0xff51afd7ed558ccdl;
            mixed ^= mixed >>> 33;
            return ((int)mixed) & mask;
        }

        private RiverSample get(long key) {
            int slot = slot(key);
            return valid[slot] && keys[slot] == key ? values[slot] : null;
        }

        private void put(long key, RiverSample value) {
            int slot = slot(key);
            keys[slot] = key;
            values[slot] = value;
            valid[slot] = true;
        }
    }


    public static RiverSample sampleWorld(int worldX, int worldZ) {
        long key = pointKey(worldX, worldZ);
        SampleCache cache = SAMPLE_CACHE.get();
        RiverSample cached = cache.get(key);
        if (cached != null) return cached;

        List<RiverCourse> candidates = candidateCourses(worldX, worldZ);
        if (candidates.isEmpty()) {
            cache.put(key, RiverSample.NONE);
            return RiverSample.NONE;
        }

        RiverSample result = sampleWorldIndexed(worldX, worldZ, candidates,
                Integer.MIN_VALUE, false);
        cache.put(key, result);
        return result;
    }

    /**
     * Variant used by the chunk generator after it has already sampled the base terrain. Passing
     * the surface here avoids decoding the height/land rasters a second time for the same column.
     */
    public static RiverSample sampleWorld(int worldX, int worldZ, int naturalSurface) {
        long key = pointKey(worldX, worldZ);
        SampleCache cache = SAMPLE_CACHE.get();
        RiverSample cached = cache.get(key);
        if (cached != null) return cached;

        List<RiverCourse> candidates = candidateCourses(worldX, worldZ);
        if (candidates.isEmpty()) {
            cache.put(key, RiverSample.NONE);
            return RiverSample.NONE;
        }

        RiverSample result = sampleWorldIndexed(worldX, worldZ, candidates,
                naturalSurface, true);
        cache.put(key, result);
        return result;
    }

    private static RiverSample sampleWorldIndexed(int worldX, int worldZ,
                                                   List<RiverCourse> candidates,
                                                   int suppliedNaturalSurface,
                                                   boolean hasSuppliedNaturalSurface) {
        RiverSample best = RiverSample.NONE;
        double bestScore = Double.POSITIVE_INFINITY;
        int bestEffectiveBankSurface = Integer.MAX_VALUE;
        int naturalSurface = suppliedNaturalSurface;
        boolean naturalSurfaceReady = hasSuppliedNaturalSurface;
        Boolean closeSingleCellPocket = null;

        List<CourseProbe> probes = new ArrayList<>(candidates.size());
        for (RiverCourse course : candidates) {
            if (!course.mayContain(worldX, worldZ)) continue;
            Nearest nearest = course.nearest(worldX, worldZ);
            if (nearest == null) continue;
            double progress = clamp(nearest.alongDistance() / course.mouthDistance, 0.0D, 1.0D);
            double width = course.waterWidth(progress);
            probes.add(new CourseProbe(course, nearest, progress, width));
        }

        for (CourseProbe probe : probes) {
            RiverCourse course = probe.course;
            Nearest nearest = probe.nearest;
            double progress = probe.progress;
            double width = probe.width;
            // A sub-block raster margin removes one-column dry pinholes where two diagonal river
            // masks meet after integer world-coordinate sampling. It does not visibly change the
            // authored width, but makes the union topologically closed for fluid placement.
            double halfWater = probe.halfWaterWidth;
            boolean inWater = nearest.distance() <= halfWater
                    && course.allowsFiniteWaterMask(worldX, worldZ);
            if (!inWater && nearest.distance() - halfWater <= 1.0D
                    && probes.size() > 1) {
                if (closeSingleCellPocket == null) {
                    closeSingleCellPocket = closesSingleCellWaterPocket(
                            worldX, worldZ, candidates);
                }
                inWater = closeSingleCellPocket;
            }

            int waterY = course.waterSurfaceY(nearest.alongDistance());
            ConnectionInfo lakeConnection = course.lakeConnection(nearest.alongDistance());
            // Lake source/terminal influence is already baked into buildWaterProfile(). Applying
            // the same interpolation again here raised the final surface above the terrace profile
            // used by dropInfo(), so the waterfall and the river column disagreed about their Y.
            // Keep ConnectionInfo only for lake-bed blending and connection metadata.

            ChannelBathymetry localBathy = localBathymetry(course, nearest, progress, width,
                    waterY, inWater);
            int maxDepth = localBathy.maximumDepth();
            int depth = localBathy.depth();

            ConfluenceInfo confluence = course.confluenceInfo(worldX, worldZ,
                    nearest.alongDistance(), depth);
            boolean hydraulicBlend = false;
            if (confluence.active()) {
                int blendedWaterY = (int)Math.round(lerp(confluence.parentWaterY(), waterY,
                        confluence.childInfluence()));
                // Runtime reconciliation may lower a child toward its exact anchor level, but it
                // is forbidden to raise it above the already-monotonic course profile.
                blendedWaterY = Math.min(waterY, blendedWaterY);
                hydraulicBlend = blendedWaterY != waterY;
                waterY = blendedWaterY;
            }

            HydraulicAdjustment adjustment = hydraulicAdjustment(probe, probes, waterY);
            hydraulicBlend |= adjustment.blended();
            waterY = adjustment.waterY();
            double headwaterInfluence = course.headwaterInfluence(nearest.alongDistance());

            // Bank geometry may react to the local terrain height, but the water surface above was
            // already resolved from the monotonic RiverData profile. This separation is critical:
            // DEM can widen or soften a valley, never make the river climb uphill.
            if (!naturalSurfaceReady) {
                naturalSurface = RiverCourse.naturalSurfaceAt(new WorldPoint(worldX, worldZ));
                naturalSurfaceReady = true;
            }

            // Water terraces are deliberately discrete; terrain must not copy those steps sideways
            // through an entire mountain.  The hydraulic reference used for river/rivermask
            // reconciliation remains unchanged, while dry-bank carving gets a second, much smoother
            // longitudinal grade.  It is clamped at or above the actual local water surface, so this
            // can only preserve/soften land around a drop -- it can never lower a bank below the
            // water or make the river run uphill.
            int bankReferenceWaterY = (int)Math.round(
                    course.bankTerrainReferenceWaterLevel(
                            worldX, worldZ, nearest.distance(), nearest.alongDistance(),
                            naturalSurface, waterY));
            double bankDistance = Math.max(0.0D, nearest.distance() - halfWater);
            double bankSlope = course.bankSlope(progress, width, naturalSurface,
                    waterY);
            double bankMargin = course.maximumBankSupport(progress, width, naturalSurface,
                    waterY, bankSlope);
            // Source protection is based on the *actual hydraulic incision*, not the smoothed
            // terrain reference.  A deep cut can have a reference only 2-4 blocks below the DEM;
            // using that smaller difference kept the 3-block source guard alive and produced
            // abrupt protected/unprotected walls exactly where the valley most needed blending.
            double headwaterBankGuard = course.headwaterBankGuard(nearest.alongDistance(),
                    Math.max(0.0D, naturalSurface - waterY));
            double halfCorridor = halfWater + bankMargin;
            if (nearest.distance() > halfCorridor
                    || !course.allowsFiniteCorridorMask(worldX, worldZ, bankMargin)) continue;

            DropInfo drop = hydraulicBlend
                    ? new DropInfo(false, waterY, waterY, 0.0D)
                    : course.dropInfo(nearest.alongDistance());
            double connectorLength = drop.active()
                    ? course.fallingConnectorLength(drop, width, nearest.alongDistance()) : 0.0D;
            boolean fallingConnector = false;
            boolean flowUpdate = false;
            if (inWater && drop.active()) {
                double connectorOffset = drop.offsetFromBoundary();
                double downstreamTaper = smoothstep(0.0D,
                        Math.max(1.0D, connectorLength), Math.max(0.0D, connectorOffset));
                double maximumInset = clamp(8.0D + width * 0.12D,
                        8.0D, Math.min(20.0D, halfWater * 0.65D));
                double edgeInset = maximumInset * downstreamTaper;
                double fallingHalfWater = Math.max(2.0D, halfWater - edgeInset);
                fallingConnector = connectorOffset >= 0.0D
                        && connectorOffset <= connectorLength
                        && nearest.distance() <= fallingHalfWater;
                flowUpdate = connectorOffset >= -2.0D
                        && connectorOffset <= connectorLength + 2.0D
                        && nearest.distance() <= fallingHalfWater;
            }
            // The graded high-source surface consists of one-block steps spread across the reach,
            // not a single waterfall at the 16-block profile boundary. Schedule fluid updates only
            // where that graded surface changes so those small cascades can settle naturally.
            boolean headwaterStep = course.headwaterSurfaceStep(nearest.alongDistance());
            if (inWater && headwaterStep) {
                flowUpdate = true;
            }

            // Bank containment is risk-driven in RiverBankTerrain. A hydraulic drop/headwater
            // step is NOT, by itself, permission to build terrain. headwaterStep remains only a
            // fluid-update signal and carries no terrain-protection height.
            if (headwaterInfluence > 0.0D) {
                int headwaterDepthCap = Math.max(1, HEADWATER_MAX_WET_TERRAIN_CUT
                        - HEADWATER_PREFERRED_SURFACE_CUT);
                depth = Math.min(depth, headwaterDepthCap);
                maxDepth = Math.min(maxDepth, headwaterDepthCap);
            }
            int sampleBedY = waterY - Math.max(1, depth);
            JunctionBathymetryAdjustment junctionBed = junctionBathymetry(
                    worldX, worldZ, probe, probes, inWater, waterY, sampleBedY);
            if (junctionBed.active()) sampleBedY = junctionBed.bedY();
            double score = nearest.distance() / halfCorridor - width * 0.00035D;
            RiverSample candidate = new RiverSample(
                    true, inWater, course.name, nearest.distance(), halfWater,
                    halfCorridor, progress, waterY, sampleBedY,
                    maxDepth, drop.active(), fallingConnector, flowUpdate,
                    drop.upperWaterY(), drop.lowerWaterY(), course.reachesSea,
                    bankSlope, bankDistance, lakeConnection.lakeName(),
                    lakeConnection.riverInfluence(), lakeConnection.apertureInfluence(),
                    course.terminalRiverName,
                    bankReferenceWaterY, headwaterBankGuard
            );

            boolean choose;
            int candidateEffectiveBankSurface = Integer.MAX_VALUE;
            if (candidate.water() != best.water()) {
                choose = candidate.water();
            } else if (candidate.water()) {
                choose = candidate.waterSurfaceY() < best.waterSurfaceY()
                        || (candidate.waterSurfaceY() == best.waterSurfaceY()
                        && score < bestScore);
            } else {
                // Terrain height is only needed for dry bank candidates. In 0.5.8 it was decoded
                // for every column before we even knew whether a river was nearby.
                if (!naturalSurfaceReady) {
                    naturalSurface = RiverCourse.naturalSurfaceAt(new WorldPoint(worldX, worldZ));
                    naturalSurfaceReady = true;
                }
                // Candidate selection must use the exact same production bank surface as the
                // final chunk generator. Keeping a second copy of the old bank formula here was
                // able to select a different river corridor than RiverBankTerrain eventually
                // generated, especially after bank-protection changes.
                candidateEffectiveBankSurface = RiverBankTerrain.surface(naturalSurface, candidate);
                choose = candidateEffectiveBankSurface < bestEffectiveBankSurface
                        || (candidateEffectiveBankSurface == bestEffectiveBankSurface
                        && score < bestScore);
            }
            if (choose) {
                best = candidate;
                bestScore = score;
                bestEffectiveBankSurface = candidateEffectiveBankSurface;
            }
        }
        return best;
    }

    private static ChannelBathymetry localBathymetry(RiverCourse course, Nearest nearest,
                                                       double progress, double width, int waterY,
                                                       boolean inWater) {
        int maxDepth = maximumDepthBlocks(width, progress, course.reachesSea);
        if (course.name.equals("Ohashi")) maxDepth = Math.min(maxDepth, 4);
        if (course.name.equals("Hii") && progress >= 0.60D) {
            double taper = smoothstep(0.60D, 1.0D, progress);
            int lowerReachCap = (int)Math.round(lerp(8.0D, 5.0D, taper));
            maxDepth = Math.min(maxDepth, lowerReachCap);
        }
        double halfWater = width * 0.5D + course.waterRasterMargin(progress);
        double lateralNormal = clamp(nearest.distance() / Math.max(1.0D, halfWater), 0.0D, 1.0D);
        double edgeShoal = smoothstep(progress >= 0.88D ? 0.88D : 0.62D,
                1.0D, lateralNormal);
        int depth = inWater ? 1 + (int)Math.round((maxDepth - 1) * (1.0D - edgeShoal)) : 0;
        if (inWater && course.headwaterInfluence(nearest.alongDistance()) > 0.0D) {
            int headwaterDepthCap = Math.max(1, HEADWATER_MAX_WET_TERRAIN_CUT
                    - HEADWATER_PREFERRED_SURFACE_CUT);
            depth = Math.min(depth, headwaterDepthCap);
            maxDepth = Math.min(maxDepth, headwaterDepthCap);
        }
        int bedY = waterY - Math.max(1, depth);
        return new ChannelBathymetry(waterY, bedY, depth, maxDepth, lateralNormal, edgeShoal);
    }

    /**
     * Least one-block-per-column radial majorant of a channel's ordinary local depth.
     *
     * <p>The ordinary smoothstep cross-section is visually useful along isolated rivers, but its
     * rounded depth can change by several blocks across one column near a narrow junction.  The
     * old P1.2 code solved that by clipping everything to the parent edge distance, which raised
     * the child bed into a shelf.  This envelope instead propagates deeper real parent samples
     * outward by at most one block of depth per block of distance.  It can only deepen a target;
     * it never turns an out-of-mask parent column into shallow replacement bathymetry.</p>
     */
    private static int junctionDepthEnvelope(RiverCourse course, CourseProbe probe,
                                             int finalWaterY, boolean finiteMaskAllowed) {
        if (!finiteMaskAllowed) return 0;
        ChannelBathymetry centre = localBathymetry(course,
                new Nearest(0.0D, probe.nearest.alongDistance()),
                probe.progress, probe.width, finalWaterY, true);
        int envelope = 0;
        for (int offset = 0; offset <= centre.maximumDepth(); offset++) {
            double inwardDistance = Math.max(0.0D, probe.nearest.distance() - offset);
            if (inwardDistance > probe.halfWaterWidth) continue;
            ChannelBathymetry support = localBathymetry(course,
                    new Nearest(inwardDistance, probe.nearest.alongDistance()),
                    probe.progress, probe.width, finalWaterY, true);
            envelope = Math.max(envelope, support.depth() - offset);
        }
        return Math.max(0, envelope);
    }

    /**
     * P1.2 river-river bed continuity.  The junction owns one 2-D depth union, so parent and
     * child sample ownership cannot leave a submerged step.  This runs only for explicit
     * RiverGraph edges; unrelated crossings remain topology errors.
     */
    private static JunctionBathymetryAdjustment junctionBathymetry(
            int worldX, int worldZ, CourseProbe owner, List<CourseProbe> probes,
            boolean ownerWater, int finalWaterY, int ownerLocalBedY) {
        if (probes.size() < 2) return JunctionBathymetryAdjustment.NONE;
        JunctionBathymetryAdjustment bestAdjustment = JunctionBathymetryAdjustment.NONE;
        double bestJunctionScore = Double.NEGATIVE_INFINITY;
        for (CourseProbe otherProbe : probes) {
            if (otherProbe == owner) continue;
            RiverCourse a = owner.course;
            RiverCourse b = otherProbe.course;
            RiverCourse child = null;
            RiverCourse parent = null;
            CourseProbe childProbe = null;
            CourseProbe parentProbe = null;
            JunctionType type = JunctionType.NONE;

            if (a.terminalRiver == b) {
                child = a; parent = b; childProbe = owner; parentProbe = otherProbe;
                type = JunctionType.CONFLUENCE;
            } else if (b.terminalRiver == a) {
                child = b; parent = a; childProbe = otherProbe; parentProbe = owner;
                type = JunctionType.CONFLUENCE;
            } else if (a.sourceRiver == b) {
                child = a; parent = b; childProbe = owner; parentProbe = otherProbe;
                type = JunctionType.BIFURCATION;
            } else if (b.sourceRiver == a) {
                child = b; parent = a; childProbe = otherProbe; parentProbe = owner;
                type = JunctionType.BIFURCATION;
            } else {
                continue;
            }

            boolean childWater = childProbe.nearest.distance() <= childProbe.halfWaterWidth
                    && child.allowsFiniteWaterMask(worldX, worldZ);
            boolean parentWater = parentProbe.nearest.distance() <= parentProbe.halfWaterWidth
                    && parent.allowsFiniteWaterMask(worldX, worldZ);
            // sampleWorld closes a true one-column hole in the union water mask.  Carry that
            // already-resolved owner state into the junction field; otherwise the water column
            // survives while bathymetry switches off for exactly one column and leaves a wall.
            boolean ownerFiniteWaterMask = owner.course.allowsFiniteWaterMask(worldX, worldZ);
            boolean ownerClosedPocket = ownerWater
                    && (owner.nearest.distance() > owner.halfWaterWidth || !ownerFiniteWaterMask);
            if (ownerClosedPocket) {
                // J-10 freeze after J-09 clipping: a one-column topological close can now sit
                // inside the nominal child width but just beyond the finite mouth/taper mask.
                // Preserve the already-resolved owner water state in the junction depth union;
                // otherwise that single wet pixel falls back to shallow local bathymetry and can
                // create a 3-4 block underwater spike beside an otherwise 1-block-slope bed.
                if (owner.course == child
                        && childProbe.nearest.distance() - childProbe.halfWaterWidth <= 1.0D) {
                    childWater = true;
                } else if (childProbe.nearest.distance() - childProbe.halfWaterWidth <= 1.0D
                        && child.allowsFiniteWaterMask(worldX, worldZ)) {
                    childWater = true;
                }
                if (owner.course == parent
                        && parentProbe.nearest.distance() - parentProbe.halfWaterWidth <= 1.0D) {
                    parentWater = true;
                } else if (parentProbe.nearest.distance() - parentProbe.halfWaterWidth <= 1.0D
                        && parent.allowsFiniteWaterMask(worldX, worldZ)) {
                    parentWater = true;
                }
            }
            if (!childWater && !parentWater) continue;

            // Bathymetry is a depth field.  Both channels must be evaluated at this world
            // column, with their own local progress/width/mask, against the already-final water
            // surface chosen by sampleWorld().  In particular, do not manufacture a parent bed
            // outside the real parent water mask and do not reintroduce a second canonical Y.
            ChannelBathymetry childBathy = localBathymetry(child, childProbe.nearest,
                    childProbe.progress, childProbe.width, finalWaterY, childWater);
            ChannelBathymetry parentBathy = localBathymetry(parent, parentProbe.nearest,
                    parentProbe.progress, parentProbe.width, finalWaterY, parentWater);
            int bedDelta = Math.abs(childBathy.depth() - parentBathy.depth());
            double baseRange = clamp(128.0D + Math.max(childProbe.width, parentProbe.width) * 2.5D,
                    192.0D, 512.0D);
            double blendRange = clamp(baseRange + bedDelta * 32.0D, 192.0D, 768.0D);

            double longitudinal;
            if (type == JunctionType.CONFLUENCE) {
                double remaining = child.mouthDistance - childProbe.nearest.alongDistance();
                double downstreamTail = Math.max(16.0D,
                        parentBathy.maximumDepth() * 2.0D);
                if (remaining < -downstreamTail || remaining > blendRange) continue;
                longitudinal = remaining >= 0.0D
                        ? 1.0D - smoothstep(0.0D, blendRange, remaining)
                        : 1.0D - smoothstep(0.0D, downstreamTail, -remaining);
            } else {
                double station = childProbe.nearest.alongDistance();
                if (station < -8.0D || station > blendRange) continue;
                longitudinal = 1.0D - smoothstep(0.0D, blendRange, Math.max(0.0D, station));
            }

            if (longitudinal <= 0.0D) continue;
            boolean childFiniteMask = child.allowsFiniteWaterMask(worldX, worldZ);
            int childEnvelope = junctionDepthEnvelope(child, childProbe, finalWaterY, true);
            if (!childFiniteMask) {
                WorldPoint capAnchor = type == JunctionType.CONFLUENCE
                        ? child.confluenceAnchor.officialPoint()
                        : child.bifurcationAnchor.officialPoint();
                double tangentStation = type == JunctionType.CONFLUENCE
                        ? Math.max(0.0D, child.mouthDistance - 16.0D)
                        : Math.min(16.0D, child.mouthDistance);
                WorldPoint capTangent = child.unitTangentAt(tangentStation);
                double capDot = (worldX - capAnchor.x()) * capTangent.x()
                        + (worldZ - capAnchor.z()) * capTangent.z();
                double capOvershoot = type == JunctionType.CONFLUENCE
                        ? capDot - RiverCourse.END_CAP_ALLOWANCE
                        : -capDot - RiverCourse.END_CAP_ALLOWANCE;
                int extensionCap = Math.max(1, childBathy.maximumDepth()
                        - (int)Math.floor(Math.max(0.0D, capOvershoot)));
                childEnvelope = Math.min(childEnvelope, extensionCap);
            }
            int parentEnvelope = junctionDepthEnvelope(parent, parentProbe, finalWaterY,
                    parent.allowsFiniteWaterMask(worldX, worldZ));
            // A junction field is the smooth union of both depth envelopes, never a fixed +24
            // parent rectangle.  Outside one real mask, fade only the deepening envelope across
            // a depth-scaled approach band.  This handles rasterised mask edges without raising
            // either real channel into shallow replacement bathymetry.
            int targetDepth;
            double sharedWeight;
            if (childWater) {
                double approachRange = Math.max(24.0D,
                        Math.max(childBathy.maximumDepth(), parentBathy.maximumDepth()) * 3.0D);
                int approachAllowance = 1 + (int)Math.floor(Math.max(0.0D,
                        parentProbe.halfWaterWidth + approachRange
                                - parentProbe.nearest.distance()));
                int supportedChildEnvelope = Math.min(childEnvelope, approachAllowance);
                targetDepth = Math.max(childBathy.depth(),
                        Math.max(parentEnvelope, supportedChildEnvelope));
                sharedWeight = longitudinal;
            } else if (parentWater) {
                double approachRange = Math.max(24.0D,
                        Math.max(childBathy.maximumDepth(), parentBathy.maximumDepth()) * 3.0D);
                double approachRemaining = Math.max(0.0D,
                        childProbe.halfWaterWidth + approachRange
                                - childProbe.nearest.distance());
                int extraDepthAllowance = (int)Math.floor(approachRemaining * 0.5D);
                // Bring the parent's own one-block-slope envelope in no faster than the child
                // envelope reaches this column.  This avoids both a same-owner depth jump at the
                // edge of a junction and a zero-effect nearby tributary winning the Yodo triple.
                int supportedParentEnvelope = Math.min(parentEnvelope,
                        parentBathy.depth() + extraDepthAllowance);
                targetDepth = Math.max(parentBathy.depth(),
                        Math.max(childEnvelope, supportedParentEnvelope));
                if (targetDepth == parentBathy.depth()) continue;
                double approachWeight = clamp(approachRemaining / approachRange, 0.0D, 1.0D);
                double childSupportWeight = childEnvelope > 0 ? 1.0D : 0.20D * approachWeight;
                sharedWeight = longitudinal * childSupportWeight;
            } else {
                continue;
            }

            WorldPoint junctionAnchor = type == JunctionType.CONFLUENCE
                    ? child.confluenceAnchor.officialPoint() : child.bifurcationAnchor.officialPoint();
            double anchorDistance = Math.hypot(
                    worldX - junctionAnchor.x(), worldZ - junctionAnchor.z());

            // Defensive invariant: an approach column owned only by the child may stay at its
            // ordinary depth or deepen, but junction preparation may never raise its bed.
            if (childWater && !parentWater) {
                targetDepth = Math.max(targetDepth, childBathy.depth());
            }
            int target = finalWaterY - Math.max(1, targetDepth);
            // A parent can participate in several nearby junctions (Yodo/Katsura/Kizu is the
            // important real example).  Never return the first candidate from grid iteration: the
            // child owner and parent owner could then select different junctions and recreate an
            // underwater seam.  Pick the locally dominant explicit edge deterministically.
            double childCentreWeight = 1.0D - clamp(
                    childProbe.nearest.distance() / Math.max(1.0D, childProbe.halfWaterWidth + 24.0D),
                    0.0D, 1.0D);
            double anchorWeight = 1.0D - clamp(anchorDistance / 768.0D, 0.0D, 1.0D);
            double junctionScore = sharedWeight * 10.0D + childCentreWeight * 2.0D + anchorWeight;
            if (junctionScore > bestJunctionScore + 1.0E-9D
                    || (Math.abs(junctionScore - bestJunctionScore) <= 1.0E-9D
                    && (!bestAdjustment.active()
                    || child.name.compareTo(bestAdjustment.otherRiver()) < 0))) {
                bestJunctionScore = junctionScore;
                bestAdjustment = new JunctionBathymetryAdjustment(true, target,
                        owner.course == child ? parent.name : child.name, type, sharedWeight,
                        ownerLocalBedY);
            }
        }
        return bestAdjustment;
    }

    /**
     * Building-oriented channel depth profile.  The source guard stays shallow, but the old
     * whole-river 4..28 metre cap made broad middle/lower reaches look like flooded sheets.
     * Depth now grows independently from the hydraulic surface: narrow headwaters remain only a
     * few blocks deep, while mature lower rivers and estuaries gain enough vertical volume for
     * large bridges and waterfront construction.  This changes bed Y only; it never changes the
     * monotonic water-surface profile and therefore cannot introduce reverse flow.
     */
    private static int maximumDepthBlocks(double widthBlocks, double progress,
                                          boolean reachesSea) {
        double p = clamp(progress, 0.0D, 1.0D);
        double width = Math.max(1.0D, widthBlocks);

        // Use MC channel width directly here.  At PJ's 1:8 horizontal and 1:4 vertical scales a
        // purely metre-based hydraulic aspect ratio makes wide visual rivers unrealistically
        // shallow.  Square-root growth prevents the largest rivers from becoming trenches.
        double matureDepth = 2.0D + Math.sqrt(width) * 0.58D + p * 3.2D;
        if (reachesSea) {
            matureDepth += smoothstep(0.68D, 1.0D, p) * 4.5D;
        }

        // Preserve the successful legacy headwater treatment: the first reach transitions from
        // a small stream into the mature section instead of inheriting the lower-river depth.
        double sourceFactor = lerp(0.55D, 1.0D, smoothstep(0.08D, 0.32D, p));
        double depthBlocks = matureDepth * sourceFactor;
        return Math.max(1, (int)Math.round(clamp(depthBlocks, 2.0D, 16.0D)));
    }

    /**
     * Reconcile hydraulic surfaces only across explicit RiverGraph edges.  P1 and earlier versions
     * also blended any two coarse routes that happened to touch, which hid authored geometry bugs
     * such as Seki/Shinano and Aka/Mogami by turning an illegal crossing into continuous water.
     * Unrelated rivers now keep independent profiles and are rejected by the topology audit.
     */
    private static HydraulicAdjustment hydraulicAdjustment(CourseProbe probe,
                                                            List<CourseProbe> probes,
                                                            int originalWaterY) {
        // P1.2: actual Minecraft water is never derived from bankReferenceWaterLevel.  Explicit
        // confluences are reconciled by ConfluenceInfo against terminalWaterY, while bifurcation
        // profiles already start at sourceRiver(bifurcationParentStation).  A second continuous
        // bank-grade interpolation here was the source of the observed +1 connection terraces.
        return new HydraulicAdjustment(originalWaterY, false);
    }

    /**
     * Close only a true one-column hole in the union of intersecting river masks. Expanding every
     * river globally merely moves these raster saddles outward; checking the four cardinal columns
     * performs the minimal topological closing without widening normal banks.
     */
    private static boolean closesSingleCellWaterPocket(int worldX, int worldZ,
                                                       List<RiverCourse> candidates) {
        if (rawWaterMaskAt(worldX, worldZ, candidates)) return false;
        return rawWaterMaskAt(worldX - 1, worldZ, candidates)
                && rawWaterMaskAt(worldX + 1, worldZ, candidates)
                && rawWaterMaskAt(worldX, worldZ - 1, candidates)
                && rawWaterMaskAt(worldX, worldZ + 1, candidates);
    }

    private static boolean rawWaterMaskAt(int worldX, int worldZ,
                                          List<RiverCourse> candidates) {
        for (RiverCourse course : candidates) {
            if (!course.mayContain(worldX, worldZ)) continue;
            Nearest nearest = course.nearest(worldX, worldZ);
            if (nearest == null) continue;
            double progress = clamp(nearest.alongDistance() / course.mouthDistance,
                    0.0D, 1.0D);
            double halfWater = course.waterWidth(progress) * 0.5D
                    + course.waterRasterMargin(progress);
            if (nearest.distance() <= halfWater
                    && course.allowsFiniteWaterMask(worldX, worldZ)) return true;
        }
        return false;
    }

    public static boolean isRiverWaterWorld(int worldX, int worldZ) {
        // Course geometry may continue beneath the sea for a clean physical river mouth, but it
        // must not paint a shallow-blue river stripe across the ocean biome map. The lake system
        // handles named lagoon connections separately.
        return TerrainData.isLandWorld(worldX, worldZ) && sampleWorld(worldX, worldZ).water();
    }

    private static RiverCourse seaCourse(String name, int sourceElevationMetres,
                                         int sourceWidth, int mouthWidth, int maximumTerraceDrop,
                                         GeoPoint... points) {
        return new RiverCourse(name, sourceElevationMetres, PJChunkGenerator.SEA_LEVEL,
                true, sourceWidth, mouthWidth, maximumTerraceDrop, List.of(points));
    }

    private static RiverCourse tributaryCourse(String name, int sourceElevationMetres,
                                               int terminalElevationMetres,
                                               int sourceWidth, int mouthWidth,
                                               int maximumTerraceDrop, GeoPoint... points) {
        int terminalWaterY = PJChunkGenerator.SEA_LEVEL + (int)Math.round(
                terminalElevationMetres / TerrainData.VERTICAL_METRES_PER_BLOCK);
        return new RiverCourse(name, sourceElevationMetres, terminalWaterY,
                false, sourceWidth, mouthWidth, maximumTerraceDrop, List.of(points));
    }

    public static int courseCount() {
        return COURSES.size();
    }

    /** Package-private regression data used by the build-time core validation task. */
    static ValidationSnapshot validationSnapshot() {
        int inlandSegments = 0;
        int profileSamples = 0;
        int reverseFlowSamples = 0;
        double maximumSegmentLength = 0.0D;
        List<String> names = new ArrayList<>(COURSES.size());

        for (RiverCourse course : COURSES) {
            names.add(course.name);
            for (Segment segment : course.segments) {
                if (segment.startDistance() < course.mouthDistance) {
                    inlandSegments++;
                    maximumSegmentLength = Math.max(maximumSegmentLength, segment.length());
                }
            }
            profileSamples += course.waterProfile.length;
            for (int i = 1; i < course.waterProfile.length; i++) {
                if (course.waterProfile[i] > course.waterProfile[i - 1]) reverseFlowSamples++;
            }
        }
        return new ValidationSnapshot(COURSES.size(), inlandSegments, maximumSegmentLength,
                profileSamples, reverseFlowSamples, List.copyOf(names));
    }

    record ValidationSnapshot(int courseCount, int inlandSegmentCount,
                              double maximumSegmentLength, int profileSampleCount,
                              int reverseFlowSampleCount, List<String> names) {}

    /** Exact geometry diagnostics for the 15 authored W05 tributary connections. */
    static List<AuthoredConfluenceAudit> authoredConfluenceAuditSnapshot() {
        List<AuthoredConfluenceAudit> result = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
            RiverCourse parent = child.terminalRiver;
            WorldPoint anchor = child.confluenceAnchor.officialPoint();
            WorldPoint endpoint = child.pointAtDistance(child.mouthDistance);
            Nearest endpointParent = parent.nearestUnbounded(endpoint.x(), endpoint.z());
            double endpointParentProgress = clamp(endpointParent.alongDistance()
                    / parent.mouthDistance, 0.0D, 1.0D);
            double endpointRadius = parent.waterWidth(endpointParentProgress) * 0.5D
                    + parent.waterRasterMargin(endpointParentProgress);
            double endpointAttachmentGap = Math.max(0.0D,
                    endpointParent.distance() - endpointRadius);
            double endpointAnchorDeviation = RiverCourse.distance(endpoint, anchor);
            Nearest parentAnchor = parent.nearestUnbounded(anchor.x(), anchor.z());

            int overlapIntervals = 0;
            boolean previousOverlap = false;
            double finalIntervalStart = Double.NaN;
            List<Double> overlapIntervalStarts = new ArrayList<>();
            int overlapSamples = Math.max(1, (int)Math.ceil(child.mouthDistance / 8.0D));
            for (int sample = 0; sample <= overlapSamples; sample++) {
                double sampleStation = Math.min(child.mouthDistance, sample * 8.0D);
                WorldPoint point = child.pointAtDistance(sampleStation);
                Nearest nearestParent = parent.nearest((int)Math.round(point.x()),
                        (int)Math.round(point.z()));
                boolean overlap = false;
                if (nearestParent != null) {
                    double progress = clamp(nearestParent.alongDistance()
                            / parent.mouthDistance, 0.0D, 1.0D);
                    double radius = parent.waterWidth(progress) * 0.5D
                            + parent.waterRasterMargin(progress);
                    overlap = nearestParent.distance() <= radius;
                }
                if (overlap && !previousOverlap) {
                    overlapIntervals++;
                    finalIntervalStart = sampleStation;
                    overlapIntervalStarts.add(sampleStation);
                }
                previousOverlap = overlap;
            }
            double overshootTail = Double.isNaN(finalIntervalStart) ? Double.POSITIVE_INFINITY
                    : child.mouthDistance - finalIntervalStart;
            double allowedTail = child.naturalConfluenceTailAllowance();
            boolean prematureCrossing = overlapIntervals == 0 || overlapIntervals > 1
                    || overshootTail > allowedTail + 8.0D;
            result.add(new AuthoredConfluenceAudit(child.name, parent.name,
                    child.confluenceAnchor.officialIdentifier(), endpointAttachmentGap,
                    endpointAnchorDeviation, parentAnchor.distance(), overlapIntervals,
                    List.copyOf(overlapIntervalStarts), prematureCrossing,
                    overshootTail, allowedTail));
        }
        return List.copyOf(result);
    }

    record AuthoredConfluenceAudit(String child, String parent, String officialIdentifier,
                                   double endpointAttachmentGap,
                                   double endpointAnchorDeviation,
                                   double parentAnchorDeviation,
                                   int overlapIntervals, List<Double> overlapIntervalStarts,
                                   boolean prematureCrossing,
                                   double overshootTail, double allowedOvershootTail) {}

    /** Production-path runtime surface checks for every explicit river-river connection. */
    static List<RuntimeConnectionAudit> runtimeConnectionAuditSnapshot() {
        List<RuntimeConnectionAudit> result = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver != null) {
                int raises = 0;
                int downstreamRises = 0;
                int sampled = 0;
                int previousY = Integer.MAX_VALUE;
                double start = Math.max(0.0D, child.mouthDistance - 768.0D);
                for (double station = start; station <= child.mouthDistance; station += 4.0D) {
                    WorldPoint centre = child.pointAtDistance(station);
                    WorldPoint tangent = child.unitTangentAt(station);
                    double rawY = child.waterSurfaceY(station);
                    double progress = clamp(station / child.mouthDistance, 0.0D, 1.0D);
                    double half = child.waterWidth(progress) * 0.5D;
                    double[] offsets = {0.0D, -half * 0.25D, half * 0.25D,
                            -half * 0.50D, half * 0.50D};
                    for (double offset : offsets) {
                        int x = (int)Math.round(centre.x() - tangent.z() * offset);
                        int z = (int)Math.round(centre.z() + tangent.x() * offset);
                        RiverSample sample = sampleWorld(x, z);
                        if (!sample.water()) continue;
                        sampled++;
                        Nearest rawNearest = child.nearest(x, z);
                        int rawColumnY = rawNearest == null ? (int)rawY
                                : child.waterSurfaceY(rawNearest.alongDistance());
                        if (sample.waterSurfaceY() > rawColumnY) raises++;
                    }
                    RiverSample centreSample = sampleWorld((int)Math.round(centre.x()),
                            (int)Math.round(centre.z()));
                    if (centreSample.water()) {
                        if (previousY != Integer.MAX_VALUE
                                && centreSample.waterSurfaceY() > previousY) downstreamRises++;
                        previousY = centreSample.waterSurfaceY();
                    }
                }
                WorldPoint anchor = child.confluenceAnchor.officialPoint();
                RiverSample anchorSample = sampleWorld((int)Math.round(anchor.x()),
                        (int)Math.round(anchor.z()));
                int mismatch = !anchorSample.water()
                        || anchorSample.waterSurfaceY() != child.terminalWaterY ? 1 : 0;
                result.add(new RuntimeConnectionAudit(child.name, child.terminalRiver.name,
                        "CONFLUENCE", sampled, raises, downstreamRises, mismatch,
                        child.terminalWaterY, anchorSample.waterSurfaceY()));
            }
            if (child.sourceRiver != null) {
                int raises = 0;
                int downstreamRises = 0;
                int sampled = 0;
                int previousY = Integer.MAX_VALUE;
                for (double station = 0.0D; station <= Math.min(512.0D, child.mouthDistance);
                     station += 4.0D) {
                    WorldPoint centre = child.pointAtDistance(station);
                    int x = (int)Math.round(centre.x());
                    int z = (int)Math.round(centre.z());
                    RiverSample sample = sampleWorld(x, z);
                    if (!sample.water()) continue;
                    sampled++;
                    Nearest rawNearest = child.nearest(x, z);
                    int rawColumnY = rawNearest == null ? child.waterSurfaceY(station)
                            : child.waterSurfaceY(rawNearest.alongDistance());
                    if (sample.waterSurfaceY() > rawColumnY) raises++;
                    if (previousY != Integer.MAX_VALUE
                            && sample.waterSurfaceY() > previousY) downstreamRises++;
                    previousY = sample.waterSurfaceY();
                }
                WorldPoint anchor = child.bifurcationAnchor.officialPoint();
                RiverSample anchorSample = sampleWorld((int)Math.round(anchor.x()),
                        (int)Math.round(anchor.z()));
                int expected = child.sourceRiver.waterSurfaceY(child.bifurcationParentStation);
                int mismatch = child.sourceWaterY != expected || !anchorSample.water()
                        || anchorSample.waterSurfaceY() != expected ? 1 : 0;
                result.add(new RuntimeConnectionAudit(child.name, child.sourceRiver.name,
                        "BIFURCATION", sampled, raises, downstreamRises, mismatch,
                        expected, anchorSample.waterSurfaceY()));
            }
            if (!child.sourceLakeName.isEmpty()) {
                int raises = 0;
                int downstreamRises = 0;
                int sampled = 0;
                int previousY = Integer.MAX_VALUE;
                double end = Math.min(child.mouthDistance,
                        Math.min(512.0D, Math.max(64.0D, child.sourceConnectionLength)));
                for (double station = 0.0D; station <= end; station += 4.0D) {
                    WorldPoint centre = child.pointAtDistance(station);
                    int x = (int)Math.round(centre.x());
                    int z = (int)Math.round(centre.z());
                    RiverSample sample = sampleWorld(x, z);
                    if (!sample.water()) continue;
                    sampled++;
                    Nearest rawNearest = child.nearest(x, z);
                    int rawColumnY = rawNearest == null ? child.waterSurfaceY(station)
                            : child.waterSurfaceY(rawNearest.alongDistance());
                    if (sample.waterSurfaceY() > rawColumnY) raises++;
                    if (previousY != Integer.MAX_VALUE
                            && sample.waterSurfaceY() > previousY) downstreamRises++;
                    previousY = sample.waterSurfaceY();
                }
                WorldPoint source = child.pointAtDistance(0.0D);
                RiverSample sourceSample = sampleWorld((int)Math.round(source.x()),
                        (int)Math.round(source.z()));
                int expected = LakeData.waterSurfaceY(child.sourceLakeName);
                int mismatch = child.sourceWaterY != expected || !sourceSample.water()
                        || sourceSample.waterSurfaceY() != expected ? 1 : 0;
                result.add(new RuntimeConnectionAudit(child.name, child.sourceLakeName,
                        "SOURCE_LAKE", sampled, raises, downstreamRises, mismatch,
                        expected, sourceSample.waterSurfaceY()));
            }
            if (!child.terminalLakeName.isEmpty()) {
                int raises = 0;
                int downstreamRises = 0;
                int sampled = 0;
                int previousY = Integer.MAX_VALUE;
                double span = Math.min(512.0D, Math.max(64.0D, child.terminalConnectionLength));
                double start = Math.max(0.0D, child.mouthDistance - span);
                for (double station = start; station <= child.mouthDistance; station += 4.0D) {
                    WorldPoint centre = child.pointAtDistance(station);
                    int x = (int)Math.round(centre.x());
                    int z = (int)Math.round(centre.z());
                    RiverSample sample = sampleWorld(x, z);
                    if (!sample.water()) continue;
                    sampled++;
                    Nearest rawNearest = child.nearest(x, z);
                    int rawColumnY = rawNearest == null ? child.waterSurfaceY(station)
                            : child.waterSurfaceY(rawNearest.alongDistance());
                    if (sample.waterSurfaceY() > rawColumnY) raises++;
                    if (previousY != Integer.MAX_VALUE
                            && sample.waterSurfaceY() > previousY) downstreamRises++;
                    previousY = sample.waterSurfaceY();
                }
                WorldPoint mouth = child.pointAtDistance(child.mouthDistance);
                RiverSample mouthSample = sampleWorld((int)Math.round(mouth.x()),
                        (int)Math.round(mouth.z()));
                int expected = LakeData.waterSurfaceY(child.terminalLakeName);
                int mismatch = child.terminalWaterY != expected || !mouthSample.water()
                        || mouthSample.waterSurfaceY() != expected ? 1 : 0;
                result.add(new RuntimeConnectionAudit(child.name, child.terminalLakeName,
                        "TERMINAL_LAKE", sampled, raises, downstreamRises, mismatch,
                        expected, mouthSample.waterSurfaceY()));
            }
        }
        return List.copyOf(result);
    }

    record RuntimeConnectionAudit(String river, String otherRiver, String relation,
                                  int sampledColumns, int runtimeRaiseAboveProfile,
                                  int downstreamRises, int anchorMismatch,
                                  int connectionLevelY, int sampledAnchorY) {}

    /** First real parent-water contact, rather than only the W05 centre-line anchor. */
    static List<TerminalHydraulicAudit> terminalHydraulicApproachAuditSnapshot() {
        List<TerminalHydraulicAudit> result = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
            ParentWaterContact contact = firstParentWaterMaskContact(child);
            RiverCourse parent = child.terminalRiver;
            WorldPoint anchor = child.confluenceAnchor.officialPoint();
            Nearest parentAnchor = parent.nearestUnbounded(anchor.x(), anchor.z());
            int anchorChildY = child.waterSurfaceY(child.mouthDistance);
            int anchorParentY = parent.waterSurfaceY(parentAnchor.alongDistance());
            int contactMismatch = contact.found()
                    ? Math.abs(contact.childY() - contact.parentY()) : Integer.MAX_VALUE;
            double parentContactToAnchor = contact.found()
                    ? Math.abs(parentAnchor.alongDistance() - contact.parentStation()) : Double.NaN;
            result.add(new TerminalHydraulicAudit(child.name, parent.name, contact.found(),
                    contact.childStation(), child.mouthDistance - contact.childStation(),
                    contact.parentStation(), parentContactToAnchor, contact.childY(), contact.parentY(),
                    contactMismatch, anchorChildY, anchorParentY,
                    Math.abs(anchorChildY - anchorParentY)));
        }
        return List.copyOf(result);
    }

    record TerminalHydraulicAudit(String child, String parent, boolean firstContactFound,
                                  double firstWaterMaskContactStation,
                                  double firstContactToAnchor, double parentStation,
                                  double parentContactToAnchor,
                                  int firstContactChildY, int firstContactParentY,
                                  int firstContactLevelMismatch, int anchorChildY,
                                  int anchorParentY, int anchorMismatch) {}

    /**
     * AR-10 geometry/contact metadata for the 15 authored terminalRiver junctions.
     * This is intentionally geometry-only: CF-03 owns the final hydraulic refit after all
     * river/lake contacts are frozen.
     */
    static List<ARConfluenceContactMetadata> authoredConfluenceContactMetadataSnapshot() {
        List<ARConfluenceContactMetadata> result = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
            RiverCourse parent = child.terminalRiver;
            ParentWaterGeometryContact geometry = firstParentWaterMaskGeometryContact(child);
            WorldPoint anchor = child.confluenceAnchor.officialPoint();
            Nearest parentAnchor = parent.nearestUnbounded(anchor.x(), anchor.z());
            if (!geometry.found()) {
                result.add(new ARConfluenceContactMetadata(child.name, parent.name, false,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
                continue;
            }
            WorldPoint contact = child.pointAtDistance(geometry.childStation());
            int childY = child.waterSurfaceY(geometry.childStation());
            int parentY = parent.waterSurfaceY(geometry.parentStation());
            int anchorChildY = child.waterSurfaceY(child.mouthDistance);
            int anchorParentY = parent.waterSurfaceY(parentAnchor.alongDistance());
            result.add(new ARConfluenceContactMetadata(child.name, parent.name, true,
                    (int)Math.round(contact.x()), (int)Math.round(contact.z()),
                    geometry.childStation(), geometry.parentStation(),
                    geometry.childStation(), child.mouthDistance - geometry.childStation(),
                    Math.abs(parentAnchor.alongDistance() - geometry.parentStation()),
                    child.mouthDistance, Math.abs(childY - parentY),
                    Math.abs(anchorChildY - anchorParentY)));
        }
        return List.copyOf(result);
    }

    record ARConfluenceContactMetadata(String child, String parent, boolean firstContactFound,
                                       int contactX, int contactZ, double childContactStation,
                                       double parentJunctionStation, double availableApproachLength,
                                       double childTailAfterContact, double parentContactToOfficialAnchor,
                                       double childRouteLength, int contactLevelMismatch, int anchorMismatch) {}

    /**
     * AR-11 physical river/lake contact snapshot using the LR-FINAL shoreline and the AR-09
     * runtime RiverCourse geometry. Missing declared contacts are metadata only and are carried
     * forward as REVIEW_CF_REBUILD; this stage never drags a river to a lake.
     */
    static List<ARRiverLakeContactMetadata> authoredRiverLakeContactMetadataSnapshot() {
        List<ARRiverLakeContactMetadata> result = new ArrayList<>();
        Map<String, LakeData.LakeMetadata> lakeByName = new HashMap<>();
        for (LakeData.LakeMetadata lake : LakeData.metadata()) lakeByName.putIfAbsent(lake.name(), lake);

        for (RiverCourse course : COURSES) {
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
            String officialId = reference == null ? "" : reference.officialIdentifier();
            Map<String, ExpectedLakeContact> expected = expectedLakeContacts(course, officialId);
            Set<String> foundExpected = new HashSet<>();

            double step = 8.0D;
            int sampleCount = Math.max(1, (int)Math.ceil(course.mouthDistance / step));
            LakeOwner previousOwner = lakeOwnerAtStation(course, 0.0D, officialId, lakeByName);
            LakeOwner runOwner = previousOwner;
            double runStart = previousOwner.present() ? 0.0D : Double.NaN;
            double previousStation = 0.0D;

            for (int i = 1; i <= sampleCount; i++) {
                double station = Math.min(course.mouthDistance, i * step);
                LakeOwner owner = lakeOwnerAtStation(course, station, officialId, lakeByName);
                if (!sameLakeOwner(owner, runOwner)) {
                    if (runOwner.present()) {
                        double last = refineLakeBoundary(course, runOwner, previousStation, station, false);
                        addLakeContactRun(result, foundExpected, course, officialId, expected,
                                runOwner, runStart, last);
                    }
                    if (owner.present()) {
                        runStart = refineLakeBoundary(course, owner, previousStation, station, true);
                    } else {
                        runStart = Double.NaN;
                    }
                    runOwner = owner;
                }
                previousOwner = owner;
                previousStation = station;
            }
            if (runOwner.present()) {
                addLakeContactRun(result, foundExpected, course, officialId, expected, runOwner,
                        runStart, course.mouthDistance);
            }

            for (ExpectedLakeContact e : expected.values()) {
                if (foundExpected.contains(e.key())) continue;
                result.add(new ARRiverLakeContactMetadata(course.name, officialId, e.relation(),
                        e.lakeId(), e.lakeName(), true, false, Double.NaN, Double.NaN,
                        Double.NaN, Integer.MIN_VALUE, Integer.MIN_VALUE,
                        "REVIEW_CF_REBUILD"));
            }
        }
        return List.copyOf(result);
    }


    /**
     * CF-01 bridge seeds for declared contacts that AR-11 could not realize without moving frozen
     * geometry.  FinalConnectionData turns only these short gaps into connection-only water masks.
     */
    static List<CFLakeGateSeed> cfMissingRiverLakeGateSeeds() {
        List<CFLakeGateSeed> result = new ArrayList<>();
        for (ARRiverLakeContactMetadata contact : authoredRiverLakeContactMetadataSnapshot()) {
            if (!contact.expected() || contact.found() || contact.relation().startsWith("ILLEGAL")) continue;
            RiverCourse course = named(COURSES, contact.river());
            LakeData.LakeMetadata lake = LakeData.metadataById(contact.lakeId());
            if (lake == null) lake = lakeMetadataByName(contact.lakeName());
            if (lake == null) throw new IllegalStateException("CF-01 missing lake metadata " + contact.lakeId());
            LakeData.LakeSpatialCandidate candidate = null;
            for (LakeData.LakeSpatialCandidate c : LakeData.spatialAuditCandidates()) {
                if (c.active() && c.lakeId().equals(lake.lakeId())) { candidate = c; break; }
            }
            if (candidate == null) throw new IllegalStateException("CF-01 inactive/missing lake " + lake.lakeId());

            int lakeY = lake.waterSurfaceY();
            double riverStation;
            if (contact.relation().equals("SOURCE_FROM_LAKE")) {
                riverStation = 0.0D;
            } else if (contact.relation().equals("TERMINAL_INTO_LAKE")) {
                riverStation = course.mouthDistance;
            } else if (contact.relation().equals("DECLARED_OUTLET")) {
                // Lake -> river may only join a rendered river column at or below lake Y. Picking
                // the geometrically closest but hydraulically higher sample would manufacture an
                // impossible uphill outlet (Miya/Miyagawa reservoir is the important canary).
                riverStation = nearestRuntimeStationToLake(course, lake.lakeId(), lakeY, true);
            } else if (contact.relation().equals("DECLARED_INLET")) {
                riverStation = nearestRuntimeStationToLake(course, lake.lakeId(), lakeY, false);
            } else {
                riverStation = nearestStationToLake(course, lake.lakeId());
            }
            WorldPoint riverPoint = course.pointAtDistance(riverStation);
            int riverX = (int)Math.round(riverPoint.x()), riverZ = (int)Math.round(riverPoint.z());
            LakeData.AuditPoint lakePoint = nearestRenderedLakePoint(candidate, lake, riverX, riverZ);
            RiverSample renderedRiver = sampleWorld(riverX, riverZ);
            int riverY = renderedRiver.water() && renderedRiver.name().equals(course.name)
                    ? renderedRiver.waterSurfaceY() : course.waterSurfaceY(riverStation);
            boolean lakeToRiver = contact.relation().equals("SOURCE_FROM_LAKE")
                    || contact.relation().equals("DECLARED_OUTLET")
                    || (contact.relation().equals("DECLARED_CHANNEL") && riverY < lakeY);
            result.add(new CFLakeGateSeed(course.name, contact.officialIdentifier(), contact.relation(),
                    lake.lakeId(), lake.name(), riverX, riverZ, riverStation, riverY,
                    lakePoint.x(), lakePoint.z(), lakeY, lakeToRiver));
        }
        return List.copyOf(result);
    }

    private static double nearestStationToLake(RiverCourse course, String lakeId) {
        double bestStation = 0.0D;
        double bestDistance = Double.POSITIVE_INFINITY;
        final double coarse = 24.0D;
        int samples = Math.max(1, (int)Math.ceil(course.mouthDistance / coarse));
        for (int i = 0; i <= samples; i++) {
            double station = Math.min(course.mouthDistance, i * coarse);
            WorldPoint p = course.pointAtDistance(station);
            LakeData.SpatialDistance d = LakeData.spatialAuditDistance(lakeId,
                    (int)Math.round(p.x()), (int)Math.round(p.z()));
            double distance = d.inside() ? 0.0D : d.distanceToShore();
            if (distance < bestDistance) { bestDistance = distance; bestStation = station; }
        }
        double low = Math.max(0.0D, bestStation - coarse);
        double high = Math.min(course.mouthDistance, bestStation + coarse);
        for (double station = low; station <= high; station += 1.0D) {
            WorldPoint p = course.pointAtDistance(station);
            LakeData.SpatialDistance d = LakeData.spatialAuditDistance(lakeId,
                    (int)Math.round(p.x()), (int)Math.round(p.z()));
            double distance = d.inside() ? 0.0D : d.distanceToShore();
            if (distance < bestDistance) { bestDistance = distance; bestStation = station; }
        }
        return bestStation;
    }



    private static double nearestRuntimeStationToLake(RiverCourse course, String lakeId,
                                                       int lakeY, boolean atOrBelow) {
        double bestStation = Double.NaN;
        double bestDistance = Double.POSITIVE_INFINITY;
        final double coarse = 8.0D;
        int samples = Math.max(1, (int)Math.ceil(course.mouthDistance / coarse));
        for (int i = 0; i <= samples; i++) {
            double station = Math.min(course.mouthDistance, i * coarse);
            WorldPoint p = course.pointAtDistance(station);
            int x = (int)Math.round(p.x()), z = (int)Math.round(p.z());
            RiverSample rendered = sampleWorld(x, z);
            if (!rendered.water() || !rendered.name().equals(course.name)) continue;
            int riverY = rendered.waterSurfaceY();
            if (atOrBelow ? riverY > lakeY : riverY < lakeY) continue;
            LakeData.SpatialDistance d = LakeData.spatialAuditDistance(lakeId, x, z);
            double distance = d.inside() ? 0.0D : d.distanceToShore();
            if (distance < bestDistance) { bestDistance = distance; bestStation = station; }
        }
        if (!Double.isFinite(bestStation)) {
            return nearestStationToLake(course, lakeId, lakeY, atOrBelow);
        }
        return bestStation;
    }

    private static double nearestStationToLake(RiverCourse course, String lakeId,
                                                int lakeY, boolean atOrBelow) {
        double bestStation = Double.NaN;
        double bestDistance = Double.POSITIVE_INFINITY;
        final double coarse = 16.0D;
        int samples = Math.max(1, (int)Math.ceil(course.mouthDistance / coarse));
        for (int i = 0; i <= samples; i++) {
            double station = Math.min(course.mouthDistance, i * coarse);
            int riverY = course.waterSurfaceY(station);
            if (atOrBelow ? riverY > lakeY : riverY < lakeY) continue;
            WorldPoint p = course.pointAtDistance(station);
            LakeData.SpatialDistance d = LakeData.spatialAuditDistance(lakeId,
                    (int)Math.round(p.x()), (int)Math.round(p.z()));
            double distance = d.inside() ? 0.0D : d.distanceToShore();
            if (distance < bestDistance) { bestDistance = distance; bestStation = station; }
        }
        if (!Double.isFinite(bestStation)) return nearestStationToLake(course, lakeId);
        double low = Math.max(0.0D, bestStation - coarse);
        double high = Math.min(course.mouthDistance, bestStation + coarse);
        for (double station = low; station <= high; station += 1.0D) {
            int riverY = course.waterSurfaceY(station);
            if (atOrBelow ? riverY > lakeY : riverY < lakeY) continue;
            WorldPoint p = course.pointAtDistance(station);
            LakeData.SpatialDistance d = LakeData.spatialAuditDistance(lakeId,
                    (int)Math.round(p.x()), (int)Math.round(p.z()));
            double distance = d.inside() ? 0.0D : d.distanceToShore();
            if (distance < bestDistance) { bestDistance = distance; bestStation = station; }
        }
        return bestStation;
    }

    private static LakeData.AuditPoint nearestRenderedLakePoint(LakeData.LakeSpatialCandidate candidate,
                                                                 LakeData.LakeMetadata lake,
                                                                 int riverX, int riverZ) {
        LakeData.AuditPoint boundary = null;
        double best = Double.POSITIVE_INFINITY;
        for (LakeData.AuditPoint p : candidate.boundarySamples()) {
            double d = Math.hypot(p.x() - riverX, p.z() - riverZ);
            if (d < best) { best = d; boundary = p; }
        }
        if (boundary == null) boundary = new LakeData.AuditPoint(lake.representativeX(), lake.representativeZ());
        int rx = lake.representativeX(), rz = lake.representativeZ();
        double dx = rx - boundary.x(), dz = rz - boundary.z();
        double len = Math.max(1.0D, Math.hypot(dx, dz));
        for (int inset = 0; inset <= Math.min(256, (int)Math.ceil(len)); inset += 2) {
            int x = (int)Math.round(boundary.x() + dx / len * inset);
            int z = (int)Math.round(boundary.z() + dz / len * inset);
            LakeData.LakeSample sample = LakeData.sampleWorld(x, z);
            if (sample.water() && sample.name().equals(lake.name())
                    && sample.rendersWaterOn(TerrainData.sampleWorld(x, z))) {
                return new LakeData.AuditPoint(x, z);
            }
        }
        LakeData.LakeSample representative = LakeData.sampleWorld(rx, rz);
        if (representative.water() && representative.name().equals(lake.name())) {
            return new LakeData.AuditPoint(rx, rz);
        }
        throw new IllegalStateException("CF-01 cannot find rendered lake contact for " + lake.lakeId());
    }

    record CFLakeGateSeed(String river, String officialIdentifier, String relation,
                          String lakeId, String lakeName, int riverX, int riverZ,
                          double riverStation, int riverY, int lakeX, int lakeZ, int lakeY,
                          boolean lakeToRiver) {}

    private static Map<String, ExpectedLakeContact> expectedLakeContacts(RiverCourse course, String officialId) {
        Map<String, ExpectedLakeContact> expected = new LinkedHashMap<>();
        if (!course.sourceLakeName.isEmpty()) {
            LakeData.LakeMetadata lake = lakeMetadataByName(course.sourceLakeName);
            addExpected(expected, lake, course.sourceLakeName, "SOURCE_FROM_LAKE");
        }
        if (!course.terminalLakeName.isEmpty()) {
            LakeData.LakeMetadata lake = lakeMetadataByName(course.terminalLakeName);
            addExpected(expected, lake, course.terminalLakeName, "TERMINAL_INTO_LAKE");
        }
        if (!officialId.isEmpty()) {
            for (LakeData.LakeMetadata lake : LakeData.metadata()) {
                boolean inlet = containsId(lake.inletRiverIds(), officialId)
                        || containsId(lake.terminalRiverIds(), officialId);
                boolean outlet = containsId(lake.outletRiverIds(), officialId)
                        || containsId(lake.sourceRiverIds(), officialId);
                if (inlet && outlet) addExpected(expected, lake, lake.name(), "DECLARED_CHANNEL");
                else if (inlet) addExpected(expected, lake, lake.name(), "DECLARED_INLET");
                else if (outlet) addExpected(expected, lake, lake.name(), "DECLARED_OUTLET");
            }
        }
        return expected;
    }

    private static void addExpected(Map<String, ExpectedLakeContact> expected,
                                    LakeData.LakeMetadata lake, String lakeName, String relation) {
        String id = lake == null ? "" : lake.lakeId();
        ExpectedLakeContact value = new ExpectedLakeContact(id, lakeName, relation);
        expected.putIfAbsent(value.key(), value);
    }

    private static LakeData.LakeMetadata lakeMetadataByName(String name) {
        for (LakeData.LakeMetadata lake : LakeData.metadata()) if (lake.name().equals(name)) return lake;
        return null;
    }

    private static void addLakeContactRun(List<ARRiverLakeContactMetadata> out, Set<String> foundExpected,
                                          RiverCourse course, String officialId,
                                          Map<String, ExpectedLakeContact> expected, LakeOwner owner,
                                          double firstStation, double lastStation) {
        String relation = owner.reason();
        ExpectedLakeContact matched = expected.get(owner.key() + "|" + relation);
        if (matched == null) {
            for (ExpectedLakeContact e : expected.values()) {
                if (e.matchesOwner(owner) && compatibleLakeRelation(e.relation(), relation)) {
                    matched = e;
                    relation = e.relation();
                    break;
                }
            }
        }
        boolean isExpected = matched != null;
        if (matched != null) foundExpected.add(matched.key());
        double physicalStation = switch (relation) {
            case "SOURCE_FROM_LAKE", "DECLARED_OUTLET" -> lastStation;
            default -> firstStation;
        };
        WorldPoint p = course.pointAtDistance(clamp(physicalStation, 0.0D, course.mouthDistance));
        String status = relation.startsWith("ILLEGAL") ? "ILLEGAL_LAKE_INTERSECTION"
                : "PHYSICAL_CONTACT_FOUND";
        out.add(new ARRiverLakeContactMetadata(course.name, officialId, relation, owner.lakeId(),
                owner.lakeName(), isExpected, true, firstStation, lastStation, physicalStation,
                (int)Math.round(p.x()), (int)Math.round(p.z()), status));
    }

    private static boolean compatibleLakeRelation(String expected, String found) {
        if (expected.equals(found)) return true;
        return expected.equals("DECLARED_CHANNEL")
                && (found.equals("DECLARED_INLET") || found.equals("DECLARED_OUTLET")
                || found.equals("DECLARED_CHANNEL"));
    }

    private static double refineLakeBoundary(RiverCourse course, LakeOwner owner,
                                             double a, double b, boolean entering) {
        double low = Math.min(a, b), high = Math.max(a, b);
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) * 0.5D;
            boolean inside = lakeOwnerMatchesAtStation(course, owner, mid);
            if (entering) {
                if (inside) high = mid; else low = mid;
            } else {
                if (inside) low = mid; else high = mid;
            }
        }
        return entering ? high : low;
    }

    private static boolean lakeOwnerMatchesAtStation(RiverCourse course, LakeOwner owner, double station) {
        WorldPoint p = course.pointAtDistance(clamp(station, 0.0D, course.mouthDistance));
        int x = (int)Math.round(p.x()), z = (int)Math.round(p.z());
        LakeData.LakeSample sample = LakeData.sampleWorld(x, z);
        if (!sample.water() || !sample.name().equals(owner.lakeName())) return false;
        String id = activeLakeIdAt(sample.name(), x, z);
        return owner.lakeId().isEmpty() || owner.lakeId().equals(id);
    }

    private static LakeOwner lakeOwnerAtStation(RiverCourse course, double station, String officialId,
                                                Map<String, LakeData.LakeMetadata> lakeByName) {
        WorldPoint p = course.pointAtDistance(clamp(station, 0.0D, course.mouthDistance));
        int x = (int)Math.round(p.x()), z = (int)Math.round(p.z());
        LakeData.LakeSample sample = LakeData.sampleWorld(x, z);
        if (!sample.water() || sample.name().isEmpty()) return LakeOwner.NONE;
        String id = activeLakeIdAt(sample.name(), x, z);
        LakeData.LakeMetadata metadata = id.isEmpty() ? lakeByName.get(sample.name()) : LakeData.metadataById(id);
        String reason = legalLakeIntersectionReason(course, sample.name(), officialId, metadata);
        return new LakeOwner(id, sample.name(), reason);
    }

    private static boolean sameLakeOwner(LakeOwner a, LakeOwner b) {
        if (!a.present() && !b.present()) return true;
        return a.present() && b.present() && a.key().equals(b.key());
    }

    private record LakeOwner(String lakeId, String lakeName, String reason) {
        private static final LakeOwner NONE = new LakeOwner("", "", "");
        boolean present() { return !lakeName.isEmpty(); }
        String key() { return lakeId.isEmpty() ? lakeName : lakeId; }
    }

    private record ExpectedLakeContact(String lakeId, String lakeName, String relation) {
        String ownerKey() { return lakeId.isEmpty() ? lakeName : lakeId; }
        String key() { return ownerKey() + "|" + relation; }
        boolean matchesOwner(LakeOwner owner) { return ownerKey().equals(owner.key()); }
    }

    record ARRiverLakeContactMetadata(String river, String officialIdentifier, String relation,
                                      String lakeId, String lakeName, boolean expected, boolean found,
                                      double firstWaterStation, double lastWaterStation,
                                      double physicalContactStation, int contactX, int contactZ,
                                      String status) {}

    enum JunctionLevelPolicyClass {
        ALREADY_EQUAL, PARENT_HIGHER, CHILD_HIGHER, NEEDS_LONG_BLEND, MANUAL_REVIEW
    }

    /** J-05 deterministic policy view. It is diagnostic and never mutates generation. */
    static List<JunctionLevelPolicy> junctionLevelPolicyReportSnapshot() {
        List<JunctionLevelPolicy> result = new ArrayList<>();
        for (TerminalHydraulicAudit audit : terminalHydraulicApproachAuditSnapshot()) {
            JunctionLevelPolicyClass classification;
            String lowerSide = "NONE";
            double contactToAnchor = 0.0D;
            double availableApproach = 0.0D;
            double recommendedHold = 0.0D;
            double recommendedBlend = 0.0D;
            int signedDelta = audit.firstContactFound()
                    ? audit.firstContactChildY() - audit.firstContactParentY() : Integer.MIN_VALUE;

            if (!audit.firstContactFound() || audit.anchorMismatch() != 0) {
                classification = JunctionLevelPolicyClass.MANUAL_REVIEW;
            } else if (signedDelta == 0) {
                classification = JunctionLevelPolicyClass.ALREADY_EQUAL;
            } else {
                boolean childHigher = signedDelta > 0;
                lowerSide = childHigher ? "CHILD" : "PARENT";
                contactToAnchor = childHigher ? audit.firstContactToAnchor()
                        : audit.parentContactToAnchor();
                RiverCourse child = named(COURSES, audit.child());
                RiverCourse parent = named(COURSES, audit.parent());
                if (childHigher) {
                    availableApproach = child.mouthDistance;
                } else {
                    Nearest anchor = parent.nearestUnbounded(
                            child.confluenceAnchor.officialPoint().x(),
                            child.confluenceAnchor.officialPoint().z());
                    availableApproach = anchor.alongDistance();
                }
                recommendedHold = clamp(roundUp64(contactToAnchor + 64.0D),
                        128.0D, 384.0D);
                recommendedBlend = roundUp64(Math.max(recommendedHold * 3.0D,
                        recommendedHold + 256.0D));
                classification = recommendedBlend > availableApproach
                        ? JunctionLevelPolicyClass.NEEDS_LONG_BLEND
                        : (childHigher ? JunctionLevelPolicyClass.CHILD_HIGHER
                        : JunctionLevelPolicyClass.PARENT_HIGHER);
            }
            result.add(new JunctionLevelPolicy(audit.child(), audit.parent(), classification,
                    signedDelta, contactToAnchor, availableApproach, recommendedHold,
                    recommendedBlend, lowerSide));
        }
        return List.copyOf(result);
    }

    private static double roundUp64(double value) {
        return Math.ceil(Math.max(0.0D, value) / 64.0D) * 64.0D;
    }

    record JunctionLevelPolicy(String child, String parent,
                               JunctionLevelPolicyClass classification, int deltaY,
                               double contactToAnchor, double availableApproach,
                               double recommendedHold, double recommendedTotalBlend,
                               String sideToLower) {}

    private static ParentWaterContact firstParentWaterMaskContact(RiverCourse child) {
        ParentWaterGeometryContact geometry = firstParentWaterMaskGeometryContact(child);
        if (!geometry.found()) return ParentWaterContact.NONE;
        return new ParentWaterContact(true, geometry.childStation(), geometry.parentStation(),
                child.waterSurfaceY(geometry.childStation()),
                child.terminalRiver.waterSurfaceY(geometry.parentStation()));
    }

    private static ParentWaterGeometryContact firstParentWaterMaskGeometryContact(
            RiverCourse child) {
        RiverCourse parent = child.terminalRiver;
        if (parent == null) return ParentWaterGeometryContact.NONE;
        final double step = 8.0D;
        boolean previous = false;
        double previousStation = 0.0D;
        int samples = Math.max(1, (int)Math.ceil(child.mouthDistance / step));
        for (int sample = 0; sample <= samples; sample++) {
            double station = Math.min(child.mouthDistance, sample * step);
            boolean overlap = childCentreInsideParentWater(child, parent, station);
            if (overlap && !previous) {
                double low = Math.max(0.0D, previousStation);
                double high = station;
                for (int iteration = 0; iteration < 24; iteration++) {
                    double mid = (low + high) * 0.5D;
                    if (childCentreInsideParentWater(child, parent, mid)) high = mid;
                    else low = mid;
                }
                WorldPoint point = child.pointAtDistance(high);
                Nearest pn = parent.nearestUnbounded(point.x(), point.z());
                return new ParentWaterGeometryContact(true, high, pn.alongDistance());
            }
            previous = overlap;
            previousStation = station;
        }
        return ParentWaterGeometryContact.NONE;
    }

    private static boolean childCentreInsideParentWater(RiverCourse child, RiverCourse parent,
                                                         double childStation) {
        WorldPoint point = child.pointAtDistance(childStation);
        Nearest pn = parent.nearestUnbounded(point.x(), point.z());
        double pp = clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D);
        double radius = parent.waterWidth(pp) * 0.5D + parent.waterRasterMargin(pp);
        return pn.distance() <= radius
                && parent.allowsFiniteWaterMask((int)Math.round(point.x()),
                (int)Math.round(point.z()));
    }

    private record ParentWaterContact(boolean found, double childStation,
                                      double parentStation, int childY, int parentY) {
        private static final ParentWaterContact NONE = new ParentWaterContact(false,
                Double.NaN, Double.NaN, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    private record ParentWaterGeometryContact(boolean found, double childStation,
                                              double parentStation) {
        private static final ParentWaterGeometryContact NONE =
                new ParentWaterGeometryContact(false, Double.NaN, Double.NaN);
    }

    private static boolean junctionCoreAt(RiverCourse child, RiverCourse parent,
                                          String relation, int x, int z) {
        Nearest cn = child.nearest(x, z);
        Nearest pn = parent.nearest(x, z);
        if (cn == null || pn == null) return false;
        double cp = clamp(cn.alongDistance() / child.mouthDistance, 0.0D, 1.0D);
        double pp = clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D);
        double ch = child.waterWidth(cp) * 0.5D + child.waterRasterMargin(cp);
        double ph = parent.waterWidth(pp) * 0.5D + parent.waterRasterMargin(pp);
        if (cn.distance() > ch + 16.0D || pn.distance() > ph + 16.0D) return false;
        if (relation.equals("CONFLUENCE")) {
            double remaining = child.mouthDistance - cn.alongDistance();
            return remaining >= -8.0D && remaining <= 768.0D;
        }
        return cn.alongDistance() >= -8.0D && cn.alongDistance() <= 768.0D;
    }

    private static double junctionUnionInterior(RiverCourse child, RiverCourse parent,
                                                int x, int z) {
        double interior = 0.0D;
        RiverCourse[] courses = {child, parent};
        for (RiverCourse course : courses) {
            Nearest nearest = course.nearest(x, z);
            if (nearest == null || !course.allowsFiniteWaterMask(x, z)) continue;
            double progress = clamp(nearest.alongDistance() / course.mouthDistance, 0.0D, 1.0D);
            double halfWater = course.waterWidth(progress) * 0.5D
                    + course.waterRasterMargin(progress);
            if (nearest.distance() <= halfWater) {
                interior = Math.max(interior, halfWater - nearest.distance());
            }
        }
        return interior;
    }

    private static int ownerLocalDepthAt(RiverSample sample, int x, int z) {
        RiverCourse owner = named(COURSES, sample.name());
        Nearest nearest = owner.nearest(x, z);
        if (nearest == null) return Math.max(1, sample.waterSurfaceY() - sample.bedY());
        double progress = clamp(nearest.alongDistance() / owner.mouthDistance, 0.0D, 1.0D);
        double width = owner.waterWidth(progress);
        return localBathymetry(owner, nearest, progress, width,
                sample.waterSurfaceY(), true).depth();
    }

    private static int largestConnectedPatch(boolean[] mask, int side) {
        boolean[] visited = new boolean[mask.length];
        int[] queue = new int[mask.length];
        int largest = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || visited[start]) continue;
            int head = 0, tail = 0, size = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int current = queue[head++];
                size++;
                int x = current % side;
                int z = current / side;
                if (x > 0) tail = enqueuePatch(current - 1, mask, visited, queue, tail);
                if (x + 1 < side) tail = enqueuePatch(current + 1, mask, visited, queue, tail);
                if (z > 0) tail = enqueuePatch(current - side, mask, visited, queue, tail);
                if (z + 1 < side) tail = enqueuePatch(current + side, mask, visited, queue, tail);
            }
            largest = Math.max(largest, size);
        }
        return largest;
    }

    private static int enqueuePatch(int index, boolean[] mask, boolean[] visited,
                                    int[] queue, int tail) {
        if (mask[index] && !visited[index]) {
            visited[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    /** Production-path 2-D bed checks around all explicit confluences and bifurcations. */
    static List<JunctionBathymetryAudit> junctionBathymetryAuditSnapshot() {
        List<JunctionBathymetryAudit> result = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            RiverCourse parent;
            WorldPoint anchor;
            String relation;
            if (child.terminalRiver != null) {
                parent = child.terminalRiver; anchor = child.confluenceAnchor.officialPoint();
                relation = "CONFLUENCE";
            } else if (child.sourceRiver != null) {
                parent = child.sourceRiver; anchor = child.bifurcationAnchor.officialPoint();
                relation = "BIFURCATION";
            } else continue;

            Nearest cn = child.nearestUnbounded(anchor.x(), anchor.z());
            Nearest pn = parent.nearestUnbounded(anchor.x(), anchor.z());
            double cw = child.waterWidth(clamp(cn.alongDistance() / child.mouthDistance, 0.0D, 1.0D));
            double pw = parent.waterWidth(clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D));
            int radius = (int)Math.ceil(Math.max(cw, pw) * 0.5D + 48.0D);
            int maxFlatStep = 0;
            int worstStepX = 0, worstStepZ = 0;
            int ownerBoundaryMax = 0;
            int ownerBoundaryMaxDepthStep = 0;
            int ownerWorstX = 0, ownerWorstZ = 0;
            int ridgeFailures = 0;
            int pitFailures = 0;
            int sampled = 0;
            int minX = (int)Math.floor(anchor.x()) - radius;
            int maxX = (int)Math.ceil(anchor.x()) + radius;
            int minZ = (int)Math.floor(anchor.z()) - radius;
            int maxZ = (int)Math.ceil(anchor.z()) + radius;
            for (int x = minX; x <= maxX; x += 2) {
                for (int z = minZ; z <= maxZ; z += 2) {
                    RiverSample c = sampleWorld(x, z);
                    if (!c.water() || !junctionCoreAt(child, parent, relation, x, z)) continue;
                    sampled++;
                    RiverSample east = sampleWorld(x + 1, z);
                    RiverSample south = sampleWorld(x, z + 1);
                    RiverSample[] neighbours = {east, south};
                    int[][] delta = {{1,0},{0,1}};
                    for (int ni = 0; ni < neighbours.length; ni++) {
                        RiverSample n = neighbours[ni];
                        int nx = x + delta[ni][0], nz = z + delta[ni][1];
                        if (!n.water() || !junctionCoreAt(child, parent, relation, nx, nz)) continue;
                        int step = Math.abs(c.bedY() - n.bedY());
                        if (c.waterSurfaceY() == n.waterSurfaceY() && step > maxFlatStep) {
                            maxFlatStep = step; worstStepX = x; worstStepZ = z;
                        }
                        if (!c.name().equals(n.name()) && step > ownerBoundaryMax) {
                            ownerBoundaryMax = step;
                            ownerWorstX = x;
                            ownerWorstZ = z;
                        }
                        if (!c.name().equals(n.name())) {
                            int depthStep = Math.abs((c.waterSurfaceY() - c.bedY())
                                    - (n.waterSurfaceY() - n.bedY()));
                            ownerBoundaryMaxDepthStep = Math.max(
                                    ownerBoundaryMaxDepthStep, depthStep);
                        }
                    }
                    RiverSample west = sampleWorld(x - 1, z);
                    RiverSample north = sampleWorld(x, z - 1);
                    if (east.water() && west.water() && north.water() && south.water()
                            && junctionCoreAt(child, parent, relation, x + 1, z)
                            && junctionCoreAt(child, parent, relation, x - 1, z)
                            && junctionCoreAt(child, parent, relation, x, z + 1)
                            && junctionCoreAt(child, parent, relation, x, z - 1)) {
                        int neighbourMean = (east.bedY() + west.bedY() + north.bedY() + south.bedY()) / 4;
                        if (c.bedY() >= neighbourMean + 2) ridgeFailures++;
                        if (c.bedY() <= neighbourMean - 3) pitFailures++;
                    }
                }
            }

            int side = Math.max(maxX - minX + 1, maxZ - minZ + 1);
            boolean[] shallowMask = new boolean[side * side];
            int interiorColumns = 0;
            int shallowShelfColumns = 0;
            int bedRaiseAboveOwnerLocal = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    RiverSample sample = sampleWorld(x, z);
                    if (!sample.water() || !junctionCoreAt(child, parent, relation, x, z)) continue;
                    int finalDepth = Math.max(1, sample.waterSurfaceY() - sample.bedY());
                    int ownerLocalDepth = ownerLocalDepthAt(sample, x, z);
                    if (finalDepth < ownerLocalDepth - 1) bedRaiseAboveOwnerLocal++;
                    if (junctionUnionInterior(child, parent, x, z) >= 6.0D) {
                        interiorColumns++;
                        if (finalDepth <= 2) {
                            shallowShelfColumns++;
                            shallowMask[(z - minZ) * side + (x - minX)] = true;
                        }
                    }
                }
            }
            int largestInteriorShallowPatch = largestConnectedPatch(shallowMask, side);

            int centrelineSamples = 0;
            int centrelineDepthCollapse = 0;
            double centreStart = relation.equals("CONFLUENCE")
                    ? Math.max(0.0D, child.mouthDistance - 512.0D) : 0.0D;
            double centreEnd = relation.equals("CONFLUENCE")
                    ? child.mouthDistance : Math.min(512.0D, child.mouthDistance);
            for (double station = centreStart; station <= centreEnd; station += 4.0D) {
                WorldPoint point = child.pointAtDistance(station);
                int x = (int)Math.round(point.x());
                int z = (int)Math.round(point.z());
                RiverSample sample = sampleWorld(x, z);
                if (!sample.water() || !child.allowsFiniteWaterMask(x, z)) continue;
                Nearest nearest = child.nearest(x, z);
                double progress = clamp(nearest.alongDistance() / child.mouthDistance,
                        0.0D, 1.0D);
                double width = child.waterWidth(progress);
                int childLocalDepth = localBathymetry(child, nearest, progress, width,
                        sample.waterSurfaceY(), true).depth();
                int finalDepth = Math.max(1, sample.waterSurfaceY() - sample.bedY());
                centrelineSamples++;
                if (finalDepth < childLocalDepth - 1) centrelineDepthCollapse++;
            }
            RiverSample anchorFinal = sampleWorld((int)Math.round(anchor.x()),
                    (int)Math.round(anchor.z()));
            int anchorBedMismatch = Integer.MAX_VALUE;
            if (anchorFinal.water()) {
                ChannelBathymetry cb = localBathymetry(child, cn,
                        clamp(cn.alongDistance() / child.mouthDistance, 0.0D, 1.0D),
                        cw, anchorFinal.waterSurfaceY(), true);
                ChannelBathymetry pb = localBathymetry(parent, pn,
                        clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D),
                        pw, anchorFinal.waterSurfaceY(), true);
                int expectedAnchorDepth = Math.max(cb.depth(), pb.depth());
                int finalAnchorDepth = anchorFinal.waterSurfaceY() - anchorFinal.bedY();
                anchorBedMismatch = Math.abs(finalAnchorDepth - expectedAnchorDepth);
            }
            result.add(new JunctionBathymetryAudit(child.name, parent.name, relation, sampled,
                    maxFlatStep, worstStepX, worstStepZ, ownerBoundaryMax,
                    ownerBoundaryMaxDepthStep, ridgeFailures,
                    pitFailures, anchorBedMismatch, ownerWorstX, ownerWorstZ,
                    interiorColumns, shallowShelfColumns, largestInteriorShallowPatch,
                    bedRaiseAboveOwnerLocal, centrelineSamples, centrelineDepthCollapse));
        }
        return List.copyOf(result);
    }

    record JunctionBathymetryAudit(String river, String otherRiver, String relation,
                                   int sampledWaterColumns, int maxFlatAdjacentBedStep,
                                   int worstStepX, int worstStepZ, int ownerBoundaryMaxBedStep,
                                   int ownerBoundaryMaxDepthStep, int ridgeFailures,
                                   int pitFailures, int anchorLocalBedMismatch,
                                   int ownerWorstX, int ownerWorstZ, int interiorColumns,
                                   int shallowShelfColumns, int largestInteriorShallowPatch,
                                   int bedRaiseAboveOwnerLocal, int centrelineSamples,
                                   int centrelineDepthCollapse) {}

    static List<JunctionShelfRegressionAudit> junctionShelfRegressionSnapshot() {
        return List.of(
                junctionShelfRegression("Edo/Tone", 119, -5705, "Edo", 0.540D),
                junctionShelfRegression("Karasu/Tone", -6603, -8129, "Karasu", 0.502D),
                junctionShelfRegression("Iruma/Arakawa", -2536, -3164, "Iruma", 0.359D)
        );
    }

    private static JunctionShelfRegressionAudit junctionShelfRegression(
            String label, int centreX, int centreZ, String owner, double p12ShallowFraction) {
        int wetColumns = 0;
        int ownerColumns = 0;
        int ownerShallowColumns = 0;
        for (int x = centreX - 64; x <= centreX + 64; x++) {
            for (int z = centreZ - 64; z <= centreZ + 64; z++) {
                RiverSample sample = sampleWorld(x, z);
                if (!sample.water()) continue;
                wetColumns++;
                if (!sample.name().equals(owner)) continue;
                ownerColumns++;
                if (sample.waterSurfaceY() - sample.bedY() <= 2) ownerShallowColumns++;
            }
        }
        double fraction = ownerColumns == 0 ? 0.0D
                : ownerShallowColumns / (double)ownerColumns;
        return new JunctionShelfRegressionAudit(label, centreX, centreZ, owner,
                wetColumns, ownerColumns, ownerShallowColumns, fraction,
                p12ShallowFraction, p12ShallowFraction * 0.60D);
    }

    record JunctionShelfRegressionAudit(String label, int centreX, int centreZ, String owner,
                                        int wetColumns, int ownerColumns,
                                        int ownerShallowColumns, double ownerShallowFraction,
                                        double p12OwnerShallowFraction,
                                        double maximumAcceptedFraction) {}

    static WidthHierarchyAudit authoredWidthHierarchyAuditSnapshot() {
        int p0 = 0, p1 = 0, p2 = 0;
        List<WidthClassSample> samples = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            switch (course.hydrologyClass) {
                case P0 -> p0++; case P1 -> p1++; case P2 -> p2++;
            }
            samples.add(new WidthClassSample(course.name, course.hydrologyClass,
                    course.waterWidth(0.0D), course.waterWidth(0.5D), course.waterWidth(1.0D),
                    course.sourceWidth, course.mouthWidth));
        }
        int confluenceRatioFailures = 0;
        List<TerminalWidthSample> terminalSamples = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver == null) continue;
            Nearest pn = child.terminalRiver.nearestUnbounded(
                    child.confluenceAnchor.officialPoint().x(), child.confluenceAnchor.officialPoint().z());
            double parentWidth = child.terminalRiver.waterWidth(clamp(
                    pn.alongDistance() / child.terminalRiver.mouthDistance, 0.0D, 1.0D));
            double childWidth = child.waterWidth(1.0D);
            double ratio = childWidth / Math.max(1.0D, parentWidth);
            double targetRatio = child.terminalWidthTargetRatio();
            // The class ratio is a preferred correction target once hierarchy is actually
            // violated. Existing narrower mouths are frozen; the hard failure is child > parent.
            boolean failure = ratio > 1.0D + 1.0E-6D;
            if (failure) confluenceRatioFailures++;
            terminalSamples.add(new TerminalWidthSample(child.name, child.terminalRiver.name,
                    child.hydrologyClass, child.terminalRiver.hydrologyClass, childWidth,
                    parentWidth, ratio, targetRatio, RiverCourse.TERMINAL_WIDTH_BLEND_LENGTH,
                    failure));
        }
        return new WidthHierarchyAudit(COURSES.size(), p0, p1, p2,
                confluenceRatioFailures, List.copyOf(samples), List.copyOf(terminalSamples));
    }

    record WidthClassSample(String river, HydrologyClass hydrologyClass, double sourceWidth,
                            double middleWidth, double mouthWidth, int authoredSourceWidth,
                            int authoredMouthWidth) {}
    record TerminalWidthSample(String child, String parent, HydrologyClass childClass,
                               HydrologyClass parentClass, double childTerminalWidth,
                               double parentLocalWidth, double ratio, double targetRatio,
                               double taperLength, boolean failure) {}
    record WidthHierarchyAudit(int authoredOverrideCount, int p0Count, int p1Count, int p2Count,
                               int confluenceRatioFailures, List<WidthClassSample> samples,
                               List<TerminalWidthSample> terminalSamples) {}

    /** J-09 frozen terminal planform metrics, including post-union child-only ownership. */
    static List<JunctionPlanformAudit> junctionPlanformAuditSnapshot() {
        List<JunctionPlanformAudit> result = new ArrayList<>();
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
            RiverCourse parent = child.terminalRiver;
            ParentWaterGeometryContact centreContact = firstParentWaterMaskGeometryContact(child);
            double firstCentre = centreContact.found() ? centreContact.childStation() : Double.NaN;
            double leftContact = firstChildBankContact(child, +1.0D);
            double rightContact = firstChildBankContact(child, -1.0D);

            int childOnlyArea = 0;
            double maxLateral = 0.0D;
            int childOnlyAreaAfterOwnership = 0;
            double maxLateralAfterOwnership = 0.0D;
            double ownershipStation = centreContact.found()
                    && !Double.isNaN(leftContact) && !Double.isNaN(rightContact)
                    ? Math.min(child.mouthDistance,
                    Math.max(firstCentre, Math.max(leftContact, rightContact)) + 8.0D) : Double.NaN;
            if (centreContact.found()) {
                double start = Math.max(0.0D, firstCentre - 32.0D);
                double end = child.mouthDistance;
                double maxHalf = 0.0D;
                for (double station = start; station <= end; station += 8.0D) {
                    double progress = clamp(station / child.mouthDistance, 0.0D, 1.0D);
                    maxHalf = Math.max(maxHalf, child.waterWidth(progress) * 0.5D
                            + child.waterRasterMargin(progress));
                }
                double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
                double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                for (double station = start; station <= end; station += 8.0D) {
                    WorldPoint point = child.pointAtDistance(station);
                    minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
                    minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
                }
                int x0 = (int)Math.floor(minX - maxHalf - 4.0D);
                int x1 = (int)Math.ceil(maxX + maxHalf + 4.0D);
                int z0 = (int)Math.floor(minZ - maxHalf - 4.0D);
                int z1 = (int)Math.ceil(maxZ + maxHalf + 4.0D);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        Nearest cn = child.nearest(x, z);
                        if (cn == null || cn.alongDistance() + 1.0D < firstCentre) continue;
                        double cp = clamp(cn.alongDistance() / child.mouthDistance, 0.0D, 1.0D);
                        double ch = child.waterWidth(cp) * 0.5D + child.waterRasterMargin(cp);
                        boolean childWater = cn.distance() <= ch
                                && child.allowsFiniteWaterMask(x, z);
                        if (!childWater || parentWaterAt(parent, x, z)) continue;
                        childOnlyArea++;
                        maxLateral = Math.max(maxLateral, cn.distance());
                        if (!Double.isNaN(ownershipStation)
                                && cn.alongDistance() >= ownershipStation) {
                            childOnlyAreaAfterOwnership++;
                            maxLateralAfterOwnership = Math.max(maxLateralAfterOwnership,
                                    cn.distance());
                        }
                    }
                }
            }
            double leftAnchorShore = parentShoreDistanceAlongChildNormal(child,
                    child.mouthDistance, +1.0D);
            double rightAnchorShore = parentShoreDistanceAlongChildNormal(child,
                    child.mouthDistance, -1.0D);
            result.add(new JunctionPlanformAudit(child.name, parent.name, firstCentre,
                    centreContact.found() ? child.mouthDistance - firstCentre : Double.NaN,
                    childOnlyArea, maxLateral, leftContact, rightContact,
                    ownershipStation, childOnlyAreaAfterOwnership, maxLateralAfterOwnership,
                    leftAnchorShore, rightAnchorShore));
        }
        return List.copyOf(result);
    }

    record JunctionPlanformAudit(String child, String parent, double firstCentreContact,
                                 double distanceToAnchor, int childOnlyAreaAfterFirstContact,
                                 double maxChildOnlyLateralExtent, double leftBankContact,
                                 double rightBankContact, double ownershipStation,
                                 int childOnlyAreaAfterOwnership,
                                 double maxChildOnlyLateralAfterOwnership,
                                 double leftAnchorParentShore,
                                 double rightAnchorParentShore) {}

    private static double firstChildBankContact(RiverCourse child, double side) {
        RiverCourse parent = child.terminalRiver;
        if (parent == null) return Double.NaN;
        final double step = 4.0D;
        boolean previous = false;
        double previousStation = 0.0D;
        int samples = Math.max(1, (int)Math.ceil(child.mouthDistance / step));
        for (int sample = 0; sample <= samples; sample++) {
            double station = Math.min(child.mouthDistance, sample * step);
            boolean contact = childBankInsideParentWater(child, parent, station, side);
            if (contact && !previous) {
                double low = Math.max(0.0D, previousStation);
                double high = station;
                for (int iteration = 0; iteration < 22; iteration++) {
                    double mid = (low + high) * 0.5D;
                    if (childBankInsideParentWater(child, parent, mid, side)) high = mid;
                    else low = mid;
                }
                return high;
            }
            previous = contact;
            previousStation = station;
        }
        return Double.NaN;
    }

    private static boolean childBankInsideParentWater(RiverCourse child, RiverCourse parent,
                                                       double station, double side) {
        WorldPoint centre = child.pointAtDistance(station);
        WorldPoint tangent = child.unitTangentAt(station);
        double progress = clamp(station / child.mouthDistance, 0.0D, 1.0D);
        double half = child.waterWidth(progress) * 0.5D + child.waterRasterMargin(progress);
        double x = centre.x() - tangent.z() * side * half;
        double z = centre.z() + tangent.x() * side * half;
        return parentWaterAt(parent, (int)Math.round(x), (int)Math.round(z));
    }

    private static boolean parentWaterAt(RiverCourse parent, int x, int z) {
        Nearest pn = parent.nearest(x, z);
        if (pn == null) return false;
        double pp = clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D);
        double ph = parent.waterWidth(pp) * 0.5D + parent.waterRasterMargin(pp);
        return pn.distance() <= ph && parent.allowsFiniteWaterMask(x, z);
    }

    private static boolean parentWaterAtPoint(RiverCourse parent, double x, double z) {
        Nearest pn = parent.nearestUnbounded(x, z);
        double pp = clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D);
        double ph = parent.waterWidth(pp) * 0.5D + parent.waterRasterMargin(pp);
        return pn.distance() <= ph
                && parent.allowsFiniteWaterMask((int)Math.round(x), (int)Math.round(z));
    }

    private static double parentShoreDistanceAlongChildNormal(RiverCourse child,
                                                               double station, double side) {
        RiverCourse parent = child.terminalRiver;
        if (parent == null) return Double.NaN;
        WorldPoint centre = child.pointAtDistance(station);
        WorldPoint tangent = child.unitTangentAt(station);
        if (!parentWaterAtPoint(parent, centre.x(), centre.z())) return Double.NaN;
        double childProgress = clamp(station / child.mouthDistance, 0.0D, 1.0D);
        double childHalf = child.waterWidth(childProgress) * 0.5D
                + child.waterRasterMargin(childProgress);
        Nearest pn = parent.nearestUnbounded(centre.x(), centre.z());
        double pp = clamp(pn.alongDistance() / parent.mouthDistance, 0.0D, 1.0D);
        double parentHalf = parent.waterWidth(pp) * 0.5D + parent.waterRasterMargin(pp);
        double maximum = Math.max(128.0D, childHalf + parentHalf + 64.0D);
        double low = 0.0D;
        double high = 1.0D;
        while (high < maximum) {
            double x = centre.x() - tangent.z() * side * high;
            double z = centre.z() + tangent.x() * side * high;
            if (!parentWaterAtPoint(parent, x, z)) break;
            low = high;
            high *= 2.0D;
        }
        high = Math.min(high, maximum);
        double hx = centre.x() - tangent.z() * side * high;
        double hz = centre.z() + tangent.x() * side * high;
        if (parentWaterAtPoint(parent, hx, hz)) return high;
        for (int iteration = 0; iteration < 24; iteration++) {
            double mid = (low + high) * 0.5D;
            double x = centre.x() - tangent.z() * side * mid;
            double z = centre.z() + tangent.x() * side * mid;
            if (parentWaterAtPoint(parent, x, z)) low = mid;
            else high = mid;
        }
        return low;
    }

    private static double firstParentEntryDistanceAlongChildNormal(RiverCourse child,
                                                                    double station,
                                                                    double side) {
        RiverCourse parent = child.terminalRiver;
        if (parent == null) return Double.NaN;
        WorldPoint centre = child.pointAtDistance(station);
        WorldPoint tangent = child.unitTangentAt(station);
        if (parentWaterAtPoint(parent, centre.x(), centre.z())) return 0.0D;
        double progress = clamp(station / child.mouthDistance, 0.0D, 1.0D);
        double childHalf = child.waterWidth(progress) * 0.5D + child.waterRasterMargin(progress);
        double maximum = Math.max(128.0D, childHalf + 96.0D);
        double previous = 0.0D;
        for (double distance = 1.0D; distance <= maximum; distance += 1.0D) {
            double x = centre.x() - tangent.z() * side * distance;
            double z = centre.z() + tangent.x() * side * distance;
            if (!parentWaterAtPoint(parent, x, z)) {
                previous = distance;
                continue;
            }
            double low = Math.max(0.0D, previous - 1.0D);
            double high = distance;
            for (int iteration = 0; iteration < 20; iteration++) {
                double mid = (low + high) * 0.5D;
                double mx = centre.x() - tangent.z() * side * mid;
                double mz = centre.z() + tangent.x() * side * mid;
                if (parentWaterAtPoint(parent, mx, mz)) high = mid;
                else low = mid;
            }
            return high;
        }
        return Double.NaN;
    }

    /**
     * P1.1 global 2-D authored-network diagnostics.  Unlike authoredConfluenceAuditSnapshot(),
     * this treats every pair of authored rivers as graph edges first and geometry second: an
     * overlap is legal only inside the local window of an explicit confluence/bifurcation.
     */
    static AuthoredTopologyAudit authoredRiverTopologyAuditSnapshot() {
        List<TopologyPairAudit> pairAudits = new ArrayList<>();
        int unexpectedOverlaps = 0;
        int differentSystemOverlaps = 0;
        for (int i = 0; i < COURSES.size(); i++) {
            RiverCourse a = COURSES.get(i);
            for (int j = i + 1; j < COURSES.size(); j++) {
                RiverCourse b = COURSES.get(j);
                TopologyPairAudit pair = topologyPairAudit(a, b);
                if (pair.overlapIntervals() == 0) continue;
                pairAudits.add(pair);
                if (pair.unexpected()) unexpectedOverlaps++;
                if (pair.unexpected() && !pair.waterSystemA().isEmpty()
                        && !pair.waterSystemB().isEmpty()
                        && !pair.waterSystemA().equals(pair.waterSystemB())) {
                    differentSystemOverlaps++;
                }
            }
        }

        List<TerminalMaskAudit> terminalMasks = new ArrayList<>();
        int terminalCapProtrusions = 0;
        int confluenceWidthFailures = 0;
        int excessiveFollowers = 0;
        for (RiverCourse child : COURSES) {
            if (child.terminalRiver == null || child.confluenceAnchor == null) continue;
            TerminalMaskAudit mask = terminalMaskAudit(child);
            terminalMasks.add(mask);
            terminalCapProtrusions += mask.childOnlyDownstreamSamples();
            if (mask.widthRatio() > 2.0D + 1.0E-6D) confluenceWidthFailures++;
            if (mask.parallelFollowerLength() > RiverCourse.MAX_PARENT_BANK_FOLLOWER + 1.0D) {
                excessiveFollowers++;
            }
        }

        List<RouteDeviationAudit> deviations = new ArrayList<>(COURSES.size());
        List<String> inlandOceanRivers = new ArrayList<>();
        List<String> severeMouthRivers = new ArrayList<>();
        int severeMouthDeviation = 0;
        int inlandOceanCrossings = 0;
        for (RiverCourse course : COURSES) {
            RouteDeviationAudit deviation = routeDeviationAudit(course);
            deviations.add(deviation);
            InlandOceanAudit inlandOceanAudit = course.reachesSea
                    ? firstInlandOceanCrossing(course) : null;
            boolean inlandOcean = inlandOceanAudit != null;
            if (inlandOcean) {
                inlandOceanCrossings++;
                inlandOceanRivers.add(course.name);
            }
            // Source/mouth errors above 3-5 km remain REVIEW diagnostics for the legacy authored
            // layer.  They become a hard topology failure only when the bad sea boundary also
            // produces an inland-ocean/re-land route (the Matsuura failure class).
            if (course.reachesSea && deviation.mouthErrorMetres() > 5000.0D && inlandOcean) {
                severeMouthDeviation++;
                severeMouthRivers.add(course.name);
            }
        }

        List<RegressionSiteAudit> regressions = List.of(
                regressionSiteAudit("Iruma/Arakawa", -4741, -3310, "Iruma", "Arakawa"),
                regressionSiteAudit("Edo/Tone", 1163, -5908, "Edo", "Tone"),
                regressionSiteAudit("Seki/Shinano", -15350, -17125, "Seki", "Shinano"),
                regressionSiteAudit("Agatsuma/Tone", -8510, -11348, "Agatsuma", "Tone"),
                regressionSiteAudit("Kusu/Chikugo", -99720, 33137, "Kusu", "Chikugo"),
                regressionSiteAudit("Matsuura coast", -112936, 31921, "Matsuura", "Matsuura"),
                regressionSiteAudit("Karasu/Tone", -6641, -8126, "Karasu", "Tone"),
                regressionSiteAudit("Aka/Mogami", 1666, -41696, "Aka", "Mogami"));

        return new AuthoredTopologyAudit(List.copyOf(pairAudits), List.copyOf(terminalMasks),
                List.copyOf(deviations), regressions, unexpectedOverlaps,
                differentSystemOverlaps, terminalCapProtrusions, confluenceWidthFailures,
                excessiveFollowers, inlandOceanCrossings, severeMouthDeviation,
                List.copyOf(inlandOceanRivers), List.copyOf(severeMouthRivers));
    }

    static TopologyPairAudit topologyPairAuditByName(String riverA, String riverB) {
        return topologyPairAudit(named(COURSES, riverA), named(COURSES, riverB));
    }

    private static TopologyPairAudit topologyPairAudit(RiverCourse a, RiverCourse b) {
        double spacing = 16.0D;
        int intervals = 0;
        boolean previous = false;
        double maxRun = 0.0D;
        double run = 0.0D;
        double minDistance = Double.POSITIVE_INFINITY;
        boolean illegalSample = false;
        int firstIllegalX = Integer.MIN_VALUE;
        int firstIllegalZ = Integer.MIN_VALUE;
        boolean firstIllegalLand = false;
        boolean relation = explicitlyLinked(a, b);
        String relationType = relationType(a, b);

        // Sample the shorter route as centreline A, then repeat in the opposite direction.  This
        // catches a short wide branch touching a long parent without raster-scanning Japan.
        for (int direction = 0; direction < 2; direction++) {
            RiverCourse source = direction == 0 ? a : b;
            RiverCourse other = direction == 0 ? b : a;
            previous = false;
            run = 0.0D;
            int samples = Math.max(1, (int)Math.ceil(source.mouthDistance / spacing));
            for (int sample = 0; sample <= samples; sample++) {
                double station = Math.min(source.mouthDistance, sample * spacing);
                WorldPoint point = source.pointAtDistance(station);
                Nearest nearest = other.nearestUnbounded(point.x(), point.z());
                minDistance = Math.min(minDistance, nearest.distance());
                double sourceProgress = clamp(station / source.mouthDistance, 0.0D, 1.0D);
                double otherProgress = clamp(nearest.alongDistance() / other.mouthDistance,
                        0.0D, 1.0D);
                double radius = source.waterWidth(sourceProgress) * 0.5D
                        + source.waterRasterMargin(sourceProgress)
                        + other.waterWidth(otherProgress) * 0.5D
                        + other.waterRasterMargin(otherProgress);
                boolean overlap = nearest.distance() <= radius;
                if (overlap && !previous) intervals++;
                if (overlap) {
                    run += spacing;
                    maxRun = Math.max(maxRun, run);
                    if (!legalPairOverlap(source, other, station, nearest.alongDistance())) {
                        illegalSample = true;
                        if (firstIllegalX == Integer.MIN_VALUE) {
                            firstIllegalX = (int)Math.round(point.x());
                            firstIllegalZ = (int)Math.round(point.z());
                            firstIllegalLand = TerrainData.isLandWorld(firstIllegalX, firstIllegalZ);
                        }
                    }
                } else {
                    run = 0.0D;
                }
                previous = overlap;
            }
        }
        // Both directional samplings describe the same physical intervals.  The count above is a
        // conservative diagnostic, not a graph-edge cardinality; zero/non-zero is authoritative.
        String systemA = W05AuthoredReference.waterSystemCode(a.name);
        String systemB = W05AuthoredReference.waterSystemCode(b.name);
        return new TopologyPairAudit(a.name, b.name, relationType, systemA, systemB,
                minDistance, intervals, maxRun, illegalSample && !pairSharedChannelWhitelist(a,b),
                relation, firstIllegalX, firstIllegalZ, firstIllegalLand);
    }

    private static boolean explicitlyLinked(RiverCourse a, RiverCourse b) {
        return a.terminalRiver == b || b.terminalRiver == a
                || a.sourceRiver == b || b.sourceRiver == a
                || commonParentConfluence(a, b)
                || sharedWaterwayRule(a, b) != null;
    }

    private static String relationType(RiverCourse a, RiverCourse b) {
        if (a.terminalRiver == b || b.terminalRiver == a) return "CONFLUENCE";
        if (a.sourceRiver == b || b.sourceRiver == a) return "BIFURCATION";
        if (commonParentConfluence(a, b)) return "COMMON_PARENT_CONFLUENCE";
        return sharedWaterwayRule(a, b) != null ? "SHARED_WATERWAY" : "INDEPENDENT";
    }

    private static boolean commonParentConfluence(RiverCourse a, RiverCourse b) {
        if (a.terminalRiver == null || a.terminalRiver != b.terminalRiver
                || a.confluenceAnchor == null || b.confluenceAnchor == null) return false;
        return RiverCourse.distance(a.confluenceAnchor.officialPoint(),
                b.confluenceAnchor.officialPoint()) <= 1024.0D;
    }

    private static boolean legalPairOverlap(RiverCourse source, RiverCourse other,
                                            double sourceStation, double otherStation) {
        if (source.terminalRiver == other) {
            return source.mouthDistance - sourceStation <= RiverCourse.CONFLUENCE_LENGTH + 128.0D;
        }
        if (other.terminalRiver == source) {
            WorldPoint anchor = other.confluenceAnchor.officialPoint();
            Nearest onSource = source.nearestUnbounded(anchor.x(), anchor.z());
            return Math.abs(sourceStation - onSource.alongDistance()) <= 768.0D;
        }
        if (source.sourceRiver == other) return sourceStation <= 768.0D;
        if (other.sourceRiver == source) {
            WorldPoint anchor = other.bifurcationAnchor.officialPoint();
            Nearest onSource = source.nearestUnbounded(anchor.x(), anchor.z());
            return Math.abs(sourceStation - onSource.alongDistance()) <= 768.0D;
        }
        if (commonParentConfluence(source, other)) {
            return source.mouthDistance - sourceStation <= RiverCourse.CONFLUENCE_LENGTH + 128.0D
                    && other.mouthDistance - otherStation <= RiverCourse.CONFLUENCE_LENGTH + 128.0D;
        }
        SharedWaterwayRule shared = sharedWaterwayRule(source, other);
        if (shared != null) {
            return source.mouthDistance - sourceStation <= shared.tailLengthBlocks()
                    && other.mouthDistance - otherStation <= shared.tailLengthBlocks();
        }
        return false;
    }

    private static SharedWaterwayRule sharedWaterwayRule(RiverCourse a, RiverCourse b) {
        // Explicit geometry-only exceptions.  These do NOT enable hydraulicAdjustment().
        // Kiso/Nagara/Ibi are represented by W05 as the same lower water-system complex and
        // run inside the same engineered floodplain for many kilometres.  Their shared-window
        // allowance is bounded to the lower 32 km; any upstream overlap still fails.
        String key = a.name.compareTo(b.name) <= 0 ? a.name + "/" + b.name : b.name + "/" + a.name;
        return switch (key) {
            case "Hii/Ohashi" -> new SharedWaterwayRule(2048.0D);
            case "Kiso/Nagara", "Ibi/Nagara" -> new SharedWaterwayRule(4096.0D);
            default -> null;
        };
    }

    private record SharedWaterwayRule(double tailLengthBlocks) {}

    private static boolean pairSharedChannelWhitelist(RiverCourse a, RiverCourse b) {
        return sharedWaterwayRule(a, b) != null || commonParentConfluence(a, b);
    }

    private static TerminalMaskAudit terminalMaskAudit(RiverCourse child) {
        RiverCourse parent = child.terminalRiver;
        WorldPoint anchor = child.confluenceAnchor.officialPoint();
        Nearest parentNearest = parent.nearestUnbounded(anchor.x(), anchor.z());
        double parentProgress = clamp(parentNearest.alongDistance() / parent.mouthDistance,
                0.0D, 1.0D);
        double childWidth = child.waterWidth(1.0D);
        double parentWidth = parent.waterWidth(parentProgress);
        double ratio = childWidth / Math.max(1.0D, parentWidth);

        WorldPoint tangent = child.unitTangentAt(Math.max(0.0D, child.mouthDistance - 16.0D));
        int radius = (int)Math.ceil(childWidth * 0.5D + 16.0D);
        int protrusions = 0;
        for (int x = (int)Math.floor(anchor.x()) - radius;
             x <= (int)Math.ceil(anchor.x()) + radius; x += 2) {
            for (int z = (int)Math.floor(anchor.z()) - radius;
                 z <= (int)Math.ceil(anchor.z()) + radius; z += 2) {
                double dot = (x - anchor.x()) * tangent.x() + (z - anchor.z()) * tangent.z();
                if (dot <= RiverCourse.END_CAP_ALLOWANCE) continue;
                Nearest nearest = child.nearestUnbounded(x, z);
                if (nearest.distance() <= childWidth * 0.5D
                        && child.allowsFiniteWaterMask(x, z)) protrusions++;
            }
        }

        double follower = 0.0D;
        double longestFollower = 0.0D;
        for (double station = 0.0D; station <= child.mouthDistance; station += 16.0D) {
            WorldPoint point = child.pointAtDistance(station);
            Nearest nearest = parent.nearestUnbounded(point.x(), point.z());
            WorldPoint ct = child.unitTangentAt(station);
            WorldPoint pt = parent.unitTangentAt(nearest.alongDistance());
            double parallel = Math.abs(ct.x() * pt.x() + ct.z() * pt.z());
            boolean follows = nearest.distance() <= 250.0D && parallel >= 0.8660254D
                    && child.mouthDistance - station > 128.0D;
            if (follows) {
                follower += 16.0D;
                longestFollower = Math.max(longestFollower, follower);
            } else follower = 0.0D;
        }
        return new TerminalMaskAudit(child.name, parent.name, childWidth, parentWidth, ratio,
                protrusions, longestFollower);
    }

    private static RouteDeviationAudit routeDeviationAudit(RiverCourse course) {
        W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
        if (reference == null || reference.samples().isEmpty()) {
            return new RouteDeviationAudit(course.name, "", "", Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN);
        }
        List<Double> distances = new ArrayList<>();
        int samples = Math.max(2, Math.min(256,
                (int)Math.ceil(course.mouthDistance / 64.0D)));
        for (int i = 0; i <= samples; i++) {
            double station = course.mouthDistance * i / (double)samples;
            WorldPoint point = course.pointAtDistance(station);
            distances.add(nearestReferenceDistanceMetres(point, reference.samples()));
        }
        distances.sort(Double::compareTo);
        double median = percentile(distances, 0.50D);
        double p90 = percentile(distances, 0.90D);
        WorldPoint source = course.pointAtDistance(0.0D);
        WorldPoint mouth = course.pointAtDistance(course.mouthDistance);
        double sourceError = nearestReferenceDistanceMetres(source, reference.headNodes());
        double mouthError = nearestReferenceDistanceMetres(mouth, reference.terminalNodes());
        return new RouteDeviationAudit(course.name, reference.officialIdentifier(),
                reference.waterSystemCode(), median, p90, sourceError, mouthError);
    }

    private static double maximumRouteDeviationMetres(RiverCourse course) {
        W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
        if (reference == null || reference.samples().isEmpty()) return Double.NaN;
        double maximum = 0.0D;
        int samples = Math.max(2, Math.min(512, (int)Math.ceil(course.mouthDistance / 32.0D)));
        for (int i = 0; i <= samples; i++) {
            WorldPoint point = course.pointAtDistance(course.mouthDistance * i / (double)samples);
            maximum = Math.max(maximum, nearestReferenceDistanceMetres(point, reference.samples()));
        }
        return maximum;
    }

    private static double nearestReferenceDistanceMetres(WorldPoint world,
                                                          List<W05AuthoredReference.LatLon> refs) {
        if (refs.isEmpty()) return Double.NaN;
        double bestBlocks = Double.POSITIVE_INFINITY;
        for (W05AuthoredReference.LatLon ref : refs) {
            double x = TerrainData.worldXFromLongitude(ref.longitude());
            double z = TerrainData.worldZFromLatitude(ref.latitude());
            bestBlocks = Math.min(bestBlocks, Math.hypot(world.x() - x, world.z() - z));
        }
        return bestBlocks * TerrainData.HORIZONTAL_METRES_PER_BLOCK;
    }

    private static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return Double.NaN;
        int index = (int)Math.round(clamp(fraction, 0.0D, 1.0D) * (values.size() - 1));
        return values.get(index);
    }

    private static InlandOceanAudit firstInlandOceanCrossing(RiverCourse course) {
        // A final coast approach is allowed.  What is forbidden is an open-ocean run followed by
        // a return to land before the authored mouth (the Matsuura strait regression).
        boolean oceanRun = false;
        double oceanStart = Double.NaN;
        double maximumOfficialDeviation = 0.0D;
        WorldPoint oceanPoint = null;
        W05AuthoredReference.Route official = W05AuthoredReference.route(course.name);
        for (double station = 0.0D; station <= course.mouthDistance; station += 16.0D) {
            WorldPoint point = course.pointAtDistance(station);
            boolean land = TerrainData.isLandWorld((int)Math.round(point.x()),
                    (int)Math.round(point.z()));
            if (!land) {
                if (!oceanRun) {
                    oceanStart = station;
                    oceanPoint = point;
                    maximumOfficialDeviation = 0.0D;
                }
                oceanRun = true;
                if (official != null && !official.samples().isEmpty()) {
                    maximumOfficialDeviation = Math.max(maximumOfficialDeviation,
                            nearestReferenceDistanceMetres(point, official.samples()));
                } else {
                    maximumOfficialDeviation = Double.POSITIVE_INFINITY;
                }
            } else if (oceanRun) {
                double run = station - oceanStart;
                double remaining = course.mouthDistance - station;
                // The compact coastline raster sometimes classifies a broad estuary or tidal
                // channel as sea.  If the entire apparent ocean run is still within 1.5 km of the
                // W05 centre-line, W05 is authoritative and this is not a strait crossing.
                boolean officialEstuary = maximumOfficialDeviation <= 1500.0D;
                if (run >= 64.0D && remaining >= 256.0D && !officialEstuary) {
                    return new InlandOceanAudit(course.name, oceanStart, station, run, remaining,
                            (int)Math.round(oceanPoint.x()), (int)Math.round(oceanPoint.z()),
                            (int)Math.round(point.x()), (int)Math.round(point.z()));
                }
                oceanRun = false;
                oceanStart = Double.NaN;
                maximumOfficialDeviation = 0.0D;
                oceanPoint = null;
            }
        }
        return null;
    }

    private static RegressionSiteAudit regressionSiteAudit(String label, int x, int z,
                                                            String riverA, String riverB) {
        RiverCourse a = named(COURSES, riverA);
        RiverCourse b = named(COURSES, riverB);
        Nearest na = a.nearestUnbounded(x, z);
        Nearest nb = b.nearestUnbounded(x, z);
        double pa = clamp(na.alongDistance() / a.mouthDistance, 0.0D, 1.0D);
        double pb = clamp(nb.alongDistance() / b.mouthDistance, 0.0D, 1.0D);
        double wa = a.waterWidth(pa);
        double wb = b.waterWidth(pb);
        boolean maskA = na.distance() <= wa * 0.5D + a.waterRasterMargin(pa)
                && a.allowsFiniteWaterMask(x, z);
        boolean maskB = nb.distance() <= wb * 0.5D + b.waterRasterMargin(pb)
                && b.allowsFiniteWaterMask(x, z);
        RouteDeviationAudit da = routeDeviationAudit(a);
        RouteDeviationAudit db = riverA.equals(riverB) ? da : routeDeviationAudit(b);
        return new RegressionSiteAudit(label, x, z, riverA, riverB, relationType(a,b),
                W05AuthoredReference.waterSystemCode(riverA),
                W05AuthoredReference.waterSystemCode(riverB), na.distance(), nb.distance(),
                wa, wb, maskA, maskB, TerrainData.isLandWorld(x,z),
                da.p90DeviationMetres(), db.p90DeviationMetres());
    }


    record AuthoredRegionalRefreshAudit(String river, String batch, String classification,
                                        boolean refreshAttempted, String fallbackReason,
                                        double baselineMedianDeviationMetres,
                                        double postMedianDeviationMetres, double postMaximumDeviationMetres,
                                        double sourceDeviationMetres, double mouthDeviationMetres,
                                        double confluenceDeviationMetres, double valleyP90OffsetBlocks,
                                        int illegalLakeIntersections, int thirdPartyLakeCrossings,
                                        String coastIntersection, double maximumSegmentBlocks,
                                        String hydrologyClass, int sourceWidth, int mouthWidth) {}

    static List<AuthoredRegionalRefreshAudit> authoredRegionalRefreshAuditSnapshot() {
        Map<String, LakeData.LakeMetadata> lakesByName = new HashMap<>();
        for (LakeData.LakeMetadata lake : LakeData.metadata()) lakesByName.put(lake.name(), lake);
        List<AuthoredRegionalRefreshAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
            RouteDeviationAudit base = routeDeviationAudit(course);
            double maxDeviation = maximumReferenceDeviationMetres(course, reference);
            double confluenceDeviation = confluenceDeviationMetres(course, reference);
            ValleyAlignment valley = valleyAlignmentAudit(course);
            LakeIntersectionAudit lakes = authoredLakeIntersectionAudit(course, reference, lakesByName);
            int illegalCoast = course.reachesSea ? (firstInlandOceanCrossing(course) == null ? 0 : 1)
                    : nonSeaOceanCrossingCount(course);
            result.add(new AuthoredRegionalRefreshAudit(course.name, course.arRegionalBatch,
                    course.arRegionalMode, course.arRegionalRefreshAttempted, course.arRegionalFallback,
                    course.arBaselineMedianDeviationMetres, base.medianDeviationMetres(), maxDeviation,
                    base.sourceErrorMetres(), base.mouthErrorMetres(), confluenceDeviation,
                    valley.p90OffsetBlocks(), lakes.illegalCount(), lakes.thirdPartyCrossingCount(),
                    illegalCoast == 0 ? (course.reachesSea ? "SEA_MOUTH_OK" : "INLAND_ROUTE_OK")
                            : "ILLEGAL_COAST_INTERSECTION", course.maximumSegmentLengthToMouth(),
                    course.hydrologyClass.name(), course.sourceWidth, course.mouthWidth));
        }
        return List.copyOf(result);
    }

    /** AR-01 nationwide alignment audit against the frozen W05 logical-course references. */
    static List<AuthoredAlignmentAudit> authoredRiverAlignmentAuditSnapshot() {
        Map<String, LakeData.LakeMetadata> lakesByName = new HashMap<>();
        for (LakeData.LakeMetadata lake : LakeData.metadata()) lakesByName.put(lake.name(), lake);
        List<AuthoredAlignmentAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
            RouteDeviationAudit base = routeDeviationAudit(course);
            double maxDeviation = maximumReferenceDeviationMetres(course, reference);
            double confluenceDeviation = confluenceDeviationMetres(course, reference);
            ValleyAlignment valley = valleyAlignmentAudit(course);
            LakeIntersectionAudit lakeAudit = authoredLakeIntersectionAudit(course, reference, lakesByName);
            InlandOceanAudit inlandOcean = course.reachesSea ? firstInlandOceanCrossing(course) : null;
            int illegalCoast = inlandOcean != null ? 1 : nonSeaOceanCrossingCount(course);
            String coastStatus = illegalCoast > 0 ? "ILLEGAL_COAST_INTERSECTION"
                    : (course.reachesSea ? "SEA_MOUTH_OK" : "INLAND_ROUTE_OK");
            ClassificationDecision classification = classifyAuthoredAlignment(course, base,
                    maxDeviation, confluenceDeviation, valley, lakeAudit, illegalCoast);
            result.add(new AuthoredAlignmentAudit(course.name,
                    reference == null ? "" : reference.officialIdentifier(),
                    reference == null ? "" : reference.waterSystemCode(),
                    base.medianDeviationMetres(), maxDeviation, base.sourceErrorMetres(),
                    base.mouthErrorMetres(), confluenceDeviation, valley.p90OffsetBlocks(),
                    valley.status(), lakeAudit.legalCount(), lakeAudit.illegalCount(),
                    lakeAudit.summary(), coastStatus, lakeAudit.thirdPartyCrossingCount(),
                    classification.mode(), classification.reason()));
        }
        return List.copyOf(result);
    }

    /** AR-03 preview only; no RiverCourse geometry is mutated before AR-04..09. */
    static List<AuthoredRefreshPreview> authoredGeometryRefreshPreviewSnapshot() {
        Map<String, AuthoredAlignmentAudit> alignment = new HashMap<>();
        for (AuthoredAlignmentAudit audit : authoredRiverAlignmentAuditSnapshot()) {
            alignment.put(audit.river(), audit);
        }
        List<AuthoredRefreshPreview> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            AuthoredAlignmentAudit audit = alignment.get(course.name);
            AuthoredRiverGeometryRefresher.Mode mode = AuthoredRiverARPolicy.decision(course.name).mode();
            List<AuthoredRiverGeometryRefresher.Point> current = sampledRefreshRoute(course);
            List<AuthoredRiverGeometryRefresher.Point> protectedPoints = protectedRefreshPoints(course);
            AuthoredRiverGeometryRefresher.Preview preview = AuthoredRiverGeometryRefresher.preview(
                    course.name, mode, current, protectedPoints);
            result.add(new AuthoredRefreshPreview(course.name, audit.classification(),
                    preview.currentPoints(), preview.officialPoints(), preview.candidatePoints(),
                    preview.maximumCandidateShiftBlocks(), preview.medianCandidateShiftBlocks(),
                    preview.sourceShiftBlocks(), preview.mouthShiftBlocks(),
                    preview.endpointsProtected(), preview.candidateFinite()));
        }
        return List.copyOf(result);
    }

    private static List<AuthoredRiverGeometryRefresher.Point> sampledRefreshRoute(RiverCourse course) {
        int samples = Math.max(2, Math.min(640, (int)Math.ceil(course.mouthDistance / 96.0D)));
        List<AuthoredRiverGeometryRefresher.Point> points = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            WorldPoint p = course.pointAtDistance(course.mouthDistance * i / (double)samples);
            points.add(new AuthoredRiverGeometryRefresher.Point(p.x(), p.z()));
        }
        return List.copyOf(points);
    }

    private static List<AuthoredRiverGeometryRefresher.Point> protectedRefreshPoints(RiverCourse course) {
        List<AuthoredRiverGeometryRefresher.Point> points = new ArrayList<>();
        WorldPoint source = course.pointAtDistance(0.0D);
        WorldPoint mouth = course.pointAtDistance(course.mouthDistance);
        points.add(new AuthoredRiverGeometryRefresher.Point(source.x(), source.z()));
        points.add(new AuthoredRiverGeometryRefresher.Point(mouth.x(), mouth.z()));
        if (course.confluenceAnchor != null) {
            WorldPoint p = course.confluenceAnchor.officialPoint();
            points.add(new AuthoredRiverGeometryRefresher.Point(p.x(), p.z()));
        }
        if (course.bifurcationAnchor != null) {
            WorldPoint p = course.bifurcationAnchor.officialPoint();
            points.add(new AuthoredRiverGeometryRefresher.Point(p.x(), p.z()));
        }
        for (WorldPoint p : course.fixedConfluenceAnchors) {
            points.add(new AuthoredRiverGeometryRefresher.Point(p.x(), p.z()));
        }
        return List.copyOf(points);
    }

    private static double maximumReferenceDeviationMetres(RiverCourse course,
                                                            W05AuthoredReference.Route reference) {
        if (reference == null || reference.samples().isEmpty()) return Double.NaN;
        int samples = Math.max(2, Math.min(320, (int)Math.ceil(course.mouthDistance / 64.0D)));
        double maximum = 0.0D;
        for (int i = 0; i <= samples; i++) {
            WorldPoint p = course.pointAtDistance(course.mouthDistance * i / (double)samples);
            maximum = Math.max(maximum, nearestReferenceDistanceMetres(p, reference.samples()));
        }
        return maximum;
    }

    private static double confluenceDeviationMetres(RiverCourse course,
                                                      W05AuthoredReference.Route reference) {
        if (course.terminalRiver == null || reference == null || reference.terminalNodes().isEmpty()) {
            return Double.NaN;
        }
        WorldPoint current = course.pointAtDistance(course.mouthDistance);
        return nearestReferenceDistanceMetres(current, reference.terminalNodes());
    }

    private record ValleyAlignment(double p90OffsetBlocks, String status) {}

    private static ValleyAlignment valleyAlignmentAudit(RiverCourse course) {
        List<Double> offsets = new ArrayList<>();
        int samples = Math.max(8, Math.min(40, (int)Math.ceil(course.mouthDistance / 512.0D)));
        for (int i = 1; i < samples; i++) {
            double station = course.mouthDistance * i / (double)samples;
            WorldPoint centre = course.pointAtDistance(station);
            TerrainData.TerrainSample terrain = TerrainData.sampleWorld(
                    (int)Math.round(centre.x()), (int)Math.round(centre.z()));
            if (!terrain.land() || LakeData.sampleWorld((int)Math.round(centre.x()),
                    (int)Math.round(centre.z())).water()) continue;
            double progress = clamp(station / course.mouthDistance, 0.0D, 1.0D);
            double width = course.waterWidth(progress);
            int centreY = RiverCourse.naturalSurfaceAt(centre);
            int valleyY = course.valleySurfaceAt(station, width);
            offsets.add((double)Math.max(0, centreY - valleyY));
        }
        offsets.sort(Double::compareTo);
        double p90 = offsets.isEmpty() ? 0.0D : percentile(offsets, 0.90D);
        String status = p90 <= 4.0D ? "GOOD" : p90 <= 12.0D ? "REVIEW" : "POOR";
        return new ValleyAlignment(p90, status);
    }

    private record LakeIntersectionAudit(int legalCount, int illegalCount,
                                         int thirdPartyCrossingCount, String summary) {}

    record AuthoredLakeIntersectionSite(String river, String lakeId, String lakeName,
                                        String reason, double station, int x, int z) {}

    static List<AuthoredLakeIntersectionSite> authoredLakeIntersectionSiteSnapshot() {
        List<AuthoredLakeIntersectionSite> result = new ArrayList<>();
        Map<String, LakeData.LakeMetadata> lakesByName = new HashMap<>();
        for (LakeData.LakeMetadata lake : LakeData.metadata()) lakesByName.put(lake.name(), lake);
        for (RiverCourse course : COURSES) {
            W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
            String officialId = reference == null ? "" : reference.officialIdentifier();
            int samples = Math.max(4, Math.min(1200, (int)Math.ceil(course.mouthDistance / 24.0D)));
            String previousOwner = "";
            for (int i = 0; i <= samples; i++) {
                double station = course.mouthDistance * i / (double)samples;
                WorldPoint p = course.pointAtDistance(station);
                int x = (int)Math.round(p.x()), z = (int)Math.round(p.z());
                LakeData.LakeSample sample = LakeData.sampleWorld(x, z);
                if (!sample.water() || sample.name().isEmpty()) { previousOwner = ""; continue; }
                String ownerId = activeLakeIdAt(sample.name(), x, z);
                String ownerKey = ownerId.isEmpty() ? sample.name() : ownerId;
                if (ownerKey.equals(previousOwner)) continue;
                previousOwner = ownerKey;
                LakeData.LakeMetadata meta = ownerId.isEmpty() ? lakesByName.get(sample.name())
                        : LakeData.metadataById(ownerId);
                String reason = legalLakeIntersectionReason(course, sample.name(), officialId, meta);
                if (reason.startsWith("ILLEGAL")) {
                    result.add(new AuthoredLakeIntersectionSite(course.name, ownerId, sample.name(),
                            reason, station, x, z));
                }
            }
        }
        return List.copyOf(result);
    }

    private static String activeLakeIdAt(String name, int x, int z) {
        for (LakeData.LakeMetadata lake : LakeData.metadata()) {
            if (!lake.name().equals(name)) continue;
            boolean inside = lake.authoredOverride()
                    ? (AuthoredLakeFootprintData.has(name) && AuthoredLakeFootprintData.distance(name, x, z).inside())
                    : RetainedLakeData.sampleLake(lake.lakeId(), x, z).water();
            if (inside) return lake.lakeId();
        }
        return "";
    }

    private static LakeIntersectionAudit authoredLakeIntersectionAudit(
            RiverCourse course, W05AuthoredReference.Route reference,
            Map<String, LakeData.LakeMetadata> lakesByName) {
        Set<String> legal = new HashSet<>();
        Set<String> illegal = new HashSet<>();
        List<String> tokens = new ArrayList<>();
        String officialId = reference == null ? "" : reference.officialIdentifier();
        int samples = Math.max(4, Math.min(720, (int)Math.ceil(course.mouthDistance / 24.0D)));
        String previousLake = "";
        for (int i = 0; i <= samples; i++) {
            double station = course.mouthDistance * i / (double)samples;
            WorldPoint point = course.pointAtDistance(station);
            int sampleX = (int)Math.round(point.x()), sampleZ = (int)Math.round(point.z());
            LakeData.LakeSample sample = LakeData.sampleWorld(sampleX, sampleZ);
            if (!sample.water() || sample.name().isEmpty()) { previousLake = ""; continue; }
            String ownerId = activeLakeIdAt(sample.name(), sampleX, sampleZ);
            String ownerKey = ownerId.isEmpty() ? sample.name() : ownerId;
            if (ownerKey.equals(previousLake)) continue;
            previousLake = ownerKey;
            LakeData.LakeMetadata metadata = ownerId.isEmpty() ? lakesByName.get(sample.name())
                    : LakeData.metadataById(ownerId);
            String reason = legalLakeIntersectionReason(course, sample.name(), officialId, metadata);
            String token = ownerKey + "/" + sample.name();
            if (reason.startsWith("ILLEGAL")) {
                illegal.add(ownerKey);
                tokens.add(token + ":" + reason);
            } else {
                legal.add(ownerKey);
                tokens.add(token + ":" + reason);
            }
        }
        return new LakeIntersectionAudit(legal.size(), illegal.size(), illegal.size(),
                String.join("|", tokens));
    }

    private static String legalLakeIntersectionReason(RiverCourse course, String lakeName,
                                                       String officialId,
                                                       LakeData.LakeMetadata lake) {
        if (lakeName.equals(course.sourceLakeName)) return "SOURCE_FROM_LAKE";
        if (lakeName.equals(course.terminalLakeName)) return "TERMINAL_INTO_LAKE";
        if (lake != null && containsId(lake.inletRiverIds(), officialId)) return "DECLARED_INLET";
        if (lake != null && containsId(lake.outletRiverIds(), officialId)) return "DECLARED_OUTLET";
        if (lake != null && containsId(lake.sourceRiverIds(), officialId)) return "DECLARED_OUTLET";
        if (lake != null && containsId(lake.terminalRiverIds(), officialId)) return "DECLARED_INLET";
        // Some major authored stems legally traverse a reservoir/lake without the compact lake
        // metadata carrying a dedicated inlet/outlet role. Treat this as DECLARED_CHANNEL only
        // when the frozen W05 official point cloud itself physically enters the same active lake;
        // same water-system membership alone is intentionally insufficient.
        if (officialRouteTouchesLake(course.name, lakeName)) return "DECLARED_CHANNEL";
        return "ILLEGAL_LAKE_INTERSECTION";
    }

    private static boolean officialRouteTouchesLake(String river, String lakeName) {
        W05AuthoredReference.Route route = W05AuthoredReference.route(river);
        if (route == null) return false;
        for (W05AuthoredReference.LatLon p : route.samples()) {
            int x = (int)Math.round(TerrainData.worldXFromLongitude(p.longitude()));
            int z = (int)Math.round(TerrainData.worldZFromLatitude(p.latitude()));
            LakeData.LakeSample sample = LakeData.sampleWorld(x, z);
            if (sample.water() && lakeName.equals(sample.name())) return true;
        }
        return false;
    }

    private static boolean containsId(List<String> ids, String officialId) {
        if (officialId == null || officialId.isEmpty() || ids == null) return false;
        for (String id : ids) if (officialId.equals(id)) return true;
        return false;
    }

    private static int nonSeaOceanCrossingCount(RiverCourse course) {
        if (course.reachesSea) return 0;
        int oceanRun = 0;
        int illegalRuns = 0;
        for (double station = 0.0D; station <= course.mouthDistance; station += 32.0D) {
            WorldPoint point = course.pointAtDistance(station);
            int x = (int)Math.round(point.x()), z = (int)Math.round(point.z());
            boolean waterLake = LakeData.sampleWorld(x, z).water();
            boolean ocean = !TerrainData.isLandWorld(x, z) && !waterLake;
            if (ocean) oceanRun++;
            else {
                if (oceanRun >= 3) illegalRuns++;
                oceanRun = 0;
            }
        }
        if (oceanRun >= 3) illegalRuns++;
        return illegalRuns;
    }

    private record ClassificationDecision(String mode, String reason) {}

    private static ClassificationDecision classifyAuthoredAlignment(
            RiverCourse course, RouteDeviationAudit base, double maxDeviation,
            double confluenceDeviation, ValleyAlignment valley,
            LakeIntersectionAudit lakeAudit, int illegalCoast) {
        // Explicit AR manual-keep: the validated high-mountain Arakawa approach is intentionally
        // retained for PJ terrain/city-building compatibility; P1.1 documents why replacing it by
        // the whole W05 stem is a regression. AR-04..09 must preserve that reason.
        if (course.name.equals("Arakawa")) {
            return new ClassificationDecision("MANUAL_KEEP",
                    "validated upper-route/rapid corridor intentionally differs from whole W05 stem");
        }
        if (lakeAudit.illegalCount() > 0 || illegalCoast > 0) {
            return new ClassificationDecision("REBUILD",
                    lakeAudit.illegalCount() > 0 ? "illegal active-lake intersection" : "illegal coast crossing");
        }
        // AR-02 deliberately does not classify from source-head distance alone: the compact W05
        // logical-course reference can have multiple upstream head nodes, while the authored layer
        // intentionally selects one city/terrain-compatible headwater. Rebuild is reserved for
        // broad plan-view failures; local deviations remain REFIT.
        if (finiteGreater(maxDeviation, 35000.0D) || finiteGreater(base.medianDeviationMetres(), 12000.0D)
                || finiteGreater(base.mouthErrorMetres(), 30000.0D)
                || finiteGreater(confluenceDeviation, 10000.0D) || valley.p90OffsetBlocks() > 80.0D) {
            return new ClassificationDecision("REBUILD", "large-scale route/W05/valley deviation");
        }
        if (finiteGreater(maxDeviation, 14000.0D) || finiteGreater(base.medianDeviationMetres(), 3500.0D)
                || finiteGreater(base.sourceErrorMetres(), 40000.0D)
                || finiteGreater(base.mouthErrorMetres(), 8000.0D)
                || finiteGreater(confluenceDeviation, 3000.0D) || valley.p90OffsetBlocks() > 48.0D) {
            return new ClassificationDecision("REFIT", "local route/W05/valley correction required");
        }
        return new ClassificationDecision("KEEP", "alignment within AR-02 tolerance");
    }

    private static boolean finiteGreater(double value, double threshold) {
        return Double.isFinite(value) && value > threshold;
    }

    record AuthoredAlignmentAudit(String river, String officialIdentifier, String waterSystemCode,
                                  double medianRouteDeviationMetres, double maximumRouteDeviationMetres,
                                  double sourceDeviationMetres, double mouthDeviationMetres,
                                  double confluenceDeviationMetres, double valleyP90OffsetBlocks,
                                  String valleyAlignment, int legalLakeIntersections,
                                  int illegalLakeIntersections, String lakeIntersectionSummary,
                                  String coastIntersection, int thirdPartyLakeCrossings,
                                  String classification, String classificationReason) {}

    record AuthoredRefreshPreview(String river, String classification, int currentPoints,
                                  int officialPoints, int candidatePoints,
                                  double maximumCandidateShiftBlocks,
                                  double medianCandidateShiftBlocks,
                                  double sourceShiftBlocks, double mouthShiftBlocks,
                                  boolean endpointsProtected, boolean candidateFinite) {}


    static List<InlandOceanAudit> inlandOceanAuditSnapshot() {
        List<InlandOceanAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            if (!course.reachesSea) continue;
            InlandOceanAudit audit = firstInlandOceanCrossing(course);
            if (audit != null) result.add(audit);
        }
        return List.copyOf(result);
    }

    record InlandOceanAudit(String river, double oceanStartStation, double reLandStation,
                            double oceanRunLength, double remainingToMouth,
                            int oceanStartX, int oceanStartZ, int reLandX, int reLandZ) {}

    record AuthoredTopologyAudit(List<TopologyPairAudit> overlappingPairs,
                                 List<TerminalMaskAudit> terminalMasks,
                                 List<RouteDeviationAudit> routeDeviations,
                                 List<RegressionSiteAudit> regressionSites,
                                 int unexpectedOverlapPairs,
                                 int differentWaterSystemOverlapPairs,
                                 int terminalCapProtrusionSamples,
                                 int confluenceWidthFailures,
                                 int excessiveParentBankFollowers,
                                 int inlandOceanCrossings,
                                 int severeMouthDeviations,
                                 List<String> inlandOceanRivers,
                                 List<String> severeMouthRivers) {}

    record TopologyPairAudit(String riverA, String riverB, String relationType,
                             String waterSystemA, String waterSystemB,
                             double minimumCentrelineDistance, int overlapIntervals,
                             double maximumOverlapRun, boolean unexpected,
                             boolean explicitlyLinked, int firstIllegalX, int firstIllegalZ,
                             boolean firstIllegalLand) {}

    record TerminalMaskAudit(String child, String parent, double childWidth,
                             double parentWidth, double widthRatio,
                             int childOnlyDownstreamSamples,
                             double parallelFollowerLength) {}

    record RouteDeviationAudit(String river, String officialIdentifier, String waterSystemCode,
                               double medianDeviationMetres, double p90DeviationMetres,
                               double sourceErrorMetres, double mouthErrorMetres) {}

    record RegressionSiteAudit(String label, int x, int z, String riverA, String riverB,
                               String relationType, String waterSystemA, String waterSystemB,
                               double distanceA, double distanceB, double widthA, double widthB,
                               boolean maskA, boolean maskB, boolean land,
                               double p90DeviationA, double p90DeviationB) {}

    /** Package-private nationwide terrace audit used by the sharp-turn regression task. */
    static List<RiverDropAudit> dropAuditSnapshot() {
        List<RiverDropAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            int dropCount = 0;
            int denseDropPairs = 0;
            int connectorOverlapRisks = 0;
            int wideSharpBendDrops = 0;
            int wideLowlandMultiBlockDrops = 0;
            int maximumDropHeight = 0;
            double minimumDropSpacing = Double.POSITIVE_INFINITY;
            double maximumConnectorLength = 0.0D;
            double maximumTurnAngle = 0.0D;
            double maximumWaterWidth = 0.0D;
            double previousBoundary = Double.NaN;
            double previousConnectorLength = 0.0D;

            for (int i = 1; i < course.waterProfile.length; i++) {
                double boundary = i * RiverCourse.RAPID_SPACING;
                // The final profile sample may lie just beyond a non-aligned mouth distance and
                // cannot form an in-world terrace boundary.
                if (boundary >= course.mouthDistance) continue;
                int upper = course.waterSurfaceY((i - 1) * RiverCourse.RAPID_SPACING);
                int lower = course.waterSurfaceY(boundary);
                if (upper <= lower) continue;

                DropInfo drop = new DropInfo(true, upper, lower, 0.0D);
                double progress = boundary / course.mouthDistance;
                double width = course.waterWidth(progress);
                double connectorLength = course.fallingConnectorLength(drop, width, boundary);
                double turnAngle = course.turnAngleAt(boundary, width);
                if (!Double.isNaN(previousBoundary)) {
                    double spacing = boundary - previousBoundary;
                    minimumDropSpacing = Math.min(minimumDropSpacing, spacing);
                    if (spacing <= RiverCourse.RAPID_SPACING * 4.0D) denseDropPairs++;
                    if (previousBoundary + previousConnectorLength >= boundary) {
                        connectorOverlapRisks++;
                    }
                }

                dropCount++;
                maximumDropHeight = Math.max(maximumDropHeight, upper - lower);
                maximumConnectorLength = Math.max(maximumConnectorLength, connectorLength);
                maximumTurnAngle = Math.max(maximumTurnAngle, turnAngle);
                maximumWaterWidth = Math.max(maximumWaterWidth, width);
                boolean estuaryTerminalTransition = course.reachesSea
                        && progress >= 0.94D && lower <= PJChunkGenerator.SEA_LEVEL + 1;
                if (width >= 48.0D && turnAngle >= 25.0D
                        && !estuaryTerminalTransition) wideSharpBendDrops++;
                if (course.maximumTerraceDrop <= 1 && width >= 32.0D
                        && upper - lower > 1) {
                    wideLowlandMultiBlockDrops++;
                }
                previousBoundary = boundary;
                previousConnectorLength = connectorLength;
            }

            result.add(new RiverDropAudit(course.name, dropCount, denseDropPairs,
                    connectorOverlapRisks, wideSharpBendDrops,
                    wideLowlandMultiBlockDrops,
                    maximumDropHeight,
                    Double.isInfinite(minimumDropSpacing) ? 0.0D : minimumDropSpacing,
                    maximumConnectorLength, maximumTurnAngle, maximumWaterWidth));
        }
        return List.copyOf(result);
    }

    record RiverDropAudit(String name, int dropCount, int denseDropPairs,
                          int connectorOverlapRisks, int wideSharpBendDrops,
                          int wideLowlandMultiBlockDrops, int maximumDropHeight,
                          double minimumDropSpacing,
                          double maximumConnectorLength, double maximumTurnAngle,
                          double maximumWaterWidth) {}

    record WideSharpBendAudit(String river, double station, double progress, double width,
                              double turnAngle, int upperY, int lowerY, int x, int z) {}

    static List<WideSharpBendAudit> wideSharpBendAuditSnapshot() {
        List<WideSharpBendAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            for (int i = 1; i < course.waterProfile.length; i++) {
                double boundary = i * RiverCourse.RAPID_SPACING;
                if (boundary >= course.mouthDistance) continue;
                int upper = course.waterSurfaceY((i - 1) * RiverCourse.RAPID_SPACING);
                int lower = course.waterSurfaceY(boundary);
                if (upper <= lower) continue;
                double progress = boundary / course.mouthDistance;
                double width = course.waterWidth(progress);
                double turn = course.turnAngleAt(boundary, width);
                boolean estuaryTerminalTransition = course.reachesSea
                        && progress >= 0.94D && lower <= PJChunkGenerator.SEA_LEVEL + 1;
                if (width >= 48.0D && turn >= 25.0D && !estuaryTerminalTransition) {
                    WorldPoint p = course.pointAtDistance(boundary);
                    result.add(new WideSharpBendAudit(course.name, boundary, progress, width, turn,
                            upper, lower, (int)Math.round(p.x()), (int)Math.round(p.z())));
                }
            }
        }
        return List.copyOf(result);
    }


    /** Whole-river continuity diagnostics for rapid-zone scheduling and hydraulic anchors. */
    static List<RiverContinuityAudit> continuityAuditSnapshot() {
        List<RiverContinuityAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            int badDensePairs = 0;
            int maximumDenseRun = 0;
            int currentDenseRun = 0;
            int periodicRuns = 0;
            int periodicRunLength = 0;
            int previousSpacing = -1;
            int previousBoundaryIndex = -1;
            int sourceLakeMismatch = 0;
            int terminalLakeMismatch = 0;
            int terminalRiverMismatch = 0;
            int terminalAnchorMismatch = Math.abs(course.waterSurfaceY(course.mouthDistance)
                    - course.terminalWaterY);
            int truncatedIsolatedRapids = 0;

            if (!course.sourceLakeName.isEmpty()) {
                sourceLakeMismatch = Math.abs(course.waterSurfaceY(0.0D)
                        - LakeData.waterSurfaceY(course.sourceLakeName));
            }
            if (!course.terminalLakeName.isEmpty()) {
                terminalLakeMismatch = Math.abs(course.terminalWaterY
                        - LakeData.waterSurfaceY(course.terminalLakeName));
            }
            if (course.terminalRiver != null) {
                terminalRiverMismatch = Math.abs(course.terminalWaterY
                        - course.terminalRiver.waterSurfaceY(course.confluenceParentStation));
            }

            List<Integer> dropIndices = new ArrayList<>();
            for (int i = 1; i < course.waterProfile.length; i++) {
                double boundary = i * RiverCourse.RAPID_SPACING;
                if (boundary >= course.mouthDistance) continue;
                int upper = course.waterSurfaceY((i - 1) * RiverCourse.RAPID_SPACING);
                int lower = course.waterSurfaceY(boundary);
                if (upper > lower) dropIndices.add(i);
            }

            for (int d = 0; d < dropIndices.size(); d++) {
                int i = dropIndices.get(d);
                if (previousBoundaryIndex >= 0) {
                    int spacing = i - previousBoundaryIndex;
                    double distance = i * RiverCourse.RAPID_SPACING;
                    double progress = clamp(distance / course.mouthDistance, 0.0D, 1.0D);
                    double width = course.waterWidth(progress);
                    int natural = RiverCourse.naturalSurfaceAt(course.pointAtDistance(distance));
                    int localWater = course.waterSurfaceY(distance);
                    double altitude = natural - PJChunkGenerator.SEA_LEVEL;
                    double actualIncision = Math.max(0.0D, natural - localWater);
                    boolean mountainRapidZone = course.headwaterInfluence(distance) > 0.0D
                            || (altitude >= 48.0D && width < 32.0D)
                            || actualIncision >= 12.0D;
                    if (spacing <= 4) {
                        currentDenseRun++;
                        maximumDenseRun = Math.max(maximumDenseRun, currentDenseRun);
                        if (!mountainRapidZone) badDensePairs++;
                    } else {
                        currentDenseRun = 0;
                    }

                    if (!mountainRapidZone && previousSpacing == spacing
                            && spacing >= 5 && spacing <= 96) {
                        periodicRunLength++;
                        if (periodicRunLength == 4) periodicRuns++;
                    } else {
                        periodicRunLength = 0;
                    }
                    previousSpacing = spacing;
                }

                int previous = d > 0 ? dropIndices.get(d - 1) : Integer.MIN_VALUE / 4;
                int next = d + 1 < dropIndices.size() ? dropIndices.get(d + 1)
                        : Integer.MAX_VALUE / 4;
                if (i - previous >= 10 && next - i >= 10) {
                    double probeDistance = i * RiverCourse.RAPID_SPACING + 3.0D;
                    if (probeDistance < course.mouthDistance) {
                        DropInfo info = course.dropInfo(probeDistance);
                        int upper = course.waterSurfaceY((i - 1) * RiverCourse.RAPID_SPACING);
                        int lower = course.waterSurfaceY(i * RiverCourse.RAPID_SPACING);
                        if (!info.active() || info.upperWaterY() != upper
                                || info.lowerWaterY() != lower
                                || info.offsetFromBoundary() < 0.0D) {
                            truncatedIsolatedRapids++;
                        }
                    }
                }
                previousBoundaryIndex = i;
            }
            result.add(new RiverContinuityAudit(course.name, badDensePairs,
                    maximumDenseRun, periodicRuns, sourceLakeMismatch,
                    terminalLakeMismatch, terminalRiverMismatch, terminalAnchorMismatch,
                    truncatedIsolatedRapids));
        }
        return List.copyOf(result);
    }

    record RiverContinuityAudit(String name, int nonMountainDensePairs,
                                int maximumDenseRun, int periodicRuns,
                                int sourceLakeMismatch, int terminalLakeMismatch,
                                int terminalRiverMismatch, int terminalAnchorMismatch,
                                int truncatedIsolatedRapids) {}

    record DenseRapidAudit(String river, double station, int spacing, double width,
                           int naturalY, int waterY, double altitude, double incision,
                           boolean mountainRapidZone, int x, int z) {}

    static List<DenseRapidAudit> denseRapidAuditSnapshot() {
        List<DenseRapidAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            int previous = -1;
            for (int i = 1; i < course.waterProfile.length; i++) {
                double boundary = i * RiverCourse.RAPID_SPACING;
                if (boundary >= course.mouthDistance) continue;
                int upper = course.waterSurfaceY((i - 1) * RiverCourse.RAPID_SPACING);
                int lower = course.waterSurfaceY(boundary);
                if (upper <= lower) continue;
                if (previous >= 0) {
                    int spacing = i - previous;
                    if (spacing <= 4) {
                        double progress = clamp(boundary / course.mouthDistance, 0.0D, 1.0D);
                        double width = course.waterWidth(progress);
                        WorldPoint p = course.pointAtDistance(boundary);
                        int natural = RiverCourse.naturalSurfaceAt(p);
                        int water = course.waterSurfaceY(boundary);
                        double altitude = natural - PJChunkGenerator.SEA_LEVEL;
                        double incision = Math.max(0.0D, natural - water);
                        boolean mountain = course.headwaterInfluence(boundary) > 0.0D
                                || (altitude >= 48.0D && width < 32.0D) || incision >= 12.0D;
                        if (!mountain) result.add(new DenseRapidAudit(course.name, boundary, spacing,
                                width, natural, water, altitude, incision, false,
                                (int)Math.round(p.x()), (int)Math.round(p.z())));
                    }
                }
                previous = i;
            }
        }
        return List.copyOf(result);
    }

    /** Hydraulic quantisation and spatial-index coverage diagnostics. */
    static List<RiverProfileAudit> profileAuditSnapshot() {
        List<RiverProfileAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            double maximumError = 0.0D;
            double maximumDeficit = 0.0D;
            for (int i = 0; i < course.waterProfile.length; i++) {
                double distance = Math.min(course.mouthDistance,
                        i * RiverCourse.RAPID_SPACING);
                maximumError = Math.max(maximumError, Math.abs(
                        course.rawWaterSurfaceY(distance)
                                - course.longitudinalProfileY(distance)));
            }
            for (double distance = 0.0D; distance <= course.mouthDistance; distance += 128.0D) {
                double progress = clamp(distance / course.mouthDistance, 0.0D, 1.0D);
                double width = course.waterWidth(progress);
                WorldPoint centre = course.pointAtDistance(distance);
                int natural = RiverCourse.naturalSurfaceAt(centre);
                int water = course.waterSurfaceY(distance);
                double slope = course.bankSlope(progress, width, natural, water);
                double support = course.maximumBankSupport(progress, width, natural, water, slope);
                double requested = width * 0.5D + support;
                maximumDeficit = Math.max(maximumDeficit, requested - course.segmentQueryRadius);
            }
            result.add(new RiverProfileAudit(course.name, maximumError, maximumDeficit));
        }
        return List.copyOf(result);
    }

    record RiverProfileAudit(String name, double maximumScheduledVsFloatingError,
                             double maximumRequestedRadiusMinusQueryRadius) {}

    /** Package-private whole-river terrain/blend audit. Water Y is never changed here. */
    static List<AuthoredSourceGeometryAudit> authoredSourceGeometryAuditSnapshot() {
        List<AuthoredSourceGeometryAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            if (!"REBUILD".equals(course.arRegionalMode)) continue;
            RouteDeviationAudit deviation = routeDeviationAudit(course);
            result.add(new AuthoredSourceGeometryAudit(course.name,
                    course.arOldSourceX, course.arOldSourceZ,
                    course.arProposedSourceX, course.arProposedSourceZ,
                    course.arAcceptedSourceX, course.arAcceptedSourceZ,
                    course.arProposedSourceRelocationBlocks,
                    course.arAcceptedSourceRelocationBlocks,
                    course.arRawFirstChordBlocks, course.arRawMaxChordBlocks,
                    course.arLongestStraightRunBlocks,
                    deviation.medianDeviationMetres(), maximumRouteDeviationMetres(course),
                    course.arSourceRelocationReason, course.arLongSourcePrefixLength,
                    course.arLongSourcePrefixJoinDistance, course.arNearSourceLakeName,
                    course.arNearSourceLakeDistanceBefore));
        }
        return List.copyOf(result);
    }

    record AuthoredSourceGeometryAudit(String river,
                                       double oldSourceX, double oldSourceZ,
                                       double proposedSourceX, double proposedSourceZ,
                                       double acceptedSourceX, double acceptedSourceZ,
                                       double proposedSourceRelocationBlocks,
                                       double acceptedSourceRelocationBlocks,
                                       double rawFirstChordBlocks, double rawMaxChordBlocks,
                                       double longestStraightRunBlocks,
                                       double officialMedianDeviationMetres,
                                       double officialMaximumDeviationMetres,
                                       String sourceRelocationReason,
                                       double longSourcePrefixLengthBlocks,
                                       double longSourcePrefixJoinDistanceBlocks,
                                       String nearSourceLakeName,
                                       double nearSourceLakeDistanceBeforeBlocks) {}

    static List<NearSourceLakeCollisionAudit> nearSourceLakeCollisionAuditSnapshot() {
        List<NearSourceLakeCollisionAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            if (course.arNearSourceLakeName.isEmpty()) continue;
            double first = Double.POSITIVE_INFINITY;
            for (FinalLakeConnection gate : course.finalLakeConnections) {
                if (gate.lakeName.equals(course.arNearSourceLakeName)) {
                    first = Math.min(first, gate.firstStation);
                }
            }
            WorldPoint source = course.pointAtDistance(0.0D);
            result.add(new NearSourceLakeCollisionAudit(course.name, course.arNearSourceLakeName,
                    course.arNearSourceLakeDistanceBefore, first, (int)Math.round(source.x()),
                    (int)Math.round(source.z()), course.arLongSourcePrefixLength,
                    course.arLongSourcePrefixJoinDistance, course.arRawMaxChordBlocks,
                    course.arLongestStraightRunBlocks, course.arSourceRelocationReason));
        }
        return List.copyOf(result);
    }

    record NearSourceLakeCollisionAudit(String river, String lake, double oldSourceToLakeDistanceBlocks,
                                        double newSourceToLakeFirstStationBlocks, int newSourceX, int newSourceZ,
                                        double prefixLengthBlocks, double joinDistanceBlocks,
                                        double rawMaxChordBlocks, double longestStraightRunBlocks,
                                        String resolution) {}

    static List<AuthoredStraightRunAudit> authoredStraightRunAuditSnapshot() {
        List<AuthoredStraightRunAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            if (!"REBUILD".equals(course.arRegionalMode)) continue;
            result.add(longestStraightRunAudit(course));
        }
        return List.copyOf(result);
    }

    private static AuthoredStraightRunAudit longestStraightRunAudit(RiverCourse course) {
        double longest = 0.0D, run = 0.0D, runStart = 0.0D;
        double bestStart = 0.0D, bestEnd = 0.0D;
        Segment previous = null;
        for (Segment segment : course.segments) {
            if (segment.startDistance() >= course.mouthDistance) break;
            if (previous == null) {
                run = segment.length(); runStart = segment.startDistance();
            } else {
                double dot = (previous.dx() * segment.dx() + previous.dz() * segment.dz())
                        / Math.max(1.0E-9D, previous.length() * segment.length());
                double turn = Math.toDegrees(Math.acos(clamp(dot, -1.0D, 1.0D)));
                if (turn < 2.0D) run += segment.length();
                else { run = segment.length(); runStart = segment.startDistance(); }
            }
            if (run > longest) {
                longest = run; bestStart = runStart;
                bestEnd = segment.startDistance() + segment.length();
            }
            previous = segment;
        }
        W05AuthoredReference.Route reference = W05AuthoredReference.route(course.name);
        double mean = Double.NaN, maximum = Double.NaN;
        if (reference != null && !reference.samples().isEmpty() && bestEnd > bestStart) {
            double sum = 0.0D; int count = 0; maximum = 0.0D;
            for (double station = bestStart; station <= bestEnd + 0.01D; station += 64.0D) {
                double d = nearestReferenceDistanceMetres(course.pointAtDistance(
                        Math.min(bestEnd, station)), reference.samples());
                sum += d; maximum = Math.max(maximum, d); count++;
            }
            mean = sum / Math.max(1, count);
        }
        double baseline = course.arBaselineLongestStraightRunBlocks;
        double excess = Double.isFinite(baseline) ? longest - baseline : Double.NaN;
        // AR-ENDPOINT-FIX must not rewrite the terminal approach of the 15 confluences that CF
        // already froze.  A long run wholly in the final ~2 km of such a child is reported, but
        // it is owned by frozen connection geometry rather than source relocation.
        boolean frozenConnectionApproach = false;
        if (course.terminalRiver != null) {
            ParentWaterGeometryContact contact = firstParentWaterMaskGeometryContact(course);
            frozenConnectionApproach = contact.found()
                    && bestEnd >= Math.max(0.0D, contact.childStation() - 2048.0D);
        }
        boolean generatedRegression = !frozenConnectionApproach && Double.isFinite(baseline)
                && longest > Math.max(1024.0D, baseline * 1.25D + 128.0D)
                && (!Double.isFinite(mean) || mean > 128.0D);
        return new AuthoredStraightRunAudit(course.name, longest, baseline, excess,
                bestStart, bestEnd, mean, maximum, frozenConnectionApproach, generatedRegression);
    }

    record AuthoredStraightRunAudit(String river, double longestStraightRunBlocks,
                                    double baselineLongestStraightRunBlocks,
                                    double generatedStraightRunExcessBlocks,
                                    double startStation, double endStation,
                                    double meanOfficialDeviationMetres,
                                    double maximumOfficialDeviationMetres,
                                    boolean frozenConnectionApproach,
                                    boolean generatedRegression) {}

    static List<SourceLakeGeometryAudit> sourceLakeGeometryAuditSnapshot() {
        List<SourceLakeGeometryAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            if (course.sourceLakeName.isEmpty()) continue;
            SourceLakeGeometryState state = sourceLakeGeometryState(course, course.sourceLakeName);
            WorldPoint source = course.pointAtDistance(0.0D);
            result.add(new SourceLakeGeometryAudit(course.name, course.sourceLakeName,
                    course.sourceLakeIntrusionBefore, course.sourceLakeIntrusionAfter,
                    state.firstWaterStation(), state.lastWaterStation(), state.intervalCount(),
                    (int)Math.round(source.x()), (int)Math.round(source.z()),
                    course.sourceLakeGeometryResolution, course.sourceLakeOfficialOutletRiverId,
                    course.sourceLakeOfficialGateDistance, course.sourceLakeOfficialRouteLength,
                    course.sourceLakeOfficialRouteMaxChord, course.sourceLakeOfficialJoinDistance,
                    course.sourceLakeOfficialEntityChain));
        }
        return List.copyOf(result);
    }

    record SourceLakeGeometryAudit(String river, String lake,
                                   double intrusionBeforeBlocks, double intrusionAfterBlocks,
                                   double firstWaterStation, double lastWaterStation,
                                   int intervalCount, int sourceX, int sourceZ,
                                   String resolution, String officialOutletRiverId,
                                   double officialGateDistanceBlocks, double officialRouteLengthBlocks,
                                   double officialRouteMaximumRawChordBlocks,
                                   double officialRouteJoinDistanceBlocks, String officialEntityChain) {}

    /** Fix3 C: first 2048 blocks after a source-lake outlet must follow the local valley, not lake->sea drop. */
    static List<SourceLakeOutletProfileAudit> sourceLakeOutletProfileAuditSnapshot() {
        List<SourceLakeOutletProfileAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            if (course.sourceLakeName.isEmpty()) continue;
            double limit = Math.min(2048.0D, course.mouthDistance);
            int maxSuspension = Integer.MIN_VALUE;
            double suspendedRun = 0.0D, longestSuspendedRun = 0.0D;
            int raisedBankColumns = 0;
            for (double station = 0.0D; station <= limit + 0.01D; station += 8.0D) {
                WorldPoint point = course.pointAtDistance(station);
                int natural = RiverCourse.naturalSurfaceAt(point);
                int water = course.waterSurfaceY(station);
                int delta = water - natural;
                maxSuspension = Math.max(maxSuspension, delta);
                if (delta > 2) {
                    suspendedRun += 8.0D;
                    longestSuspendedRun = Math.max(longestSuspendedRun, suspendedRun);
                } else suspendedRun = 0.0D;

                double progress = clamp(station / Math.max(1.0D, course.mouthDistance), 0.0D, 1.0D);
                double halfWater = course.waterWidth(progress) * 0.5D + course.waterRasterMargin(progress);
                WorldPoint tangent = course.unitTangentAt(station);
                double nx = -tangent.z(), nz = tangent.x();
                for (int side : new int[]{-1, 1}) {
                    double offset = halfWater + 2.0D;
                    int x = (int)Math.round(point.x() + nx * offset * side);
                    int z = (int)Math.round(point.z() + nz * offset * side);
                    int bankNatural = RiverCourse.naturalSurfaceAt(new WorldPoint(x, z));
                    RiverSample bankSample = sampleWorld(x, z, bankNatural);
                    if (bankSample.corridor() && !bankSample.water()
                            && bankSample.name().equals(course.name)) {
                        int generated = RiverBankTerrain.surface(x, z, bankNatural, bankSample);
                        if (generated > bankNatural) raisedBankColumns++;
                    }
                }
            }
            result.add(new SourceLakeOutletProfileAudit(course.name, course.sourceLakeName,
                    course.sourceWaterY, course.sourceLakeLocalTargetY,
                    course.sourceLakeLegacyRequiredTransition, course.sourceLakeGateHoldLength,
                    course.sourceLakeLocalTransitionLength, course.sourceConnectionLength,
                    maxSuspension == Integer.MIN_VALUE ? 0 : maxSuspension,
                    longestSuspendedRun, raisedBankColumns));
        }
        return List.copyOf(result);
    }

    record SourceLakeOutletProfileAudit(String river, String lake, int sourceLakeY, int localTargetY,
                                        double legacyRequiredTransition, double gateHoldLength,
                                        double localTransitionLength, double sourceConnectionLength,
                                        int maximumSuspension, double longestSuspendedRun,
                                        int overflowGuardRaisedColumns) {}

    static List<RiverTerrainAudit> terrainAuditSnapshot() {
        List<RiverTerrainAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            int maximumCentreCut = 0;
            int maximumCentreRaise = 0;
            int maximumBlendResidual = 0;
            double maximumBankSlope = 0.0D;
            double maximumBankMargin = 0.0D;
            for (double distance = 0.0D; distance <= course.mouthDistance; distance += 128.0D) {
                double progress = clamp(distance / course.mouthDistance, 0.0D, 1.0D);
                double width = course.waterWidth(progress);
                WorldPoint centre = course.pointAtDistance(distance);
                int natural = RiverCourse.naturalSurfaceAt(centre);
                int water = course.waterSurfaceY(distance);
                int reference = (int)Math.round(course.bankTerrainReferenceWaterLevel(
                        (int)Math.round(centre.x()), (int)Math.round(centre.z()), 0.0D,
                        distance, natural, water));
                double slope = course.bankSlope(progress, width, natural, water);
                double margin = course.maximumBankSupport(progress, width, natural, water, slope);
                int shorelineLevel = water;
                int residual = Math.max(0, natural
                        - (shorelineLevel + (int)Math.floor(margin * slope)));
                maximumCentreCut = Math.max(maximumCentreCut, natural - water);
                maximumCentreRaise = Math.max(maximumCentreRaise, water - natural);
                maximumBlendResidual = Math.max(maximumBlendResidual, residual);
                maximumBankSlope = Math.max(maximumBankSlope, slope);
                maximumBankMargin = Math.max(maximumBankMargin, margin);
            }
            result.add(new RiverTerrainAudit(course.name, maximumCentreCut,
                    maximumCentreRaise, maximumBlendResidual, maximumBankSlope,
                    maximumBankMargin));
        }
        return List.copyOf(result);
    }

    record RiverTerrainAudit(String name, int maximumCentreCut, int maximumCentreRaise,
                             int maximumBlendResidual, double maximumBankSlope,
                             double maximumBankMargin) {}

    /** Package-private headwater audit for DEM alignment and named source-lake outlets. */
    static List<HeadwaterAudit> headwaterAuditSnapshot() {
        List<HeadwaterAudit> result = new ArrayList<>(COURSES.size());
        for (RiverCourse course : COURSES) {
            WorldPoint source = course.pointAtDistance(0.0D);
            int x = (int)Math.round(source.x());
            int z = (int)Math.round(source.z());
            int naturalSurfaceY = RiverCourse.naturalSurfaceAt(source);
            int profileWaterY = course.waterSurfaceY(0.0D);
            result.add(new HeadwaterAudit(course.name, x, z,
                    course.sourceElevationMetres, course.sourceWaterY, profileWaterY,
                    naturalSurfaceY, profileWaterY - naturalSurfaceY,
                    course.sourceLakeName, course.sourceConnectionLength));
        }
        return List.copyOf(result);
    }

    record HeadwaterAudit(String name, int x, int z, int sourceElevationMetres,
                          int configuredWaterY, int profileWaterY, int naturalSurfaceY,
                          int waterMinusNatural, String sourceLakeName,
                          double sourceConnectionLength) {}

    /** Highest-risk remaining drop sites, independent of the original reported Yodo bend. */
    static List<RiverDropSite> riskyDropSitesSnapshot() {
        PriorityQueue<RiverDropSite> worst = new PriorityQueue<>(
                Comparator.comparingDouble(RiverDropSite::score));
        for (RiverCourse course : COURSES) {
            for (int i = 1; i < course.waterProfile.length; i++) {
                double boundary = i * RiverCourse.RAPID_SPACING;
                if (boundary >= course.mouthDistance) continue;
                int upper = course.waterSurfaceY(
                        (i - 1) * RiverCourse.RAPID_SPACING);
                int lower = course.waterSurfaceY(boundary);
                if (upper <= lower) continue;

                double progress = boundary / course.mouthDistance;
                double width = course.waterWidth(progress);
                double turnAngle = course.turnAngleAt(boundary, width);
                if (width < 28.0D || turnAngle < 10.0D) continue;
                WorldPoint point = course.pointAtDistance(boundary);
                double score = width * turnAngle * (upper - lower);
                worst.offer(new RiverDropSite(course.name,
                        (int)Math.round(point.x()), (int)Math.round(point.z()),
                        upper - lower, width, turnAngle, score));
                if (worst.size() > 12) worst.poll();
            }
        }
        List<RiverDropSite> result = new ArrayList<>(worst);
        result.sort(Comparator.comparingDouble(RiverDropSite::score).reversed());
        return List.copyOf(result);
    }

    record RiverDropSite(String name, int x, int z, int dropHeight,
                         double waterWidth, double turnAngle, double score) {}

    private static GeoPoint point(double longitude, double latitude) {
        return new GeoPoint(longitude, latitude);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    public record RiverSample(boolean corridor, boolean water, String name,
                              double distanceToCentre, double halfWaterWidth,
                              double halfCorridorWidth, double progress,
                              int waterSurfaceY, int bedY,
                              int maximumDepth, boolean terraceDrop,
                              boolean fallingConnector, boolean flowUpdate,
                              int upperWaterY, int lowerWaterY, boolean seaMouth,
                              double bankSlope, double distanceFromWater,
                              String connectedLakeName, double lakeConnectionBlend,
                              double lakeShorelineApertureWeight,
                              String terminalRiverName, int bankReferenceWaterY,
                              double headwaterInfluence) {
        public static final RiverSample NONE = new RiverSample(
                false, false, "", Double.POSITIVE_INFINITY, 0.0D, 0.0D,
                0.0D, PJChunkGenerator.SEA_LEVEL, PJChunkGenerator.SEA_LEVEL,
                0, false, false, false, PJChunkGenerator.SEA_LEVEL,
                PJChunkGenerator.SEA_LEVEL, false, 0.35D,
                Double.POSITIVE_INFINITY, "", 1.0D, 0.0D, "", PJChunkGenerator.SEA_LEVEL,
                0.0D);

        public double bankBlend() {
            if (!corridor || water) return 0.0D;
            return clamp((distanceToCentre - halfWaterWidth)
                    / Math.max(1.0D, halfCorridorWidth - halfWaterWidth), 0.0D, 1.0D);
        }

        public int waterWidth() {
            return (int)Math.round(halfWaterWidth * 2.0D);
        }

        /** Narrow floodplain/bench immediately outside the wet channel. */
        public double valleyBenchWidth() {
            return clamp(3.0D + waterWidth() * 0.08D, 3.0D, 14.0D);
        }

        /** Overflow guard is deliberately tiny; it is not a levee generator. */
        public double overflowGuardWidth() {
            return clamp(2.5D + waterWidth() * 0.012D, 2.5D, 5.0D);
        }

        /**
         * Width in which an already-sufficient natural bank is protected from over-carving.
         * This is preservation, not construction: RiverBankTerrain never raises such terrain
         * above the untouched DEM.
         */
        public double naturalBankPreservationWidth() {
            return clamp(2.0D + waterWidth() * 0.012D, 2.0D, 5.0D);
        }

        public boolean estuary() {
            return corridor && seaMouth && progress >= 0.88D;
        }

        public boolean overridesCoastline() {
            return water && estuary();
        }

        public boolean protectedHeadwater() {
            return headwaterInfluence > 0.0D;
        }

        public int headwaterMaximumBankCut() {
            return HEADWATER_MAX_DRY_BANK_CUT;
        }

        public int generatedWaterTopY() {
            // Lake gates and confluence/backwater blends can raise the effective local surface
            // above the raw profile terrace used by DropInfo. Never let a connector lower the
            // generated top below that already-resolved surface: doing so creates a dry river-bed
            // hole beside post-processed water, which immediately spills sideways when ticked.
            return fallingConnector ? Math.max(waterSurfaceY, upperWaterY) : waterSurfaceY;
        }

        public boolean needsFluidPostProcessing() {
            return corridor && water && (flowUpdate || fallingConnector);
        }

        public boolean connectsLake(String lakeName) {
            return !connectedLakeName.isEmpty() && connectedLakeName.equals(lakeName);
        }
    }

    /** CF-04 rendered centre-line gate audit over every legal physical lake overlap. */
    static List<FinalLakeGateAudit> finalLakeGateAuditSnapshot() {
        List<FinalLakeGateAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            for (FinalLakeConnection gate : course.finalLakeConnections) {
                double start = Math.max(0.0D, gate.firstStation - 128.0D);
                double end = Math.min(course.mouthDistance, gate.lastStation + 128.0D);
                int rises = 0, maxRise = 0, maxDrop = 0;
                int previous = Integer.MAX_VALUE;
                int firstRaw = course.waterSurfaceY(gate.firstStation);
                int lastRaw = course.waterSurfaceY(gate.lastStation);
                int upstreamRaw = course.waterSurfaceY(Math.max(0.0D, gate.firstStation - 1.0D));
                int downstreamRaw = course.waterSurfaceY(Math.min(course.mouthDistance, gate.lastStation + 1.0D));
                int samples = Math.max(2, (int)Math.ceil(end - start) + 1);
                for (int i = 0; i < samples; i++) {
                    double station = start + (end - start) * i / (double)(samples - 1);
                    WorldPoint point = course.pointAtDistance(station);
                    int x = (int)Math.round(point.x()), z = (int)Math.round(point.z());
                    LakeData.LakeSample lake = LakeData.sampleWorld(x, z);
                    int renderedY = lake.water() && lake.name().equals(gate.lakeName)
                            ? gate.lakeY : course.waterSurfaceY(station);
                    if (previous != Integer.MAX_VALUE) {
                        int delta = renderedY - previous;
                        if (delta > 0) { rises++; maxRise = Math.max(maxRise, delta); }
                        else maxDrop = Math.max(maxDrop, -delta);
                    }
                    previous = renderedY;
                }
                result.add(new FinalLakeGateAudit(course.name, gate.lakeId, gate.lakeName, gate.reason,
                        gate.firstStation, gate.lastStation, gate.lakeY, firstRaw, lastRaw,
                        upstreamRaw, downstreamRaw, rises, maxRise, maxDrop));
            }
        }
        return List.copyOf(result);
    }

    record FinalLakeGateAudit(String river, String lakeId, String lakeName, String relation,
                              double firstStation, double lastStation, int lakeY,
                              int firstRawRiverY, int lastRawRiverY,
                              int upstreamRawRiverY, int downstreamRawRiverY,
                              int renderedDownstreamRises, int maximumRenderedRise,
                              int maximumRenderedDrop) {}

    /** CF-LAKE-UNION cross-section stations derived from the same frozen physical contacts. */
    static List<FinalLakeCrossSectionSite> finalLakeCrossSectionSitesSnapshot() {
        List<FinalLakeCrossSectionSite> result = new ArrayList<>();
        double[] fractions = {0.0D, 0.25D, 0.50D, 0.75D, 1.0D};
        for (RiverCourse course : COURSES) {
            for (FinalLakeConnection gate : course.finalLakeConnections) {
                for (double fraction : fractions) {
                    double station = lerp(gate.firstStation, gate.lastStation, fraction);
                    WorldPoint center = course.pointAtDistance(station);
                    WorldPoint before = course.pointAtDistance(Math.max(0.0D, station - 8.0D));
                    WorldPoint after = course.pointAtDistance(Math.min(course.mouthDistance, station + 8.0D));
                    double tx = after.x() - before.x(), tz = after.z() - before.z();
                    double length = Math.max(1.0E-9D, Math.hypot(tx, tz));
                    double nx = -tz / length, nz = tx / length;
                    double progress = clamp(station / course.mouthDistance, 0.0D, 1.0D);
                    double halfWidth = course.waterWidth(progress) * 0.5D
                            + course.waterRasterMargin(progress);
                    result.add(new FinalLakeCrossSectionSite(course.name, gate.lakeId, gate.lakeName,
                            gate.reason, station, fraction, center.x(), center.z(), nx, nz,
                            halfWidth, gate.lakeY));
                }
            }
        }
        return List.copyOf(result);
    }

    record FinalLakeCrossSectionSite(String river, String lakeId, String lakeName, String relation,
                                     double station, double gateFraction,
                                     double centerX, double centerZ, double normalX, double normalZ,
                                     double halfWaterWidth, int lakeY) {}

    /** Fix4: samples every preserved physical shoreline boundary, not only merged hydraulic ends. */
    static List<FinalLakeShorelineApertureSite> finalLakeShorelineApertureSitesSnapshot() {
        List<FinalLakeShorelineApertureSite> result = new ArrayList<>();
        double[] offsets = {-16.0D, -8.0D, 0.0D, 8.0D, 16.0D};
        for (RiverCourse course : COURSES) {
            int spanOrdinal = 0;
            for (FinalLakeConnection gate : course.finalLakeConnections) {
                for (int intervalOrdinal = 0; intervalOrdinal < gate.physicalIntervals.size(); intervalOrdinal++) {
                    FinalLakeContactInterval interval = gate.physicalIntervals.get(intervalOrdinal);
                    for (ShorelineGateBoundary boundary : physicalShorelineGates(course, interval)) {
                        double gateStation = boundary.station;
                        for (double delta : offsets) {
                            double station = clamp(gateStation + delta, 0.0D, course.mouthDistance);
                            WorldPoint center = course.pointAtDistance(station);
                            WorldPoint tangent = course.unitTangentAt(station);
                            double nx = -tangent.z(), nz = tangent.x();
                            double progress = clamp(station / Math.max(1.0D, course.mouthDistance), 0.0D, 1.0D);
                            double halfWidth = course.waterWidth(progress) * 0.5D + course.waterRasterMargin(progress);
                            result.add(new FinalLakeShorelineApertureSite(course.name, gate.lakeId, gate.lakeName,
                                    interval.reason, boundary.side, gateStation, delta, station, center.x(), center.z(),
                                    nx, nz, halfWidth, gate.lakeY, spanOrdinal, intervalOrdinal,
                                    gate.physicalIntervals.size(), interval.firstStation, interval.lastStation,
                                    gate.firstStation, gate.lastStation));
                        }
                    }
                }
                spanOrdinal++;
            }
        }
        return List.copyOf(result);
    }

    /** Fix4 grouped-span accounting used by the hard aperture audit. */
    static List<FinalLakeConnectionTopologyAudit> finalLakeConnectionTopologySnapshot() {
        List<FinalLakeConnectionTopologyAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            int spanOrdinal = 0;
            for (FinalLakeConnection gate : course.finalLakeConnections) {
                int physicalGateCount = 0;
                for (FinalLakeContactInterval interval : gate.physicalIntervals) {
                    physicalGateCount += physicalShorelineGates(course, interval).size();
                }
                int legacyMergedGateCount = legacyMergedShorelineGateCount(course, gate);
                result.add(new FinalLakeConnectionTopologyAudit(course.name, gate.lakeId, gate.lakeName,
                        gate.reason, spanOrdinal, gate.firstStation, gate.lastStation,
                        gate.physicalIntervals.size(), physicalGateCount, legacyMergedGateCount,
                        Math.max(0, gate.physicalIntervals.size() - 1)));
                spanOrdinal++;
            }
        }
        return List.copyOf(result);
    }

    static List<FinalLakePhysicalIntervalAudit> finalLakePhysicalIntervalsSnapshot() {
        List<FinalLakePhysicalIntervalAudit> result = new ArrayList<>();
        for (RiverCourse course : COURSES) {
            int spanOrdinal = 0;
            for (FinalLakeConnection gate : course.finalLakeConnections) {
                for (int intervalOrdinal = 0; intervalOrdinal < gate.physicalIntervals.size(); intervalOrdinal++) {
                    FinalLakeContactInterval interval = gate.physicalIntervals.get(intervalOrdinal);
                    result.add(new FinalLakePhysicalIntervalAudit(course.name, gate.lakeId, gate.lakeName,
                            interval.reason, spanOrdinal, intervalOrdinal, interval.firstStation,
                            interval.lastStation, interval.lakeY));
                }
                spanOrdinal++;
            }
        }
        return List.copyOf(result);
    }

    private static List<ShorelineGateBoundary> physicalShorelineGates(
            RiverCourse course, FinalLakeContactInterval interval) {
        boolean sourceGate = interval.lakeName.equals(course.sourceLakeName)
                && (interval.reason.equals("SOURCE_FROM_LAKE") || interval.reason.equals("DECLARED_OUTLET"));
        boolean terminalGate = interval.lakeName.equals(course.terminalLakeName)
                || interval.reason.equals("TERMINAL_INTO_LAKE") || interval.reason.equals("DECLARED_INLET");
        boolean channelGate = interval.reason.equals("DECLARED_CHANNEL") || (!sourceGate && !terminalGate);
        List<ShorelineGateBoundary> gates = new ArrayList<>(2);
        if (sourceGate && interval.lastStation < course.mouthDistance - 1.0D) {
            gates.add(new ShorelineGateBoundary("LAST", interval.lastStation));
        } else if (terminalGate && interval.firstStation > 1.0D) {
            gates.add(new ShorelineGateBoundary("FIRST", interval.firstStation));
        }
        if (channelGate) {
            if (interval.firstStation > 1.0D) gates.add(new ShorelineGateBoundary("FIRST", interval.firstStation));
            if (interval.lastStation < course.mouthDistance - 1.0D
                    && Math.abs(interval.lastStation - interval.firstStation) > 1.0D) {
                gates.add(new ShorelineGateBoundary("LAST", interval.lastStation));
            }
        }
        return List.copyOf(gates);
    }

    private static int legacyMergedShorelineGateCount(RiverCourse course, FinalLakeConnection gate) {
        boolean sourceGate = gate.lakeName.equals(course.sourceLakeName)
                && (gate.reason.equals("SOURCE_FROM_LAKE") || gate.reason.equals("DECLARED_OUTLET"));
        boolean terminalGate = gate.lakeName.equals(course.terminalLakeName)
                || gate.reason.equals("TERMINAL_INTO_LAKE") || gate.reason.equals("DECLARED_INLET");
        boolean channelGate = gate.reason.equals("DECLARED_CHANNEL") || (!sourceGate && !terminalGate);
        int count = 0;
        if (sourceGate && gate.lastStation < course.mouthDistance - 1.0D) count++;
        else if (terminalGate && gate.firstStation > 1.0D) count++;
        if (channelGate) {
            if (gate.firstStation > 1.0D) count++;
            if (gate.lastStation < course.mouthDistance - 1.0D
                    && Math.abs(gate.lastStation - gate.firstStation) > 1.0D) count++;
        }
        return count;
    }

    private record ShorelineGateBoundary(String side, double station) {}

    record FinalLakeShorelineApertureSite(String river, String lakeId, String lakeName, String relation,
                                          String gateSide, double gateStation, double stationOffset,
                                          double station, double centerX, double centerZ,
                                          double normalX, double normalZ, double halfWaterWidth, int lakeY,
                                          int hydraulicSpanOrdinal, int physicalIntervalOrdinal,
                                          int physicalIntervalCount, double physicalFirstStation,
                                          double physicalLastStation, double hydraulicFirstStation,
                                          double hydraulicLastStation) {}

    record FinalLakeConnectionTopologyAudit(String river, String lakeId, String lakeName, String relation,
                                            int hydraulicSpanOrdinal, double hydraulicFirstStation,
                                            double hydraulicLastStation, int physicalIntervalCount,
                                            int physicalGateCount, int legacyMergedGateCount,
                                            int mergedIntervalCount) {}

    record FinalLakePhysicalIntervalAudit(String river, String lakeId, String lakeName, String relation,
                                          int hydraulicSpanOrdinal, int physicalIntervalOrdinal,
                                          double firstStation, double lastStation, int lakeY) {}

    /** Fix4: literal contiguous lake.water run before any hydraulic fragment merge. */
    private record FinalLakeContactInterval(String lakeId, String lakeName, String reason,
                                            double firstStation, double lastStation, int lakeY) {}

    /**
     * Grouped final lake connection. firstStation/lastStation are the CF-04 hydraulic span;
     * physicalIntervals preserve every real shoreline crossing (apart from <=16 block raster chatter).
     */
    private record FinalLakeConnection(String lakeId, String lakeName, String reason,
                                       double firstStation, double lastStation, int lakeY,
                                       List<FinalLakeContactInterval> physicalIntervals) {
        private FinalLakeConnection {
            physicalIntervals = List.copyOf(physicalIntervals);
        }
        double hydraulicFirstStation() { return firstStation; }
        double hydraulicLastStation() { return lastStation; }
    }

    private record ConnectionInfo(boolean active, String lakeName, int waterY,
                                  double riverInfluence, double apertureInfluence) {
        private static final ConnectionInfo NONE = new ConnectionInfo(false, "",
                PJChunkGenerator.SEA_LEVEL, 1.0D, 0.0D);
    }

    private enum JunctionType { NONE, CONFLUENCE, BIFURCATION }

    private record ChannelBathymetry(int waterY, int bedY, int depth, int maximumDepth,
                                     double lateralNormal, double edgeShoal) {}

    private record JunctionBathymetryAdjustment(boolean active, int bedY, String otherRiver,
                                                JunctionType type, double blend,
                                                int ownerLocalBedY) {
        private static final JunctionBathymetryAdjustment NONE =
                new JunctionBathymetryAdjustment(false, PJChunkGenerator.SEA_LEVEL, "",
                        JunctionType.NONE, 0.0D, PJChunkGenerator.SEA_LEVEL);
    }

    private record ConfluenceInfo(boolean active, int parentWaterY, int parentDepth,
                                  double childInfluence) {
        private static final ConfluenceInfo NONE = new ConfluenceInfo(false,
                PJChunkGenerator.SEA_LEVEL, 1, 1.0D);
    }

    /** Per-column river geometry shared by candidate selection and cross-course reconciliation. */
    private static final class CourseProbe {
        private final RiverCourse course;
        private final Nearest nearest;
        private final double progress;
        private final double width;
        private final double halfWaterWidth;
        private double bankReferenceWaterLevel = Double.NaN;

        private CourseProbe(RiverCourse course, Nearest nearest,
                            double progress, double width) {
            this.course = course;
            this.nearest = nearest;
            this.progress = progress;
            this.width = width;
            this.halfWaterWidth = width * 0.5D + course.waterRasterMargin(progress);
        }

        private double bankReferenceWaterLevel() {
            if (Double.isNaN(bankReferenceWaterLevel)) {
                bankReferenceWaterLevel = course.bankReferenceWaterLevel(
                        nearest.alongDistance());
            }
            return bankReferenceWaterLevel;
        }
    }

    private record HydraulicAdjustment(int waterY, boolean blended) {}

    private record GeoPoint(double longitude, double latitude) {}
    private record WorldPoint(double x, double z) {}
    private record JunctionBankTransition(JunctionBankCurve left, JunctionBankCurve right) {}
    private record JunctionBankCurve(double side, double blendStartStation,
                                     double contactStation, WorldPoint start,
                                     WorldPoint control1, WorldPoint control2,
                                     WorldPoint target) {}
    private record Segment(WorldPoint start, WorldPoint end, double startDistance, double length,
                           double dx, double dz, double inverseLengthSq) {
        private Segment(WorldPoint start, WorldPoint end, double startDistance, double length) {
            this(start, end, startDistance, length,
                    end.x() - start.x(), end.z() - start.z(),
                    1.0D / Math.max(1.0D, length * length));
        }
    }
    private record Nearest(double distance, double alongDistance) {}
    private record DropInfo(boolean active, int upperWaterY, int lowerWaterY, double offsetFromBoundary) {}
    private record OffshoreExtension(double unitX, double unitZ, double length, double score) {}
    public enum HydrologyClass {
        P0(1.00D), P1(0.75D), P2(0.50D);
        private final double wideningFactor;
        HydrologyClass(double wideningFactor) { this.wideningFactor = wideningFactor; }
        public double wideningFactor() { return wideningFactor; }
    }

    private record ConfluenceAnchor(WorldPoint officialPoint, String officialIdentifier) {}
    private record BifurcationAnchor(WorldPoint officialPoint, String officialIdentifier) {}

    private static final class RiverCourse {
        private final String name;
        private final int sourceElevationMetres;
        private int sourceWaterY;
        private int terminalWaterY;
        private final boolean reachesSea;
        private final int sourceWidth;
        private final int mouthWidth;
        private HydrologyClass hydrologyClass = HydrologyClass.P0;
        private final int maximumTerraceDrop;
        private List<Segment> segments;
        private Map<Long, List<Segment>> segmentGrid;
        private final double segmentQueryRadius;
        private final ThreadLocal<SegmentCellCache> segmentCellCache =
                ThreadLocal.withInitial(SegmentCellCache::new);
        private double totalLength;
        private double mouthDistance;
        private double minX;
        private double maxX;
        private double minZ;
        private double maxZ;
        private static final double PROFILE_SPACING = 16.0D;
        /** Fine Minecraft quantisation grid used only by DropScheduler. */
        private static final double RAPID_SPACING = 1.0D;
        /** DropScheduler may only move a threshold crossing a few blocks for terrain/bend aesthetics. */
        private static final int RAPID_RELOCATION_RADIUS_SAMPLES = 48;
        /** Integer water may never drift far from the solved floating hydraulic profile. */
        private static final double MAX_SCHEDULED_PROFILE_ERROR = 1.25D;
        private static final int MIN_TERRACE_SPACING_SAMPLES = 2;
        private static final int MAX_COALESCED_TERRACE_DROP = 6;
        private static final int DROP_RELOCATION_UPSTREAM_SAMPLES = 96;
        private static final double DROP_UPSTREAM_LENGTH = 4.0D;
        private static final double DROP_DOWNSTREAM_LENGTH = 14.0D;
        private static final double MIN_FALLING_CONNECTOR_LENGTH = 5.0D;
        private static final double MAX_FALLING_CONNECTOR_LENGTH = 9.0D;
        private static final double MIN_ESTUARY_EXTENSION = 768.0D;
        private static final double MAX_ESTUARY_EXTENSION = 4096.0D;
        private static final double ESTUARY_EXTENSION_STEP = 64.0D;
        private static final int REQUIRED_DEEP_OCEAN_SAMPLES = 6;
        private int[] waterProfile;
        /** Floating, whole-river hydraulic grade. Integer Minecraft water terraces are scheduled from this. */
        private double[] longitudinalProfile;
        private boolean profileInitializing;
        private int sourceNaturalY;
        private double headwaterProfileLength;
        private String sourceLakeName = "";
        private String terminalLakeName = "";
        /** CF-01 final legal LR-shoreline/AR-route overlap intervals; geometry remains frozen. */
        private final List<FinalLakeConnection> finalLakeConnections = new ArrayList<>();
        private String terminalRiverName = "";
        private RiverCourse terminalRiver;
        private ConfluenceAnchor confluenceAnchor;
        private double confluenceParentStation = Double.NaN;
        /** J-09 frozen terminal-bank transition for every authored confluence. */
        private JunctionBankTransition junctionBankTransition;
        private String sourceRiverName = "";
        private RiverCourse sourceRiver;
        private BifurcationAnchor bifurcationAnchor;
        private double bifurcationParentStation = Double.NaN;
        private final List<WorldPoint> fixedConfluenceAnchors = new ArrayList<>();
        private double sourceConnectionLength;
        private double sourceConnectionHoldFraction = 0.55D;
        /** Fix3 split source-lake profile: short hydraulic gate hold + local valley transition. */
        private double sourceLakeGateHoldLength;
        private double sourceLakeLocalTransitionLength;
        private int sourceLakeLocalTargetY = Integer.MIN_VALUE;
        private double sourceLakeLegacyRequiredTransition;
        private double terminalConnectionLength;
        private double sourceLakeIntrusionBefore = Double.NaN;
        private double sourceLakeIntrusionAfter = Double.NaN;
        private String sourceLakeGeometryResolution = "";
        private String sourceLakeOfficialOutletRiverId = "";
        private double sourceLakeOfficialGateDistance = Double.NaN;
        private double sourceLakeOfficialRouteLength = Double.NaN;
        private double sourceLakeOfficialRouteMaxChord = Double.NaN;
        private double sourceLakeOfficialJoinDistance = Double.NaN;
        private String sourceLakeOfficialEntityChain = "";
        private double arProposedSourceRelocationBlocks = Double.NaN;
        private double arAcceptedSourceRelocationBlocks = Double.NaN;
        private double arRawFirstChordBlocks = Double.NaN;
        private double arRawMaxChordBlocks = Double.NaN;
        private double arLongestStraightRunBlocks = Double.NaN;
        private double arBaselineLongestStraightRunBlocks = Double.NaN;
        private double arOldSourceX = Double.NaN, arOldSourceZ = Double.NaN;
        private double arProposedSourceX = Double.NaN, arProposedSourceZ = Double.NaN;
        private double arAcceptedSourceX = Double.NaN, arAcceptedSourceZ = Double.NaN;
        private String arSourceRelocationReason = "";
        private double arLongSourcePrefixLength = Double.NaN;
        private double arLongSourcePrefixJoinDistance = Double.NaN;
        private double arNearSourceLakeDistanceBefore = Double.NaN;
        private String arNearSourceLakeName = "";
        // AR-04..09 regional geometry refresh diagnostics. These are metadata only and never
        // participate in water/profile ownership.
        private String arRegionalBatch = "";
        private String arRegionalMode = "KEEP";
        private String arRegionalFallback = "";
        private boolean arRegionalRefreshAttempted;
        private double arBaselineMedianDeviationMetres = Double.NaN;
        /** True only when a first-contact mismatch required a local terminal profile refit. */
        private boolean terminalHydraulicApproachRefit;
        private static final double CONFLUENCE_LENGTH = 640.0D;
        private static final double CONFLUENCE_LEVEL_HOLD = 256.0D;
        private static final double TERMINAL_WIDTH_BLEND_LENGTH = 512.0D;
        private static final double JUNCTION_BANK_BLEND_LENGTH = 144.0D;
        private static final double MAX_PARENT_BANK_FOLLOWER = 768.0D;
        private static final double END_CAP_ALLOWANCE = 1.5D;

        private RiverCourse(String name, int sourceElevationMetres,
                            int terminalWaterY, boolean reachesSea,
                            int sourceWidth, int mouthWidth, int maximumTerraceDrop,
                            List<GeoPoint> geoPoints) {
            if (geoPoints.size() < 2) throw new IllegalArgumentException("A river needs at least two points");
            this.name = name;
            this.sourceElevationMetres = sourceElevationMetres;
            this.sourceWaterY = PJChunkGenerator.SEA_LEVEL + (int)Math.round(
                    sourceElevationMetres / TerrainData.VERTICAL_METRES_PER_BLOCK);
            this.terminalWaterY = terminalWaterY;
            this.reachesSea = reachesSea;
            this.sourceWidth = sourceWidth;
            this.mouthWidth = mouthWidth;
            this.maximumTerraceDrop = Math.max(1, Math.min(4, maximumTerraceDrop));

            List<WorldPoint> controlPoints = new ArrayList<>();
            for (GeoPoint point : geoPoints) {
                controlPoints.add(new WorldPoint(
                        TerrainData.worldXFromLongitude(point.longitude()),
                        TerrainData.worldZFromLatitude(point.latitude())));
            }

            // Subdivide the coarse hand-authored route with a Catmull-Rom spline. The original
            // straight control-point segments were visibly canal-like, especially in mountain
            // valleys. This keeps the same broad route while producing continuous bends.
            List<WorldPoint> points = densifyRoute(smoothRoute(
                    snapRouteToValleys(buildSpline(controlPoints))));
            List<Segment> built = new ArrayList<>();
            double cumulative = 0.0D;
            double localMinX = Double.POSITIVE_INFINITY;
            double localMaxX = Double.NEGATIVE_INFINITY;
            double localMinZ = Double.POSITIVE_INFINITY;
            double localMaxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < points.size() - 1; i++) {
                WorldPoint start = points.get(i);
                WorldPoint end = points.get(i + 1);
                double dx = end.x() - start.x();
                double dz = end.z() - start.z();
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length < 1.0D) continue;
                built.add(new Segment(start, end, cumulative, length));
                cumulative += length;
                localMinX = Math.min(localMinX, Math.min(start.x(), end.x()));
                localMaxX = Math.max(localMaxX, Math.max(start.x(), end.x()));
                localMinZ = Math.min(localMinZ, Math.min(start.z(), end.z()));
                localMaxZ = Math.max(localMaxZ, Math.max(start.z(), end.z()));
            }
            this.mouthDistance = Math.max(1.0D, cumulative);

            if (reachesSea) {
            // Continue the centre-line offshore until it is safely inside naturally deep ocean.
            // A fixed 384-block extension ended while the base coastline shelf was still shallow.
            // Because nearest-point projection clamps to the segment endpoint, that produced a
            // semicircular end cap which looked exactly like the pre-river coastline surviving
            // beneath the mouth. The endpoint is now chosen from the actual coastline mask and is
            // accepted only after several consecutive deep-ocean samples. At that point the
            // estuary override resolves to the already-deeper natural seabed, so its rounded cap is
            // geometrically invisible.
            WorldPoint mouth = points.get(points.size() - 1);
            WorldPoint beforeMouth = points.get(points.size() - 2);
            double tangentX = mouth.x() - beforeMouth.x();
            double tangentZ = mouth.z() - beforeMouth.z();
            double tangentLength = Math.max(1.0D, Math.hypot(tangentX, tangentZ));
            double tangentUnitX = tangentX / tangentLength;
            double tangentUnitZ = tangentZ / tangentLength;
            OffshoreExtension extension = findOffshoreExtension(
                    mouth, tangentUnitX, tangentUnitZ);
            WorldPoint offshore = new WorldPoint(
                    mouth.x() + extension.unitX() * extension.length(),
                    mouth.z() + extension.unitZ() * extension.length());
            built.add(new Segment(mouth, offshore, cumulative, extension.length()));
            cumulative += extension.length();
            localMinX = Math.min(localMinX, offshore.x());
            localMaxX = Math.max(localMaxX, offshore.x());
            localMinZ = Math.min(localMinZ, offshore.z());
            localMaxZ = Math.max(localMaxZ, offshore.z());
            }

            this.segments = List.copyOf(built);
            // The spatial index must cover the same envelope that ValleyCarver can request.
            // The previous ~800 block query radius silently truncated deep mountain valleys even
            // though maximumBankSupport() was allowed to request up to 2048 blocks.
            this.segmentQueryRadius = Math.max(sourceWidth, mouthWidth) * 0.60D
                    + RIVER_BANK_MAX_MARGIN + 96.0D;
            this.segmentGrid = buildSegmentGrid(this.segments);
            this.totalLength = Math.max(1.0D, cumulative);
            this.sourceNaturalY = naturalSurfaceAt(points.get(0));
            double expansion = Math.max(sourceWidth, mouthWidth) * 0.60D
                    + RIVER_BANK_MAX_MARGIN + 128.0D;
            this.minX = localMinX - expansion;
            this.maxX = localMaxX + expansion;
            this.minZ = localMinZ - expansion;
            this.maxZ = localMaxZ + expansion;
        }


        private void trimSourceLakePrefix(double startStation) {
            double start = clamp(startStation, 0.0D, Math.max(0.0D, mouthDistance - 1.0D));
            rebuildSourceFromOfficialOutlet(pointAtDistance(start), start);
        }

        private void rebuildSourceFromOfficialOutlet(WorldPoint gate, double resumeStation) {
            double resume = clamp(resumeStation, 0.0D, Math.max(0.0D, mouthDistance - 1.0D));
            double oldMouth = mouthDistance;
            double oldTotal = totalLength;
            WorldPoint oldOffshore = reachesSea ? pointAtDistance(oldTotal) : null;
            List<WorldPoint> points = new ArrayList<>();
            points.add(gate);
            for (Segment segment : segments) {
                double end = segment.startDistance() + segment.length();
                if (end <= resume + 0.01D || segment.startDistance() >= oldMouth) continue;
                WorldPoint p = end < oldMouth - 0.01D ? segment.end() : pointAtDistance(oldMouth);
                if (distance(points.get(points.size() - 1), p) >= 1.0D) points.add(p);
                if (end >= oldMouth) break;
            }
            WorldPoint mouth = pointAtDistance(oldMouth);
            if (distance(points.get(points.size() - 1), mouth) >= 1.0D) points.add(mouth);
            int mouthIndex = points.size() - 1;
            if (reachesSea && oldOffshore != null
                    && distance(points.get(points.size() - 1), oldOffshore) >= 1.0D) {
                points.add(oldOffshore);
            }
            if (points.size() < 2) throw new IllegalStateException("Cannot rebuild source route " + name);
            rebuildGeometry(points, mouthIndex);
            this.sourceNaturalY = naturalSurfaceAt(pointAtDistance(0.0D));
        }

        private void rebuildSourceFromOfficialRoute(
                List<SourceLakeOfficialRouteResolver.Point> officialPrefix, double resumeStation) {
            if (officialPrefix == null || officialPrefix.size() < 2) {
                throw new IllegalStateException("Missing official source-lake prefix for " + name);
            }
            double resume = clamp(resumeStation, 0.0D, Math.max(0.0D, mouthDistance - 1.0D));
            double oldMouth = mouthDistance;
            double oldTotal = totalLength;
            WorldPoint oldOffshore = reachesSea ? pointAtDistance(oldTotal) : null;
            List<WorldPoint> points = new ArrayList<>();
            for (SourceLakeOfficialRouteResolver.Point p : officialPrefix) {
                WorldPoint next = new WorldPoint(p.x(), p.z());
                if (points.isEmpty() || distance(points.get(points.size() - 1), next) >= 1.0D) {
                    points.add(next);
                }
            }
            WorldPoint join = pointAtDistance(resume);
            double joinGap = distance(points.get(points.size() - 1), join);
            if (joinGap > 384.0D) {
                throw new IllegalStateException("Official source-lake prefix creates a join chord for "
                        + name + ": " + joinGap);
            }
            if (joinGap >= 1.0D) points.add(join);
            for (Segment segment : segments) {
                double end = segment.startDistance() + segment.length();
                if (end <= resume + 0.01D || segment.startDistance() >= oldMouth) continue;
                WorldPoint p = end < oldMouth - 0.01D ? segment.end() : pointAtDistance(oldMouth);
                if (distance(points.get(points.size() - 1), p) >= 1.0D) points.add(p);
                if (end >= oldMouth) break;
            }
            WorldPoint mouth = pointAtDistance(oldMouth);
            if (distance(points.get(points.size() - 1), mouth) >= 1.0D) points.add(mouth);
            int mouthIndex = points.size() - 1;
            if (reachesSea && oldOffshore != null
                    && distance(points.get(points.size() - 1), oldOffshore) >= 1.0D) {
                points.add(oldOffshore);
            }
            rebuildGeometry(points, mouthIndex);
            this.sourceNaturalY = naturalSurfaceAt(pointAtDistance(0.0D));
        }

        private void connectSourceLake(String lakeName) {
            this.sourceLakeName = lakeName;
            this.sourceWaterY = LakeData.waterSurfaceY(lakeName);

            // Fix3: source-lake geometry has already been trimmed/rebuilt to the real outlet.
            // The outlet hydraulic transition therefore owns only the local lake -> valley drop,
            // never the lake -> sea/terminal total drop.  The legacy total-drop value is retained
            // solely for validation/reporting so Sagami's former ~3.2k-block suspension cannot
            // silently return.
            double totalDrop = Math.max(0.0D, sourceWaterY - terminalWaterY);
            this.sourceLakeLegacyRequiredTransition = totalDrop * PROFILE_SPACING * 1.75D
                    / maximumTerraceDrop;

            double sourceWidthNow = waterWidth(0.0D);
            this.sourceLakeGateHoldLength = clamp(32.0D + sourceWidthNow * 0.40D,
                    32.0D, 96.0D);
            this.sourceLakeLocalTargetY = resolveSourceLakeLocalTargetY();
            double localDrop = Math.max(0.0D, sourceWaterY - sourceLakeLocalTargetY);
            double descentLength = localDrop <= 0.0D ? 64.0D
                    : localDrop * PROFILE_SPACING * 1.75D / maximumTerraceDrop;
            this.sourceLakeLocalTransitionLength = clamp(sourceLakeGateHoldLength
                    + Math.max(64.0D, descentLength), sourceLakeGateHoldLength + 64.0D, 1024.0D);
            this.sourceConnectionLength = Math.min(mouthDistance, sourceLakeLocalTransitionLength);
            this.sourceConnectionHoldFraction = clamp(sourceLakeGateHoldLength
                    / Math.max(1.0D, sourceConnectionLength), 0.0D, 0.75D);
        }

        /** Robust local valley target for a source-lake outlet; no terminal/sea Y participates. */
        private int resolveSourceLakeLocalTargetY() {
            List<Integer> targets = new ArrayList<>();
            double end = Math.min(mouthDistance, 1024.0D);
            double start = Math.min(end, Math.max(128.0D, sourceLakeGateHoldLength + 64.0D));
            for (double distance = start; distance <= end + 0.01D; distance += 64.0D) {
                double progress = clamp(distance / Math.max(1.0D, mouthDistance), 0.0D, 1.0D);
                double width = waterWidth(progress);
                int natural = valleySurfaceAt(distance, width);
                double incisionMetres = clamp(4.0D + width * 0.04D + progress * 4.0D,
                        4.0D, 16.0D);
                int incision = Math.max(1, (int)Math.round(
                        incisionMetres / TerrainData.VERTICAL_METRES_PER_BLOCK));
                int terrainPreferred = natural - incision;
                int lowestCompatible = natural - profileTerrainCutLimit(progress, width, natural);
                int compatible = Math.max(lowestCompatible, terrainPreferred);
                targets.add(Math.max(terminalWaterY, Math.min(sourceWaterY, compatible)));
            }
            if (targets.isEmpty()) return sourceWaterY;
            targets.sort(Integer::compareTo);
            // Use the upper quartile of the local valley-compatible targets.  This is deliberately
            // conservative: an outlet transition should leave the lake smoothly and hand control
            // back to the ordinary profile, not chase the deepest basin sample in the first km.
            int index = (int)Math.floor((targets.size() - 1) * 0.75D);
            return targets.get(Math.max(0, Math.min(targets.size() - 1, index)));
        }

        private void connectTerminalLake(String lakeName) {
            this.terminalLakeName = lakeName;
            this.terminalWaterY = LakeData.waterSurfaceY(lakeName);
            this.terminalConnectionLength = findTerminalLakeConnectionLength(lakeName);
        }

        private double findSourceLakeConnectionLength(String lakeName) {
            double lastWater = 0.0D;
            for (double distance = 0.0D; distance <= Math.min(mouthDistance, 2048.0D);
                 distance += 16.0D) {
                WorldPoint point = pointAtDistance(distance);
                LakeData.LakeSample lake = LakeData.sampleWorld((int)Math.round(point.x()),
                        (int)Math.round(point.z()));
                if (lake.water() && lake.name().equals(lakeName)) lastWater = distance;
            }
            return clamp(lastWater + 256.0D, 192.0D, 1280.0D);
        }

        private double findTerminalLakeConnectionLength(String lakeName) {
            double firstWaterFromEnd = 0.0D;
            for (double remaining = 0.0D; remaining <= Math.min(mouthDistance, 2048.0D);
                 remaining += 16.0D) {
                WorldPoint point = pointAtDistance(mouthDistance - remaining);
                LakeData.LakeSample lake = LakeData.sampleWorld((int)Math.round(point.x()),
                        (int)Math.round(point.z()));
                if (lake.water() && lake.name().equals(lakeName)) {
                    firstWaterFromEnd = remaining;
                }
            }
            return clamp(firstWaterFromEnd + 256.0D, 192.0D, 1280.0D);
        }

        private void connectTerminalRiver(RiverCourse parent, ConfluenceAnchor anchor) {
            this.terminalRiver = parent;
            this.terminalRiverName = parent.name;
            this.confluenceAnchor = anchor;
        }

        private void connectBifurcation(RiverCourse parent, BifurcationAnchor anchor) {
            this.sourceRiver = parent;
            this.sourceRiverName = parent.name;
            this.bifurcationAnchor = anchor;
        }

        private void snapParentToConfluenceAnchor() {
            if (terminalRiver == null || confluenceAnchor == null) return;
            terminalRiver.snapRouteThrough(confluenceAnchor.officialPoint());
        }

        private void snapParentToBifurcationAnchor() {
            if (sourceRiver == null || bifurcationAnchor == null) return;
            sourceRiver.snapRouteThrough(bifurcationAnchor.officialPoint());
        }

        private void resolveTerminalGeometry() {
            if (terminalRiver == null || confluenceAnchor == null) return;
            reshapeTerminalRoute(confluenceAnchor.officialPoint());
            Nearest parentNearest = terminalRiver.nearestUnbounded(
                    confluenceAnchor.officialPoint().x(), confluenceAnchor.officialPoint().z());
            confluenceParentStation = parentNearest.alongDistance();
            initializeJunctionBankTransition();
        }

        private void resolveBifurcationGeometry() {
            if (sourceRiver == null || bifurcationAnchor == null) return;
            snapRouteStart(bifurcationAnchor.officialPoint(), 768.0D);
            Nearest parentNearest = sourceRiver.nearestUnbounded(
                    bifurcationAnchor.officialPoint().x(), bifurcationAnchor.officialPoint().z());
            bifurcationParentStation = parentNearest.alongDistance();
        }

        /** Locally displaces a coarse authored main stem so it passes through the W05 node. */
        private void snapRouteThrough(WorldPoint anchor) {
            Nearest projection = nearestUnbounded(anchor.x(), anchor.z());
            if (projection.distance() <= 0.01D) {
                fixedConfluenceAnchors.add(anchor);
                return;
            }

            // P1.1: a topology fix must remain local.  The former 4096-block displacement could
            // bend an entire authored main stem merely to hit one node.  Large discrepancies are
            // now handled by W05 route overrides instead of long-distance rubber-sheeting.
            double radius = clamp(384.0D + projection.distance() * 1.5D,
                    384.0D, 768.0D);
            WorldPoint projected = pointAtDistance(projection.alongDistance());
            double offsetX = anchor.x() - projected.x();
            double offsetZ = anchor.z() - projected.z();

            List<Double> stations = geometryStations();
            stations.add(projection.alongDistance());
            stations.add(mouthDistance);
            List<Double> fixedStations = new ArrayList<>(fixedConfluenceAnchors.size());
            for (WorldPoint fixed : fixedConfluenceAnchors) {
                double fixedStation = nearestUnbounded(fixed.x(), fixed.z()).alongDistance();
                fixedStations.add(fixedStation);
                stations.add(fixedStation);
            }
            stations.sort(Double::compareTo);
            List<WorldPoint> points = new ArrayList<>(stations.size());
            int mouthIndex = -1;
            double previousStation = Double.NaN;
            for (double station : stations) {
                if (!Double.isNaN(previousStation) && Math.abs(station - previousStation) < 0.001D) {
                    continue;
                }
                WorldPoint original = pointAtDistance(station);
                double delta = Math.abs(station - projection.alongDistance());
                double weight = delta >= radius ? 0.0D
                        : 1.0D - smoothstep(0.0D, radius, delta);
                if (delta >= 0.001D) {
                    for (double fixedStation : fixedStations) {
                        weight *= smoothstep(0.0D, 256.0D,
                                Math.abs(station - fixedStation));
                    }
                }
                points.add(new WorldPoint(original.x() + offsetX * weight,
                        original.z() + offsetZ * weight));
                if (Math.abs(station - mouthDistance) < 0.001D) mouthIndex = points.size() - 1;
                previousStation = station;
            }
            if (mouthIndex < 0) throw new IllegalStateException("Missing mouth station for " + name);
            rebuildGeometry(points, mouthIndex);
            fixedConfluenceAnchors.add(anchor);
        }

        /** Pins a distributary source to its parent anchor without moving the downstream route. */
        private void snapRouteStart(WorldPoint anchor, double blendLength) {
            WorldPoint originalStart = pointAtDistance(0.0D);
            double offsetX = anchor.x() - originalStart.x();
            double offsetZ = anchor.z() - originalStart.z();
            if (Math.hypot(offsetX, offsetZ) <= 0.25D) return;

            List<Double> stations = geometryStations();
            stations.add(mouthDistance);
            stations.sort(Double::compareTo);
            List<WorldPoint> points = new ArrayList<>(stations.size());
            int mouthIndex = -1;
            double previousStation = Double.NaN;
            for (double station : stations) {
                if (!Double.isNaN(previousStation) && Math.abs(station - previousStation) < 0.001D) {
                    continue;
                }
                WorldPoint original = pointAtDistance(station);
                double weight = station >= blendLength ? 0.0D
                        : 1.0D - smoothstep(0.0D, blendLength, station);
                WorldPoint shifted = new WorldPoint(original.x() + offsetX * weight,
                        original.z() + offsetZ * weight);
                if (points.isEmpty()) shifted = anchor;
                points.add(shifted);
                if (Math.abs(station - mouthDistance) < 0.001D) mouthIndex = points.size() - 1;
                previousStation = station;
            }
            if (mouthIndex < 0) throw new IllegalStateException("Missing mouth station for " + name);
            rebuildGeometry(points, mouthIndex);
        }


        /** Replaces only the terminal approach, preserving the authored upper route. */
        private void reshapeTerminalRoute(WorldPoint anchor) {
            List<WorldPoint> officialApproach = officialTerminalApproach();
            if (!officialApproach.isEmpty()) {
                spliceOfficialTerminalApproach(officialApproach, anchor);
                // W05 is authoritative for the long approach, but the authored parent itself is
                // still a compact representation.  If its finite corridor grazes the last few
                // hundred blocks of the W05 child more than once, repair only that local join;
                // never reintroduce P1's kilometre-scale bank follower.
                for (int attempt = 0; attempt < 2; attempt++) {
                    ParentOverlapStats overlap = parentOverlapStats();
                    if (overlap.intervals() == 1
                            && overlap.tailLength() <= naturalConfluenceTailAllowance() + 8.0D) {
                        break;
                    }
                    if (!Double.isNaN(overlap.firstStart())
                            && mouthDistance - overlap.firstStart() <= MAX_PARENT_BANK_FOLLOWER) {
                        rerouteTerminalApproach(anchor, overlap.firstStart(), attempt);
                    } else {
                        break;
                    }
                }
                return;
            }
            Nearest projection = nearestUnbounded(anchor.x(), anchor.z());
            double chordToAnchor = projection.distance();
            double blendLength = clamp(1024.0D + chordToAnchor * 0.35D,
                    1024.0D, 4096.0D);
            double repairStart = Math.max(0.0D, projection.alongDistance() - blendLength);

            // If the coarse child crosses the parent before the official junction (Iruma was the
            // worst case), discard the geometry from shortly before that first crossing as well.
            double firstOverlap = firstParentCorridorOverlap();
            double naturalTail = naturalConfluenceTailAllowance();
            if (!Double.isNaN(firstOverlap)
                    && mouthDistance - firstOverlap > naturalTail) {
                repairStart = Math.min(repairStart, Math.max(0.0D, firstOverlap - 256.0D));
            }

            WorldPoint start = pointAtDistance(repairStart);
            WorldPoint startDirection = unitTangentAt(repairStart);
            Nearest parentAtAnchor = terminalRiver.nearestUnbounded(anchor.x(), anchor.z());
            WorldPoint parentDirection = terminalRiver.unitTangentAt(parentAtAnchor.alongDistance());
            double chordX = anchor.x() - start.x();
            double chordZ = anchor.z() - start.z();
            double chordLength = Math.max(1.0D, Math.hypot(chordX, chordZ));
            WorldPoint chordDirection = new WorldPoint(chordX / chordLength, chordZ / chordLength);
            WorldPoint endDirection = normalized(new WorldPoint(
                    chordDirection.x() * 0.70D + parentDirection.x() * 0.30D,
                    chordDirection.z() * 0.70D + parentDirection.z() * 0.30D));
            double startHandle = Math.min(chordLength * 0.35D,
                    Math.max(128.0D, (projection.alongDistance() - repairStart) * 0.75D));
            double endHandle = Math.min(512.0D, chordLength * 0.22D);
            WorldPoint control1 = new WorldPoint(start.x() + startDirection.x() * startHandle,
                    start.z() + startDirection.z() * startHandle);
            WorldPoint control2 = new WorldPoint(anchor.x() - endDirection.x() * endHandle,
                    anchor.z() - endDirection.z() * endHandle);

            List<WorldPoint> points = prefixPoints(repairStart);
            int fixedPrefixIndex = points.size() - 1;
            double controlPolygonLength = distance(start, control1) + distance(control1, control2)
                    + distance(control2, anchor);
            int steps = Math.max(2, (int)Math.ceil(controlPolygonLength / 16.0D));
            for (int step = 1; step <= steps; step++) {
                double t = step / (double)steps;
                points.add(cubicBezier(start, control1, control2, anchor, t));
            }
            smoothTail(points, fixedPrefixIndex, 8);
            rebuildGeometry(points, points.size() - 1);

            // Nearly parallel approaches can remain inside a wide parent for hundreds of blocks;
            // a coarse route may also cross a meander twice. Retry from the same side of the
            // parent with an explicit clearance waypoint, never by relaxing the audit limit.
            for (int attempt = 0; attempt < 4; attempt++) {
                ParentOverlapStats overlap = parentOverlapStats();
                if (overlap.intervals() == 1
                        && overlap.tailLength() <= naturalConfluenceTailAllowance() + 8.0D) {
                    break;
                }
                rerouteTerminalApproach(anchor, overlap.firstStart(), attempt);
            }
            ParentOverlapStats resolved = parentOverlapStats();
            if (resolved.intervals() > 1) {
                rerouteAlongParentBank(anchor, resolved.firstStart());
            }
        }

        /**
         * Last-resort topology repair for a child whose coarse authored centre-line changes sides
         * across a parent meander. Follow one dry side of the parent until the W05 node, then enter
         * it once; this removes independent cross-river intersections without moving the anchor.
         */
        private void rerouteAlongParentBank(WorldPoint anchor, double firstOverlap) {
            double repairStart = Math.max(0.0D, firstOverlap - 384.0D);
            WorldPoint start = pointAtDistance(repairStart);
            Nearest parentAtStart = terminalRiver.nearestUnbounded(start.x(), start.z());
            Nearest parentAtAnchor = terminalRiver.nearestUnbounded(anchor.x(), anchor.z());
            WorldPoint startParentPoint = terminalRiver.pointAtDistance(
                    parentAtStart.alongDistance());
            WorldPoint startParentDirection = terminalRiver.unitTangentAt(
                    parentAtStart.alongDistance());
            WorldPoint startNormal = new WorldPoint(-startParentDirection.z(),
                    startParentDirection.x());
            double sideProjection = (start.x() - startParentPoint.x()) * startNormal.x()
                    + (start.z() - startParentPoint.z()) * startNormal.z();
            double side = sideProjection < 0.0D ? -1.0D : 1.0D;

            List<WorldPoint> points = prefixPoints(repairStart);
            int fixedPrefixIndex = points.size() - 1;
            double stationDelta = parentAtAnchor.alongDistance() - parentAtStart.alongDistance();
            // Never reproduce P1's multi-kilometre parent-bank follower.  If this repair would
            // exceed the local topology budget, leave the previous approach in place so the audit
            // fails loudly and a W05 terminal override must be supplied.
            if (Math.abs(stationDelta) > MAX_PARENT_BANK_FOLLOWER) return;
            int guideSteps = Math.max(2, (int)Math.ceil(Math.abs(stationDelta) / 96.0D));
            WorldPoint previous = start;
            for (int guide = 0; guide < guideSteps; guide++) {
                double t = guide / (double)guideSteps;
                double station = parentAtStart.alongDistance() + stationDelta * t;
                WorldPoint centre = terminalRiver.pointAtDistance(station);
                WorldPoint direction = terminalRiver.unitTangentAt(station);
                WorldPoint normal = new WorldPoint(-direction.z(), direction.x());
                double progress = clamp(station / terminalRiver.mouthDistance, 0.0D, 1.0D);
                double clearance = terminalRiver.baseWaterWidth(progress) * 0.5D + 64.0D;
                WorldPoint guidePoint = new WorldPoint(centre.x() + normal.x() * side * clearance,
                        centre.z() + normal.z() * side * clearance);
                appendDensifiedLine(points, previous, guidePoint);
                previous = guidePoint;
            }
            WorldPoint approach = normalized(new WorldPoint(anchor.x() - previous.x(),
                    anchor.z() - previous.z()));
            appendBezier(points, previous, approach, anchor, approach, 0.28D, 0.12D);
            smoothTail(points, fixedPrefixIndex, 14);
            rebuildGeometry(points, points.size() - 1);
        }

        private static void smoothTail(List<WorldPoint> points, int fixedPrefixIndex,
                                       int passes) {
            for (int pass = 0; pass < passes; pass++) {
                List<WorldPoint> previous = List.copyOf(points);
                for (int i = fixedPrefixIndex + 1; i < points.size() - 1; i++) {
                    WorldPoint before = previous.get(i - 1);
                    WorldPoint point = previous.get(i);
                    WorldPoint after = previous.get(i + 1);
                    points.set(i, new WorldPoint(
                            (before.x() + point.x() * 2.0D + after.x()) * 0.25D,
                            (before.z() + point.z() * 2.0D + after.z()) * 0.25D));
                }
            }
        }

        private static void appendDensifiedLine(List<WorldPoint> points, WorldPoint start,
                                                WorldPoint end) {
            double length = distance(start, end);
            int steps = Math.max(1, (int)Math.ceil(length / 16.0D));
            for (int step = 1; step <= steps; step++) {
                double t = step / (double)steps;
                points.add(new WorldPoint(lerp(start.x(), end.x(), t),
                        lerp(start.z(), end.z(), t)));
            }
        }

        /**
         * W05 terminal polyline override for authored routes whose coarse endpoint geometry is too
         * different for a local Bezier repair.  Iruma is the permanent regression case: P1's
         * generic bank follower solved the crossing numerically by creating a 5 km parallel river.
         */
        private List<WorldPoint> officialTerminalApproach() {
            // P1.1 terminal-local W05 geometry.  These overrides are intentionally limited to
            // routes which the global topology audit proves cannot be repaired by a short local
            // Bezier without crossing their parent.  Iruma requires the whole official main-stem
            // approach because the former authored line crosses Arakawa far upstream; the others
            // replace only roughly the final 15 km (about 1,900 PJ horizontal blocks).
            return switch (name) {
                case "Iruma" -> List.of(
                        worldPoint(139.1272333D, 35.9244117D),
                        worldPoint(139.1479681D, 35.9111225D),
                        worldPoint(139.1646853D, 35.9006278D),
                        worldPoint(139.1790047D, 35.8890003D),
                        worldPoint(139.1852017D, 35.8688908D),
                        worldPoint(139.2213461D, 35.8611331D),
                        worldPoint(139.2333361D, 35.8695972D),
                        worldPoint(139.2566153D, 35.8659119D),
                        worldPoint(139.2801047D, 35.8691600D),
                        worldPoint(139.3015254D, 35.8566641D),
                        worldPoint(139.3190784D, 35.8440743D),
                        worldPoint(139.3401069D, 35.8335664D),
                        worldPoint(139.3740225D, 35.8492189D),
                        worldPoint(139.4050000D, 35.8620286D),
                        worldPoint(139.4208761D, 35.8927036D),
                        worldPoint(139.4427969D, 35.9112078D),
                        worldPoint(139.4552628D, 35.9301442D),
                        worldPoint(139.4675647D, 35.9501284D),
                        worldPoint(139.4943867D, 35.9561634D),
                        worldPoint(139.5249792D, 35.9512211D),
                        worldPoint(139.5365235D, 35.9324282D),
                        worldPoint(139.5448064D, 35.9101419D),
                        worldPoint(139.5467303D, 35.9087892D));
                case "Sorachi" -> List.of(
                        worldPoint(142.0397255D, 43.5697271D),
                        worldPoint(142.0206168D, 43.5686873D),
                        worldPoint(141.9900405D, 43.5744763D),
                        worldPoint(141.9829497D, 43.5618731D),
                        worldPoint(141.9572101D, 43.5634145D),
                        worldPoint(141.9376860D, 43.5523394D),
                        worldPoint(141.9093636D, 43.5454566D),
                        worldPoint(141.9034543D, 43.5325070D),
                        worldPoint(141.8962657D, 43.5250874D));
                case "Kinu" -> List.of(
                        worldPoint(139.9873025D, 36.0473664D),
                        worldPoint(139.9800469D, 36.0344053D),
                        worldPoint(139.9826764D, 36.0207200D),
                        worldPoint(139.9724478D, 36.0076411D),
                        worldPoint(139.9768411D, 35.9932006D),
                        worldPoint(139.9721422D, 35.9784567D),
                        worldPoint(139.9562353D, 35.9750588D),
                        worldPoint(139.9455436D, 35.9551702D),
                        worldPoint(139.9517338D, 35.9362991D));
                case "Kokai" -> List.of(
                        worldPoint(140.0728197D, 35.9427358D),
                        worldPoint(140.0867642D, 35.9392175D),
                        worldPoint(140.0997478D, 35.9320450D),
                        worldPoint(140.1132539D, 35.9262175D),
                        worldPoint(140.1272861D, 35.9292753D),
                        worldPoint(140.1321010D, 35.9180469D),
                        worldPoint(140.1452190D, 35.9102877D),
                        worldPoint(140.1450846D, 35.8938630D),
                        worldPoint(140.1299019D, 35.8845193D),
                        worldPoint(140.1273529D, 35.8706248D));
                case "Watarase" -> List.of(
                        worldPoint(139.6561839D, 36.2628328D),
                        worldPoint(139.6732003D, 36.2592017D),
                        worldPoint(139.6803306D, 36.2445408D),
                        worldPoint(139.6872853D, 36.2307787D),
                        worldPoint(139.6983576D, 36.2201241D),
                        worldPoint(139.6939576D, 36.2063834D),
                        worldPoint(139.6925904D, 36.1923776D),
                        worldPoint(139.6939845D, 36.1664235D),
                        worldPoint(139.6986776D, 36.1516467D),
                        worldPoint(139.6987553D, 36.1512512D));
                default -> List.of();
            };
        }

        private void spliceOfficialTerminalApproach(List<WorldPoint> officialApproach,
                                                     WorldPoint anchor) {
            WorldPoint firstOfficial = officialApproach.get(0);
            Nearest projection = nearestUnbounded(firstOfficial.x(), firstOfficial.z());
            double repairStart = Math.max(0.0D, projection.alongDistance() - 384.0D);
            WorldPoint start = pointAtDistance(repairStart);
            WorldPoint startDirection = unitTangentAt(repairStart);
            WorldPoint firstDirection = normalized(new WorldPoint(
                    officialApproach.get(1).x() - firstOfficial.x(),
                    officialApproach.get(1).z() - firstOfficial.z()));

            List<WorldPoint> points = prefixPoints(repairStart);
            int fixedPrefixIndex = points.size() - 1;
            appendBezier(points, start, startDirection, firstOfficial, firstDirection,
                    0.28D, 0.20D);
            WorldPoint previous = firstOfficial;
            for (int i = 1; i < officialApproach.size(); i++) {
                WorldPoint next = i == officialApproach.size() - 1 ? anchor
                        : officialApproach.get(i);
                appendDensifiedLine(points, previous, next);
                previous = next;
            }
            smoothTail(points, fixedPrefixIndex, 2);
            // Re-pin the W05 node after smoothing; only interior approach points are allowed to move.
            points.set(points.size() - 1, anchor);
            rebuildGeometry(points, points.size() - 1);
        }

        private static WorldPoint worldPoint(double longitude, double latitude) {
            return new WorldPoint(TerrainData.worldXFromLongitude(longitude),
                    TerrainData.worldZFromLatitude(latitude));
        }

        private void rerouteTerminalApproach(WorldPoint anchor, double firstOverlap,
                                             int attempt) {
            double fallbackStart = Math.max(0.0D, mouthDistance
                    - 1024.0D - attempt * 512.0D);
            double repairStart = Double.isNaN(firstOverlap) ? fallbackStart
                    : Math.max(0.0D, firstOverlap - 384.0D - attempt * 256.0D);
            WorldPoint start = pointAtDistance(repairStart);
            WorldPoint startDirection = unitTangentAt(repairStart);
            Nearest parentAtAnchor = terminalRiver.nearestUnbounded(anchor.x(), anchor.z());
            WorldPoint parentDirection = terminalRiver.unitTangentAt(parentAtAnchor.alongDistance());
            WorldPoint parentNormal = new WorldPoint(-parentDirection.z(), parentDirection.x());
            double sideProjection = (start.x() - anchor.x()) * parentNormal.x()
                    + (start.z() - anchor.z()) * parentNormal.z();
            double side = sideProjection < 0.0D ? -1.0D : 1.0D;
            double parentProgress = clamp(parentAtAnchor.alongDistance()
                    / terminalRiver.mouthDistance, 0.0D, 1.0D);
            double clearance = Math.max(192.0D,
                    (mouthWidth + terminalRiver.waterWidth(parentProgress)) * 0.75D + 64.0D);
            clearance *= 1.0D + attempt * 0.55D;
            double upstreamOffset = Math.min(384.0D, Math.max(128.0D, clearance * 0.75D));
            WorldPoint waypoint = new WorldPoint(
                    anchor.x() - parentDirection.x() * upstreamOffset
                            + parentNormal.x() * side * clearance,
                    anchor.z() - parentDirection.z() * upstreamOffset
                            + parentNormal.z() * side * clearance);

            List<WorldPoint> points = prefixPoints(repairStart);
            appendBezier(points, start, startDirection, waypoint,
                    normalized(new WorldPoint(waypoint.x() - start.x(),
                            waypoint.z() - start.z())), 0.30D, 0.20D);
            WorldPoint approach = normalized(new WorldPoint(anchor.x() - waypoint.x(),
                    anchor.z() - waypoint.z()));
            appendBezier(points, waypoint, approach, anchor, approach, 0.28D, 0.16D);
            rebuildGeometry(points, points.size() - 1);
        }

        private static void appendBezier(List<WorldPoint> points, WorldPoint start,
                                         WorldPoint startDirection, WorldPoint end,
                                         WorldPoint endDirection, double startFraction,
                                         double endFraction) {
            double chord = Math.max(1.0D, distance(start, end));
            WorldPoint control1 = new WorldPoint(
                    start.x() + startDirection.x() * chord * startFraction,
                    start.z() + startDirection.z() * chord * startFraction);
            WorldPoint control2 = new WorldPoint(
                    end.x() - endDirection.x() * chord * endFraction,
                    end.z() - endDirection.z() * chord * endFraction);
            double polygonLength = distance(start, control1) + distance(control1, control2)
                    + distance(control2, end);
            int steps = Math.max(2, (int)Math.ceil(polygonLength / 16.0D));
            for (int step = 1; step <= steps; step++) {
                points.add(cubicBezier(start, control1, control2, end,
                        step / (double)steps));
            }
        }

        private List<AuthoredRiverGeometryRefresher.Point> authoredRefreshScaffold(double spacing) {
            int samples = Math.max(2, Math.min(2400, (int)Math.ceil(mouthDistance / spacing)));
            List<AuthoredRiverGeometryRefresher.Point> result = new ArrayList<>(samples + 1);
            for (int i = 0; i <= samples; i++) {
                WorldPoint p = pointAtDistance(mouthDistance * i / (double)samples);
                result.add(new AuthoredRiverGeometryRefresher.Point(p.x(), p.z()));
            }
            return List.copyOf(result);
        }

        private void replaceAuthoredRefreshRoute(List<AuthoredRiverGeometryRefresher.Point> candidate) {
            replaceAuthoredRefreshRoute(candidate, false);
        }

        private void replaceAuthoredRefreshRoute(List<AuthoredRiverGeometryRefresher.Point> candidate,
                                                  boolean relocatedSource) {
            if (candidate.size() < 2) return;
            List<WorldPoint> raw = new ArrayList<>(candidate.size());
            for (AuthoredRiverGeometryRefresher.Point p : candidate) raw.add(new WorldPoint(p.x(), p.z()));
            // Match the ordinary authored constructor's visual continuity guarantees. AR candidate
            // points are a topology scaffold, not final 16-block segments; without this pass the
            // point-cloud correction can leave 48-block elbows which DropScheduler correctly
            // rejects on a wide river. Endpoint guards inside smoothRoute keep source/mouth fixed.
            List<WorldPoint> points = densifyRoute(smoothAuthoredRefreshRoute(raw, relocatedSource));
            int mouthIndex = points.size() - 1;
            if (reachesSea) {
                WorldPoint oldOffshoreEnd = pointAtDistance(totalLength);
                WorldPoint mouth = points.get(mouthIndex);
                if (distance(mouth, oldOffshoreEnd) >= 1.0D) points.add(oldOffshoreEnd);
                else points.add(new WorldPoint(mouth.x() + 1.0D, mouth.z()));
            }
            rebuildGeometry(points, mouthIndex);
            this.sourceNaturalY = naturalSurfaceAt(pointAtDistance(0.0D));
        }

        private double longestStraightRunBlocks() {
            double longest = 0.0D, run = 0.0D;
            Segment previous = null;
            for (Segment segment : segments) {
                if (segment.startDistance() >= mouthDistance) break;
                if (previous == null) {
                    run = segment.length();
                } else {
                    double dot = (previous.dx() * segment.dx() + previous.dz() * segment.dz())
                            / Math.max(1.0E-9D, previous.length() * segment.length());
                    double turn = Math.toDegrees(Math.acos(clamp(dot, -1.0D, 1.0D)));
                    run = turn < 2.0D ? run + segment.length() : segment.length();
                }
                longest = Math.max(longest, run);
                previous = segment;
            }
            return longest;
        }

        private double maximumSegmentLengthToMouth() {
            double maximum = 0.0D;
            for (Segment segment : segments) {
                if (segment.startDistance() >= mouthDistance) break;
                maximum = Math.max(maximum, segment.length());
            }
            return maximum;
        }

        private List<Double> geometryStations() {
            List<Double> stations = new ArrayList<>(segments.size() + 1);
            stations.add(0.0D);
            for (Segment segment : segments) {
                stations.add(segment.startDistance() + segment.length());
            }
            return stations;
        }

        private List<WorldPoint> prefixPoints(double endDistance) {
            List<WorldPoint> result = new ArrayList<>();
            result.add(pointAtDistance(0.0D));
            for (Segment segment : segments) {
                double segmentEnd = segment.startDistance() + segment.length();
                if (segmentEnd >= endDistance - 0.001D) break;
                result.add(segment.end());
            }
            WorldPoint end = pointAtDistance(endDistance);
            if (distance(result.get(result.size() - 1), end) >= 0.01D) result.add(end);
            return result;
        }

        private void rebuildGeometry(List<WorldPoint> points, int mouthPointIndex) {
            List<Segment> rebuilt = new ArrayList<>(Math.max(1, points.size() - 1));
            double cumulative = 0.0D;
            double rebuiltMouthDistance = Double.NaN;
            double localMinX = Double.POSITIVE_INFINITY;
            double localMaxX = Double.NEGATIVE_INFINITY;
            double localMinZ = Double.POSITIVE_INFINITY;
            double localMaxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < points.size() - 1; i++) {
                WorldPoint start = points.get(i);
                WorldPoint end = points.get(i + 1);
                double length = distance(start, end);
                if (length >= 1.0D) {
                    int steps = Math.max(1, (int)Math.ceil(length / 16.0D));
                    WorldPoint previous = start;
                    for (int step = 1; step <= steps; step++) {
                        double t = step / (double)steps;
                        WorldPoint next = new WorldPoint(lerp(start.x(), end.x(), t),
                                lerp(start.z(), end.z(), t));
                        double partLength = distance(previous, next);
                        if (partLength >= 1.0D) {
                            rebuilt.add(new Segment(previous, next, cumulative, partLength));
                            cumulative += partLength;
                            localMinX = Math.min(localMinX,
                                    Math.min(previous.x(), next.x()));
                            localMaxX = Math.max(localMaxX,
                                    Math.max(previous.x(), next.x()));
                            localMinZ = Math.min(localMinZ,
                                    Math.min(previous.z(), next.z()));
                            localMaxZ = Math.max(localMaxZ,
                                    Math.max(previous.z(), next.z()));
                        }
                        previous = next;
                    }
                }
                if (i + 1 == mouthPointIndex) rebuiltMouthDistance = cumulative;
            }
            if (rebuilt.isEmpty() || Double.isNaN(rebuiltMouthDistance)) {
                throw new IllegalStateException("Invalid rebuilt confluence geometry for " + name);
            }
            this.segments = List.copyOf(rebuilt);
            this.segmentGrid = buildSegmentGrid(this.segments);
            this.totalLength = Math.max(1.0D, cumulative);
            this.mouthDistance = Math.max(1.0D, rebuiltMouthDistance);
            double expansion = Math.max(sourceWidth, mouthWidth) * 0.60D
                    + RIVER_BANK_MAX_MARGIN + 128.0D;
            this.minX = localMinX - expansion;
            this.maxX = localMaxX + expansion;
            this.minZ = localMinZ - expansion;
            this.maxZ = localMaxZ + expansion;
            this.segmentCellCache.remove();
            this.waterProfile = null;
            this.longitudinalProfile = null;
        }

        private double firstParentCorridorOverlap() {
            return parentOverlapStats().firstStart();
        }

        private ParentOverlapStats parentOverlapStats() {
            int intervals = 0;
            boolean previousOverlap = false;
            double firstStart = Double.NaN;
            double finalStart = Double.NaN;
            int samples = Math.max(1, (int)Math.ceil(mouthDistance / 8.0D));
            for (int sample = 0; sample <= samples; sample++) {
                double station = Math.min(mouthDistance, sample * 8.0D);
                WorldPoint point = pointAtDistance(station);
                Nearest parent = terminalRiver.nearest((int)Math.round(point.x()),
                        (int)Math.round(point.z()));
                boolean overlap = false;
                if (parent != null) {
                    double parentProgress = clamp(parent.alongDistance()
                            / terminalRiver.mouthDistance, 0.0D, 1.0D);
                    double radius = terminalRiver.waterWidth(parentProgress) * 0.5D
                            + terminalRiver.waterRasterMargin(parentProgress);
                    overlap = parent.distance() <= radius;
                }
                if (overlap && !previousOverlap) {
                    intervals++;
                    finalStart = station;
                    if (Double.isNaN(firstStart)) firstStart = station;
                }
                previousOverlap = overlap;
            }
            double tail = Double.isNaN(finalStart) ? Double.POSITIVE_INFINITY
                    : mouthDistance - finalStart;
            return new ParentOverlapStats(intervals, firstStart, tail);
        }

        private double naturalConfluenceTailAllowance() {
            Nearest parent = terminalRiver.nearestUnbounded(
                    confluenceAnchor.officialPoint().x(), confluenceAnchor.officialPoint().z());
            double progress = clamp(parent.alongDistance() / terminalRiver.mouthDistance,
                    0.0D, 1.0D);
            return Math.max(128.0D, (mouthWidth + terminalRiver.waterWidth(progress)) * 0.60D);
        }

        private record ParentOverlapStats(int intervals, double firstStart,
                                          double tailLength) {}

        private WorldPoint unitTangentAt(double station) {
            double reach = 32.0D;
            WorldPoint before = pointAtDistance(Math.max(0.0D, station - reach));
            WorldPoint after = pointAtDistance(Math.min(totalLength, station + reach));
            return normalized(new WorldPoint(after.x() - before.x(), after.z() - before.z()));
        }

        private static WorldPoint normalized(WorldPoint vector) {
            double length = Math.hypot(vector.x(), vector.z());
            if (length < 1.0e-9D) return new WorldPoint(1.0D, 0.0D);
            return new WorldPoint(vector.x() / length, vector.z() / length);
        }

        private static WorldPoint cubicBezier(WorldPoint p0, WorldPoint p1,
                                               WorldPoint p2, WorldPoint p3, double t) {
            double u = 1.0D - t;
            double a = u * u * u;
            double b = 3.0D * u * u * t;
            double c = 3.0D * u * t * t;
            double d = t * t * t;
            return new WorldPoint(a * p0.x() + b * p1.x() + c * p2.x() + d * p3.x(),
                    a * p0.z() + b * p1.z() + c * p2.z() + d * p3.z());
        }

        private static double distance(WorldPoint first, WorldPoint second) {
            return Math.hypot(first.x() - second.x(), first.z() - second.z());
        }

        private void initializeWaterProfile() {
            if (waterProfile != null) return;
            if (profileInitializing) {
                throw new IllegalStateException("Cyclic Project Japan river connection at " + name);
            }

            profileInitializing = true;
            try {
                if (sourceRiver != null) {
                    sourceRiver.initializeWaterProfile();
                    if (Double.isNaN(bifurcationParentStation)) {
                        throw new IllegalStateException("Unresolved bifurcation anchor for " + name);
                    }
                    sourceWaterY = sourceRiver.waterSurfaceY(bifurcationParentStation);
                }
                if (terminalRiver != null) {
                    terminalRiver.initializeWaterProfile();
                    if (Double.isNaN(confluenceParentStation)) {
                        throw new IllegalStateException("Unresolved confluence anchor for " + name);
                    }
                    terminalWaterY = terminalRiver.waterSurfaceY(confluenceParentStation);
                }
                waterProfile = buildWaterProfile();
            } finally {
                profileInitializing = false;
            }
        }

        /**
         * Move an existing main-stem descent upstream before a tributary reaches the parent water
         * mask. This rewrites the solved floating grade and
         * then runs DropScheduler again, so the relocated one-block rapids are part of
         * {@code waterProfile}. That is important for falling-water geometry: runtime-only
         * overlays are invisible to {@code distanceToNextRapid()} and can place two visual
         * connectors on top of each other.
         *
         * <p>The source and terminal anchors are unchanged. The total elevation loss is unchanged;
         * only its location inside the requested upstream approach is redistributed. The final
         * {@code flatLength} reaches the junction terrace exactly, while the preceding part fades
         * smoothly back to the original longitudinal profile.</p>
         */
        private void refitUpstreamApproachToJunctionLevel(WorldPoint junction, int sharedTargetY,
                                                           double flatLength, double totalLength) {
            if (waterProfile == null || longitudinalProfile == null) {
                throw new IllegalStateException("Water profile must be initialized before approach refit: "
                        + name);
            }
            Nearest nearest = nearest((int)Math.round(junction.x()),
                    (int)Math.round(junction.z()));
            if (nearest == null) return;

            double joinDistance = clamp(nearest.alongDistance(), 0.0D, mouthDistance);
            int localJunctionY = rawWaterSurfaceY(joinDistance);
            // The RiverGraph parent owns the shared hydraulic target. A convergence pass may only
            // lower the selected approach; it can never manufacture a higher local target.
            int junctionY = Math.min(localJunctionY, sharedTargetY);
            double hold = Math.max(0.0D, flatLength);
            double reach = Math.max(hold + 1.0D, totalLength);
            double[] refit = longitudinalProfile.clone();

            for (int i = 0; i < refit.length; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                double upstream = joinDistance - distance;
                if (upstream < 0.0D || upstream > reach) continue;
                double influence = upstream <= hold
                        ? 1.0D
                        : 1.0D - smoothstep(hold, reach, upstream);
                double lowered = lerp(refit[i], junctionY, influence);
                refit[i] = Math.min(refit[i], lowered);
            }

            // Lowering may only move a fall upstream. Preserve the global no-reverse-flow
            // invariant and leave every downstream value at or below the junction terrace.
            for (int i = 1; i < refit.length; i++) {
                refit[i] = Math.min(refit[i - 1], refit[i]);
            }
            refit[refit.length - 1] = longitudinalProfile[longitudinalProfile.length - 1];

            int coarseCount = refit.length;
            int[] valleySurfaces = new int[coarseCount];
            double[] widths = new double[coarseCount];
            for (int i = 0; i < coarseCount; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                double progress = clamp(distance / mouthDistance, 0.0D, 1.0D);
                widths[i] = waterWidth(progress);
                valleySurfaces[i] = naturalSurfaceAt(pointAtDistance(distance));
            }

            longitudinalProfile = refit;
            waterProfile = scheduleRapidProfile(longitudinalProfile, valleySurfaces, widths);
        }


        private void registerFinalLakeConnection(LakeOwner owner, double firstStation, double lastStation) {
            if (!owner.present() || owner.reason().startsWith("ILLEGAL")) return;
            LakeData.LakeMetadata lake = owner.lakeId().isEmpty()
                    ? lakeMetadataByName(owner.lakeName()) : LakeData.metadataById(owner.lakeId());
            if (lake == null) return;
            double first = clamp(Math.min(firstStation, lastStation), 0.0D, mouthDistance);
            double last = clamp(Math.max(firstStation, lastStation), 0.0D, mouthDistance);
            FinalLakeContactInterval interval = new FinalLakeContactInterval(lake.lakeId(), lake.name(),
                    owner.reason(), first, last, lake.waterSurfaceY());
            finalLakeConnections.add(new FinalLakeConnection(lake.lakeId(), lake.name(), owner.reason(),
                    first, last, lake.waterSurfaceY(), List.of(interval)));
        }

        /**
         * CF-01 final contact rebuild keeps the LR-FINAL raster literal, but CF-04 needs a stable
         * hydraulic reach.  Merge only adjacent fragments of the same physical lake when the dry
         * centre-line gap is within the river-width-scaled 128..192 block hydraulic seam. A
         * different lake owner always terminates the merge, and no shoreline/route vertex is moved.
         */
        private void mergeFragmentedFinalLakeConnections() {
            if (finalLakeConnections.size() < 2) return;
            List<FinalLakeConnection> sorted = new ArrayList<>(finalLakeConnections);
            sorted.sort(Comparator.comparingDouble(FinalLakeConnection::firstStation));
            List<FinalLakeConnection> merged = new ArrayList<>(sorted.size());
            FinalLakeConnection current = sorted.get(0);
            for (int i = 1; i < sorted.size(); i++) {
                FinalLakeConnection next = sorted.get(i);
                double gap = Math.max(0.0D, next.firstStation - current.lastStation);
                boolean samePhysicalLake = current.lakeId.equals(next.lakeId)
                        && current.lakeName.equals(next.lakeName)
                        && current.lakeY == next.lakeY;
                double gapProgress = clamp((current.lastStation + next.firstStation) * 0.5D
                        / Math.max(1.0D, mouthDistance), 0.0D, 1.0D);
                double fragmentGapLimit = clamp(CF_SAME_LAKE_FRAGMENT_GAP
                        + waterWidth(gapProgress) * 0.75D, CF_SAME_LAKE_FRAGMENT_GAP, 192.0D);
                if (samePhysicalLake && gap <= fragmentGapLimit) {
                    String reason = current.reason.equals(next.reason)
                            ? current.reason : "DECLARED_CHANNEL";
                    List<FinalLakeContactInterval> physical = new ArrayList<>(current.physicalIntervals);
                    physical.addAll(next.physicalIntervals);
                    physical = deglitchPhysicalLakeIntervals(physical);
                    current = new FinalLakeConnection(current.lakeId, current.lakeName, reason,
                            current.firstStation, Math.max(current.lastStation, next.lastStation),
                            current.lakeY, physical);
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
            finalLakeConnections.clear();
            finalLakeConnections.addAll(merged);
        }

        /**
         * Fix4 shoreline-only raster chatter suppression.  Do not use the 128..192 block hydraulic
         * merge threshold here: a 20+ block dry gap is a real exit/re-entry and must keep both gates.
         */
        private static List<FinalLakeContactInterval> deglitchPhysicalLakeIntervals(
                List<FinalLakeContactInterval> input) {
            if (input.size() < 2) return List.copyOf(input);
            List<FinalLakeContactInterval> sorted = new ArrayList<>(input);
            sorted.sort(Comparator.comparingDouble(FinalLakeContactInterval::firstStation));
            List<FinalLakeContactInterval> result = new ArrayList<>(sorted.size());
            FinalLakeContactInterval current = sorted.get(0);
            for (int i = 1; i < sorted.size(); i++) {
                FinalLakeContactInterval next = sorted.get(i);
                double gap = Math.max(0.0D, next.firstStation - current.lastStation);
                boolean sameLake = current.lakeId.equals(next.lakeId)
                        && current.lakeName.equals(next.lakeName) && current.lakeY == next.lakeY;
                if (sameLake && gap <= CF_SHORELINE_GATE_DEGLITCH_GAP) {
                    String reason = current.reason.equals(next.reason)
                            ? current.reason : "DECLARED_CHANNEL";
                    current = new FinalLakeContactInterval(current.lakeId, current.lakeName, reason,
                            current.firstStation, Math.max(current.lastStation, next.lastStation),
                            current.lakeY);
                } else {
                    result.add(current);
                    current = next;
                }
            }
            result.add(current);
            return List.copyOf(result);
        }

        private ConnectionInfo lakeConnection(double alongDistance) {
            // Final physical water-mask reaches own both the bathymetry blend and the much shorter
            // shoreline aperture.  These weights are intentionally separate: bathymetry only acts
            // inside lake.water(), while aperture exists in lake.corridor() && !lake.water().
            for (FinalLakeConnection gate : finalLakeConnections) {
                double progress = clamp((gate.firstStation + gate.lastStation) * 0.5D
                        / mouthDistance, 0.0D, 1.0D);
                double width = waterWidth(progress);
                double blendExtent = clamp(64.0D + width * 1.25D, 64.0D, 256.0D);
                double apron = Math.min(48.0D, Math.max(12.0D, width));
                double apertureExtent = clamp(16.0D + width * 0.20D, 16.0D, 48.0D);
                if (alongDistance < gate.firstStation - Math.max(apron, apertureExtent)
                        || alongDistance > gate.lastStation + Math.max(apron, apertureExtent)) continue;

                boolean sourceGate = gate.lakeName.equals(sourceLakeName)
                        && (gate.reason.equals("SOURCE_FROM_LAKE")
                        || gate.reason.equals("DECLARED_OUTLET"));
                boolean terminalGate = gate.lakeName.equals(terminalLakeName)
                        || gate.reason.equals("TERMINAL_INTO_LAKE")
                        || gate.reason.equals("DECLARED_INLET");
                boolean channelGate = gate.reason.equals("DECLARED_CHANNEL")
                        || (!sourceGate && !terminalGate);

                double influence;
                if (sourceGate) {
                    double start = gate.firstStation;
                    double end = Math.min(gate.lastStation, start + blendExtent);
                    influence = smoothstep(start, Math.max(start + 1.0D, end), alongDistance);
                } else if (terminalGate) {
                    double start = gate.firstStation;
                    double end = Math.min(gate.lastStation, start + blendExtent);
                    influence = 1.0D - smoothstep(start, Math.max(start + 1.0D, end), alongDistance);
                } else {
                    // A declared channel must keep the river thalweg continuous *through* each
                    // shoreline gate, then let it disappear into the open lake interior.  The old
                    // rule did the inverse (0 at both shores, 1 in the lake middle), which made the
                    // bed snap to lake.bedY exactly where the shoreline aperture was meant to open.
                    // Treat the first shore like a terminal inlet and the last shore like a source
                    // outlet; if their blend extents overlap, the deeper of the two channel weights
                    // wins without changing the lake polygon.
                    double firstEnd = Math.min(gate.lastStation, gate.firstStation + blendExtent);
                    double firstInfluence = 1.0D - smoothstep(gate.firstStation,
                            Math.max(gate.firstStation + 1.0D, firstEnd), alongDistance);
                    double lastStart = Math.max(gate.firstStation, gate.lastStation - blendExtent);
                    double lastInfluence = smoothstep(lastStart,
                            Math.max(lastStart + 1.0D, gate.lastStation), alongDistance);
                    influence = Math.max(firstInfluence, lastInfluence);
                }
                if (alongDistance < gate.firstStation) {
                    influence *= smoothstep(gate.firstStation - apron, gate.firstStation, alongDistance);
                } else if (alongDistance > gate.lastStation) {
                    influence *= 1.0D - smoothstep(gate.lastStation,
                            gate.lastStation + apron, alongDistance);
                }

                // Fix4: shoreline topology is literal, not hydraulic.  Every preserved physical
                // interval contributes its own legal entry/exit aperture; the 128..192 block
                // hydraulic merge is deliberately invisible here.  This prevents a same-lake
                // exit/re-entry pair (e.g. Sagami/Tsukui) from losing its middle shoreline gates.
                double aperture = 0.0D;
                for (FinalLakeContactInterval interval : gate.physicalIntervals) {
                    boolean intervalSourceGate = interval.lakeName.equals(sourceLakeName)
                            && (interval.reason.equals("SOURCE_FROM_LAKE")
                            || interval.reason.equals("DECLARED_OUTLET"));
                    boolean intervalTerminalGate = interval.lakeName.equals(terminalLakeName)
                            || interval.reason.equals("TERMINAL_INTO_LAKE")
                            || interval.reason.equals("DECLARED_INLET");
                    boolean intervalChannelGate = interval.reason.equals("DECLARED_CHANNEL")
                            || (!intervalSourceGate && !intervalTerminalGate);
                    if (intervalSourceGate && interval.lastStation < mouthDistance - 1.0D) {
                        aperture = Math.max(aperture, 1.0D - smoothstep(0.0D, apertureExtent,
                                Math.abs(alongDistance - interval.lastStation)));
                    } else if (intervalTerminalGate && interval.firstStation > 1.0D) {
                        aperture = Math.max(aperture, 1.0D - smoothstep(0.0D, apertureExtent,
                                Math.abs(alongDistance - interval.firstStation)));
                    }
                    if (intervalChannelGate) {
                        if (interval.firstStation > 1.0D) {
                            aperture = Math.max(aperture, 1.0D - smoothstep(0.0D, apertureExtent,
                                    Math.abs(alongDistance - interval.firstStation)));
                        }
                        if (interval.lastStation < mouthDistance - 1.0D
                                && Math.abs(interval.lastStation - interval.firstStation) > 1.0D) {
                            aperture = Math.max(aperture, 1.0D - smoothstep(0.0D, apertureExtent,
                                    Math.abs(alongDistance - interval.lastStation)));
                        }
                    }
                }
                return new ConnectionInfo(true, gate.lakeName, gate.lakeY,
                        clamp(influence, 0.0D, 1.0D), clamp(aperture, 0.0D, 1.0D));
            }

            // Hydraulic fallback outside the exact final water mask. It shapes Y only and is not
            // allowed to punch an unverified shoreline aperture.
            if (!sourceLakeName.isEmpty() && alongDistance <= sourceConnectionLength) {
                return new ConnectionInfo(true, sourceLakeName,
                        LakeData.waterSurfaceY(sourceLakeName), 0.0D, 0.0D);
            }
            double remaining = mouthDistance - alongDistance;
            if (!terminalLakeName.isEmpty() && remaining <= terminalConnectionLength) {
                return new ConnectionInfo(true, terminalLakeName,
                        LakeData.waterSurfaceY(terminalLakeName), 0.0D, 0.0D);
            }
            return ConnectionInfo.NONE;
        }

        private ConfluenceInfo confluenceInfo(int worldX, int worldZ,
                                               double alongDistance, int childDepth) {
            if (terminalRiver == null) return ConfluenceInfo.NONE;
            double remaining = mouthDistance - alongDistance;
            if (remaining < 0.0D || remaining > CONFLUENCE_LENGTH) return ConfluenceInfo.NONE;
            Nearest parentNearest = terminalRiver.nearest(worldX, worldZ);
            if (parentNearest == null) return ConfluenceInfo.NONE;
            double parentProgress = clamp(parentNearest.alongDistance()
                    / terminalRiver.mouthDistance, 0.0D, 1.0D);
            double parentWidth = terminalRiver.waterWidth(parentProgress);
            if (parentNearest.distance() > parentWidth * 0.5D + 48.0D) return ConfluenceInfo.NONE;

            // P1.2: connection height comes from exactly the same W05 anchor station that built
            // terminalWaterY.  parentNearest remains useful for local geometry only; its Y must
            // never select an adjacent one-block terrace and raise the child at runtime.
            int sharedY = terminalWaterY;
            int childY = waterSurfaceY(alongDistance);
            if (childY < sharedY) {
                throw new IllegalStateException("Child profile below terminal connection level: "
                        + name + " raw=" + childY + " connection=" + sharedY);
            }
            if (terminalHydraulicApproachRefit) {
                // The final approach grade already reaches the exact anchor level while matching
                // the parent's first-contact terrace. Re-applying the old anchor-level blend here
                // would flatten that repaired grade back to terminalWaterY 100+ blocks too early.
                return new ConfluenceInfo(true, childY, Math.max(1, childDepth), 1.0D);
            }
            double childInfluence = smoothstep(0.0D, CONFLUENCE_LENGTH, remaining);
            return new ConfluenceInfo(true, sharedY, Math.max(1, childDepth), childInfluence);
        }

        private double bankSlope(double progress, double width, int naturalSurface,
                                 int actualWaterY) {
            double incision = Math.max(0.0D, naturalSurface - actualWaterY);
            double p = clamp(progress, 0.0D, 1.0D);
            double wide = smoothstep(40.0D, 120.0D, width);
            double deep = smoothstep(RIVER_CARVE_BLEND_START, 48.0D, incision);

            // ValleyCarver slope targets are expressed as vertical rise per horizontal block.
            // Headwaters are allowed around 1:1.6..1:2, mid reaches 1:2..1:3, and mature lower
            // rivers are gentler still. Deep cuts never get steeper merely because they are deep.
            double reachSlope = lerp(0.58D, 0.30D, smoothstep(0.08D, 0.82D, p));
            reachSlope -= wide * 0.035D;
            reachSlope = Math.min(reachSlope, lerp(0.56D, 0.42D, deep));
            return clamp(reachSlope, RIVER_BANK_MIN_SLOPE, RIVER_BANK_MAX_SLOPE);
        }

        private double maximumBankSupport(double progress, double width, int naturalSurface,
                                          int actualWaterY, double slope) {
            double heightDifference = Math.abs(naturalSurface - actualWaterY);
            double incision = Math.max(0.0D, naturalSurface - actualWaterY);
            double p = clamp(progress, 0.0D, 1.0D);
            double bench = clamp(3.0D + width * 0.08D, 3.0D, 14.0D);
            double base = RIVER_BANK_MIN_MARGIN + Math.min(64.0D, width * 0.40D);

            // "Cut deeper => carve a wider valley".  The geometric climb-to-DEM distance is the
            // minimum; an additional depth-dependent relaxation reserve prevents the corridor from
            // ending in a cliff. Lower reaches also receive a little more floodplain room.
            double geometric = bench + heightDifference / Math.max(0.20D, slope);
            double relaxation = RIVER_BANK_BLEND_EXTRA
                    + incision * lerp(1.6D, 2.6D, smoothstep(8.0D, 64.0D, incision))
                    + smoothstep(0.45D, 1.0D, p) * Math.min(48.0D, width * 0.22D);
            double normal = clamp(Math.max(base, geometric + relaxation),
                    base, RIVER_BANK_MAX_MARGIN);

            // Shallow source grooves may keep a compact untouched shoulder. As soon as an actual
            // mountain cut starts, this guard falls to zero and the full valley carver owns it.
            double sourceGuard = headwaterBankGuard(progress * mouthDistance, heightDifference);
            if (sourceGuard <= 0.0D) return normal;
            return lerp(normal, HEADWATER_BANK_MARGIN, sourceGuard);
        }

        private double headwaterBankGuard(double alongDistance, double heightDifference) {
            double source = headwaterInfluence(alongDistance);
            if (source <= 0.0D) return 0.0D;
            // Protect only a genuinely shallow source groove. Any visible mountain incision must
            // immediately hand control to the full shoreline blend, otherwise the old 3-block
            // cut guard itself becomes a vertical wall beside the water.
            double compatible = 1.0D - smoothstep(2.0D, 5.0D, heightDifference);
            return source * compatible;
        }

        private double headwaterInfluence(double alongDistance) {
            if (!sourceLakeName.isEmpty() || headwaterProfileLength <= 0.0D) return 0.0D;
            double guardLength = Math.min(headwaterProfileLength, HEADWATER_VISUAL_GUARD_LENGTH);
            if (alongDistance >= guardLength) return 0.0D;
            double fadeStart = guardLength * 0.72D;
            return 1.0D - smoothstep(fadeStart, guardLength, alongDistance);
        }

        private static OffshoreExtension findOffshoreExtension(
                WorldPoint mouth, double tangentUnitX, double tangentUnitZ) {
            OffshoreExtension best = null;

            // Prefer a continuation broadly aligned with the authored final river segment, but
            // allow the route to turn into the actual bay. Several coarse control lines end while
            // still pointing along the shoreline; blindly extending that tangent can remain over
            // land for kilometres and create an artificial canal.
            for (int angle = -100; angle <= 100; angle += 5) {
                OffshoreExtension candidate = traceOffshore(
                        mouth, tangentUnitX, tangentUnitZ, angle);
                if (candidate != null && (best == null || candidate.score() < best.score())) {
                    best = candidate;
                }
            }

            // Defensive fallback for a badly placed hand-authored endpoint. This should rarely be
            // needed, but guarantees that the finite segment never ends inside a shallow shelf.
            if (best == null) {
                for (int angle = -180; angle < 180; angle += 10) {
                    OffshoreExtension candidate = traceOffshore(
                            mouth, tangentUnitX, tangentUnitZ, angle);
                    if (candidate != null && (best == null || candidate.score() < best.score())) {
                        best = candidate;
                    }
                }
            }

            if (best != null) return best;
            return new OffshoreExtension(tangentUnitX, tangentUnitZ,
                    MAX_ESTUARY_EXTENSION, Double.POSITIVE_INFINITY);
        }

        private static OffshoreExtension traceOffshore(
                WorldPoint mouth, double tangentUnitX, double tangentUnitZ, int angleDegrees) {
            double angle = Math.toRadians(angleDegrees);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double unitX = tangentUnitX * cos - tangentUnitZ * sin;
            double unitZ = tangentUnitX * sin + tangentUnitZ * cos;

            int consecutiveDeep = 0;
            int landSamples = 0;
            int shallowSamples = 0;

            for (double distance = ESTUARY_EXTENSION_STEP;
                 distance <= MAX_ESTUARY_EXTENSION;
                 distance += ESTUARY_EXTENSION_STEP) {
                WorldPoint point = new WorldPoint(
                        mouth.x() + unitX * distance,
                        mouth.z() + unitZ * distance);
                TerrainData.TerrainSample terrain = TerrainData.sampleWorld(
                        (int)Math.round(point.x()), (int)Math.round(point.z()));

                if (terrain.land()) landSamples++;
                else if (!isNaturallyDeepOcean(terrain)) shallowSamples++;

                if (distance >= MIN_ESTUARY_EXTENSION && isNaturallyDeepOcean(terrain)) {
                    consecutiveDeep++;
                    if (consecutiveDeep >= REQUIRED_DEEP_OCEAN_SAMPLES) {
                        // The endpoint is the last sample in a long deep-ocean run. Since the
                        // natural seabed is already deeper than the estuary opening here, the
                        // rounded nearest-segment cap cannot leave any visible underwater arc.
                        double anglePenalty = Math.abs(angleDegrees) * 7.0D;
                        double terrainPenalty = landSamples * 220.0D
                                + shallowSamples * 8.0D;
                        double score = distance + anglePenalty + terrainPenalty;
                        return new OffshoreExtension(unitX, unitZ, distance, score);
                    }
                } else {
                    consecutiveDeep = 0;
                }
            }
            return null;
        }

        private static boolean isNaturallyDeepOcean(TerrainData.TerrainSample terrain) {
            if (terrain.land()) return false;
            double coast = smoothstep(0.02D, 0.50D, terrain.landCoverage());
            int depth = 2 + (int)Math.round((64.0D - 2.0D) * (1.0D - coast));
            return depth >= 60;
        }

        private static Map<Long, List<Segment>> buildSegmentGrid(List<Segment> segments) {
            Map<Long, List<Segment>> mutable = new HashMap<>();
            // Fine river segments are <=24 blocks long. Storing each one by midpoint keeps the
            // index compact; nearest() searches enough neighbouring 1024-block cells to cover the
            // full bank-support radius plus more than one segment length.
            for (Segment segment : segments) {
                double midX = (segment.start().x() + segment.end().x()) * 0.5D;
                double midZ = (segment.start().z() + segment.end().z()) * 0.5D;
                long key = gridKey(gridCoord(midX, SEGMENT_GRID_SIZE),
                        gridCoord(midZ, SEGMENT_GRID_SIZE));
                mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(segment);
            }
            Map<Long, List<Segment>> result = new HashMap<>(mutable.size());
            mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Map.copyOf(result);
        }

        private boolean mayContain(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        private Nearest nearest(int x, int z) {
            double bestDistanceSq = Double.POSITIVE_INFINITY;
            double bestAlong = 0.0D;
            boolean found = false;
            int centreCellX = gridCoord(x, SEGMENT_GRID_SIZE);
            int centreCellZ = gridCoord(z, SEGMENT_GRID_SIZE);
            int radiusCells = (int)Math.ceil((segmentQueryRadius + 32.0D)
                    / SEGMENT_GRID_SIZE) + 1;

            for (Segment segment : nearbySegments(centreCellX, centreCellZ, radiusCells)) {
                double t = ((x - segment.start().x()) * segment.dx()
                        + (z - segment.start().z()) * segment.dz())
                        * segment.inverseLengthSq();
                t = clamp(t, 0.0D, 1.0D);
                double nearestX = segment.start().x() + segment.dx() * t;
                double nearestZ = segment.start().z() + segment.dz() * t;
                double dx = x - nearestX;
                double dz = z - nearestZ;
                double distanceSq = dx * dx + dz * dz;
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    bestAlong = segment.startDistance() + segment.length() * t;
                    found = true;
                }
            }
            if (!found || bestDistanceSq > segmentQueryRadius * segmentQueryRadius) return null;
            return new Nearest(Math.sqrt(bestDistanceSq), bestAlong);
        }

        /** Initialization/audit query which is intentionally not limited by the runtime radius. */
        private Nearest nearestUnbounded(double x, double z) {
            double bestDistanceSq = Double.POSITIVE_INFINITY;
            double bestAlong = 0.0D;
            for (Segment segment : segments) {
                double t = ((x - segment.start().x()) * segment.dx()
                        + (z - segment.start().z()) * segment.dz())
                        * segment.inverseLengthSq();
                t = clamp(t, 0.0D, 1.0D);
                double nearestX = segment.start().x() + segment.dx() * t;
                double nearestZ = segment.start().z() + segment.dz() * t;
                double dx = x - nearestX;
                double dz = z - nearestZ;
                double distanceSq = dx * dx + dz * dz;
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    bestAlong = segment.startDistance() + segment.length() * t;
                }
            }
            return new Nearest(Math.sqrt(bestDistanceSq), bestAlong);
        }

        private List<Segment> nearbySegments(int centreCellX, int centreCellZ,
                                             int radiusCells) {
            SegmentCellCache cache = segmentCellCache.get();
            if (cache.cellX == centreCellX && cache.cellZ == centreCellZ
                    && cache.radiusCells == radiusCells) {
                return cache.segments;
            }

            List<Segment> merged = new ArrayList<>();
            for (int cellX = centreCellX - radiusCells;
                 cellX <= centreCellX + radiusCells; cellX++) {
                for (int cellZ = centreCellZ - radiusCells;
                     cellZ <= centreCellZ + radiusCells; cellZ++) {
                    List<Segment> nearby = segmentGrid.get(gridKey(cellX, cellZ));
                    if (nearby != null) merged.addAll(nearby);
                }
            }
            cache.cellX = centreCellX;
            cache.cellZ = centreCellZ;
            cache.radiusCells = radiusCells;
            cache.segments = List.copyOf(merged);
            return cache.segments;
        }

        private static final class SegmentCellCache {
            private int cellX = Integer.MIN_VALUE;
            private int cellZ = Integer.MIN_VALUE;
            private int radiusCells = -1;
            private List<Segment> segments = List.of();
        }

        private double baseWaterWidth(double progress) {
            // Preserve narrow headwaters and spend most of the authored widening in the middle
            // and lower reaches. The old exponent (< 1) widened rivers too early, producing broad
            // artificial channels high in mountain valleys.
            double p = smoothstep(0.0D, 1.0D, progress);
            double growth = Math.pow(p, 1.12D);
            double estuary = reachesSea ? smoothstep(0.80D, 1.0D, progress) : 0.0D;
            double effectiveMouthWidth = sourceWidth
                    + (mouthWidth - sourceWidth) * hydrologyClass.wideningFactor();
            double width = sourceWidth + (effectiveMouthWidth - sourceWidth) * growth;
            width *= 1.0D + estuary * 0.15D;

            // A 5-8 block authored width is useful once a mountain stream has formed, but across
            // a cliff it can span tens of vertical blocks from one bank to the other.  The good
            // tellus-overlay screenshots effectively avoided that by clipping each water column to
            // the local DEM.  Keep a single flat hydraulic surface instead and make only the first
            // source reach genuinely stream-sized.
            double sourceGuard = headwaterInfluence(progress * mouthDistance);
            if (sourceGuard > 0.0D) {
                double alpineWidth = Math.min(sourceWidth, 3.0D);
                width = lerp(width, alpineWidth, sourceGuard);
            }
            return width;
        }

        private double waterWidth(double progress) {
            double width = baseWaterWidth(progress);
            if (terminalRiver == null || Double.isNaN(confluenceParentStation)) return width;

            // P1.1 confluence-width continuity.  A tributary mouth wider than the receiving main
            // stem creates a round lake-like pool even when both centre-lines are topologically
            // perfect.  Only the final 512 blocks are tapered; the authored upstream width remains
            // unchanged.  Parent width is read from its base profile to avoid recursive widening.
            double alongDistance = clamp(progress, 0.0D, 1.0D) * mouthDistance;
            double remaining = mouthDistance - alongDistance;
            if (remaining > TERMINAL_WIDTH_BLEND_LENGTH) return width;
            double parentProgress = clamp(confluenceParentStation / terminalRiver.mouthDistance,
                    0.0D, 1.0D);
            double parentWidth = terminalRiver.baseWaterWidth(parentProgress);
            double joinTarget = width > parentWidth
                    ? parentWidth * terminalWidthTargetRatio()
                    : width;
            double terminalInfluence = 1.0D - smoothstep(0.0D,
                    TERMINAL_WIDTH_BLEND_LENGTH, remaining);
            return lerp(width, joinTarget, terminalInfluence);
        }

        /**
         * Local tributary-mouth hierarchy only. This never widens a child and never changes the
         * authored upstream reach; it only caps the final terminal approach when a lower-priority
         * tributary would otherwise become wider than its receiving river.
         */
        private double terminalWidthTargetRatio() {
            if (terminalRiver == null) return 1.0D;
            return switch (hydrologyClass) {
                case P2 -> terminalRiver.hydrologyClass == HydrologyClass.P2 ? 0.90D : 0.60D;
                case P1 -> terminalRiver.hydrologyClass == HydrologyClass.P0 ? 0.80D : 0.90D;
                case P0 -> 1.00D;
            };
        }

        private void initializeJunctionBankTransition() {
            junctionBankTransition = null;
            // J-09 freeze: every authored terminalRiver now uses the same tested shoreline-contact
            // ownership rule that was previously staged only on Kizu/Yodo. Each child bank tapers
            // to its first real parent-water contact; after the centre has entered parent water the
            // receiving corridor owns all exterior water, preventing tongue/fan overshoot.
            if (terminalRiver == null || confluenceAnchor == null) return;
            double leftContact = RiverData.firstChildBankContact(this, +1.0D);
            double rightContact = RiverData.firstChildBankContact(this, -1.0D);
            if (Double.isNaN(leftContact) || Double.isNaN(rightContact)) return;
            JunctionBankCurve left = buildJunctionBankCurve(+1.0D, leftContact);
            JunctionBankCurve right = buildJunctionBankCurve(-1.0D, rightContact);
            junctionBankTransition = new JunctionBankTransition(left, right);
        }

        private JunctionBankCurve buildJunctionBankCurve(double side, double contactStation) {
            double blendStart = Math.max(0.0D, contactStation - JUNCTION_BANK_BLEND_LENGTH);
            WorldPoint start = bankPointAt(blendStart, side);
            WorldPoint target = bankPointAt(contactStation, side);
            WorldPoint startDirection = unitTangentAt(blendStart);
            Nearest parentNearest = terminalRiver.nearestUnbounded(target.x(), target.z());
            WorldPoint parentDirection = terminalRiver.unitTangentAt(parentNearest.alongDistance());
            WorldPoint chord = normalized(new WorldPoint(target.x() - start.x(),
                    target.z() - start.z()));
            if (parentDirection.x() * chord.x() + parentDirection.z() * chord.z() < 0.0D) {
                parentDirection = new WorldPoint(-parentDirection.x(), -parentDirection.z());
            }
            double length = Math.max(1.0D, distance(start, target));
            WorldPoint control1 = new WorldPoint(
                    start.x() + startDirection.x() * length * 0.34D,
                    start.z() + startDirection.z() * length * 0.34D);
            WorldPoint control2 = new WorldPoint(
                    target.x() - parentDirection.x() * length * 0.30D,
                    target.z() - parentDirection.z() * length * 0.30D);
            return new JunctionBankCurve(side, blendStart, contactStation, start, control1,
                    control2, target);
        }

        private WorldPoint bankPointAt(double station, double side) {
            WorldPoint centre = pointAtDistance(station);
            WorldPoint tangent = unitTangentAt(station);
            double progress = clamp(station / mouthDistance, 0.0D, 1.0D);
            double half = waterWidth(progress) * 0.5D + waterRasterMargin(progress);
            return new WorldPoint(centre.x() - tangent.z() * side * half,
                    centre.z() + tangent.x() * side * half);
        }

        private boolean allowsJunctionBankTransition(int worldX, int worldZ,
                                                      double extraMargin) {
            if (junctionBankTransition == null) return true;
            Nearest local = nearest(worldX, worldZ);
            if (local == null) return true;
            double station = local.alongDistance();
            WorldPoint centre = pointAtDistance(station);
            WorldPoint tangent = unitTangentAt(station);
            double normalX = -tangent.z();
            double normalZ = tangent.x();
            double signed = (worldX - centre.x()) * normalX
                    + (worldZ - centre.z()) * normalZ;
            double side = signed >= 0.0D ? +1.0D : -1.0D;
            double sideDistance = Math.abs(signed);
            JunctionBankCurve curve = side > 0.0D
                    ? junctionBankTransition.left() : junctionBankTransition.right();
            if (station < curve.blendStartStation()) return true;

            double progress = clamp(station / mouthDistance, 0.0D, 1.0D);
            double naturalHalf = waterWidth(progress) * 0.5D + waterRasterMargin(progress);
            if (station <= curve.contactStation()) {
                double t = clamp((station - curve.blendStartStation())
                        / Math.max(1.0D, curve.contactStation() - curve.blendStartStation()),
                        0.0D, 1.0D);
                WorldPoint bank = cubicBezier(curve.start(), curve.control1(), curve.control2(),
                        curve.target(), t);
                double bankSigned = (bank.x() - centre.x()) * normalX
                        + (bank.z() - centre.z()) * normalZ;
                double allowed = Math.min(naturalHalf, Math.max(0.0D, bankSigned * side));
                return sideDistance <= allowed + extraMargin
                        || RiverData.parentWaterAt(terminalRiver, worldX, worldZ);
            }

            // Once this bank has actually met the receiving shoreline, the child no longer owns
            // exterior water on that side. If the child centre is still outside the parent (the
            // near bank at an oblique junction), retain only the strip up to the first shoreline
            // entry. Once the centre is inside, parent water/corridor owns the entire exterior.
            if (RiverData.parentWaterAt(terminalRiver, worldX, worldZ)) return true;
            boolean centreInside = RiverData.parentWaterAtPoint(terminalRiver,
                    centre.x(), centre.z());
            if (centreInside) return false;
            double entry = RiverData.firstParentEntryDistanceAlongChildNormal(
                    this, station, side);
            if (Double.isNaN(entry)) return sideDistance <= naturalHalf + extraMargin;
            return sideDistance <= entry + Math.min(extraMargin, 8.0D);
        }

        private boolean allowsFiniteCorridorMask(int worldX, int worldZ, double bankMargin) {
            return allowsJunctionBankTransition(worldX, worldZ, bankMargin);
        }

        /**
         * Finite river segments normally rasterise with round caps because nearest() clamps its
         * segment projection.  That is desirable for ordinary endpoints, but a topology anchor is
         * not a free endpoint: the connected parent owns the water beyond the node.  Clip only the
         * small anchor neighbourhood with a tangent half-plane, leaving the rest of the route and
         * its natural meanders untouched.
         */
        private boolean allowsFiniteWaterMask(int worldX, int worldZ) {
            if (terminalRiver != null && confluenceAnchor != null) {
                WorldPoint anchor = confluenceAnchor.officialPoint();
                double dx = worldX - anchor.x();
                double dz = worldZ - anchor.z();
                if (dx * dx + dz * dz <= 256.0D * 256.0D) {
                    WorldPoint tangent = unitTangentAt(Math.max(0.0D, mouthDistance - 16.0D));
                    if (dx * tangent.x() + dz * tangent.z() > END_CAP_ALLOWANCE) return false;
                }
            }
            if (sourceRiver != null && bifurcationAnchor != null) {
                WorldPoint anchor = bifurcationAnchor.officialPoint();
                double dx = worldX - anchor.x();
                double dz = worldZ - anchor.z();
                if (dx * dx + dz * dz <= 256.0D * 256.0D) {
                    WorldPoint tangent = unitTangentAt(Math.min(16.0D, mouthDistance));
                    if (dx * tangent.x() + dz * tangent.z() < -END_CAP_ALLOWANCE) return false;
                }
            }
            return allowsJunctionBankTransition(worldX, worldZ, 0.0D);
        }

        private double waterRasterMargin(double progress) {
            double sourceGuard = headwaterInfluence(progress * mouthDistance);
            return lerp(WATER_RASTER_MARGIN, 0.5D, sourceGuard);
        }

        private int rawWaterSurfaceY(double alongDistance) {
            if (alongDistance >= mouthDistance) return terminalWaterY;
            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            int index = Math.min(waterProfile.length - 1,
                    Math.max(0, (int)Math.floor(distance / RAPID_SPACING)));
            return waterProfile[index];
        }

        private boolean continuousHeadwaterGrade(double alongDistance) {
            return headwaterProfileLength > 0.0D
                    && alongDistance >= 0.0D
                    && alongDistance < Math.min(headwaterProfileLength, mouthDistance);
        }

        /**
         * High mountain headwaters must not reproduce a 6-20 block profile sample drop as one
         * vertical water curtain.  The hydraulic samples stay monotonic and unchanged, but inside
         * the solved headwater prefix their loss is distributed across the 16-block sample cell.
         * Ceil is intentional for a descending interpolation: the first column retains the upper
         * terrace and every following change is at most one block, so the river becomes a stepped
         * mountain cascade instead of a dam-height cliff.  This interpolation never consults DEM
         * and therefore cannot introduce the old tellus-overlay reverse-flow failure.
         */
        private int hydraulicSurfaceY(double distance) {
            // DropScheduler already quantises the entire floating profile on a 1-block longitudinal grid.
            // Do not add a second headwater-specific interpolation layer: that was another source
            // of periodic staircase geometry and made bank/drop detection disagree with water Y.
            return rawWaterSurfaceY(distance);
        }

        private int waterSurfaceY(double alongDistance) {
            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            return hydraulicSurfaceY(distance);
        }

        /**
         * Banks follow a continuous interpolation of the hydraulic profile. Water remains on
         * integer terraces, but copying those discrete levels across the full bank corridor made
         * every narrow waterfall continue sideways as a long vertical dirt or stone wall.
         */
        private double bankReferenceWaterLevel(double alongDistance) {
            double progress = clamp(alongDistance / mouthDistance, 0.0D, 1.0D);
            double width = waterWidth(progress);
            // The former +/-256 block window smeared one mountain terrace hundreds of blocks
            // along both banks. Narrow reaches now use a compact reference while wide lowland
            // rivers retain enough smoothing to avoid stair-stepped embankments.
            double radius = clamp(48.0D + width * 1.35D, 64.0D, 192.0D);
            double half = radius * 0.5D;
            double weighted =
                    longitudinalProfileY(alongDistance - radius)
                    + longitudinalProfileY(alongDistance - half) * 2.0D
                    + longitudinalProfileY(alongDistance) * 3.0D
                    + longitudinalProfileY(alongDistance + half) * 2.0D
                    + longitudinalProfileY(alongDistance + radius);
            return weighted / 9.0D;
        }

        /**
         * Continuous grade used only by dry-bank terrain shaping.  The visible regression in the
         * high headwaters was not the cross-valley slope itself: each discrete hydraulic terrace
         * was being copied laterally through the full shoreline corridor, producing long exposed
         * dirt/stone walls perpendicular to the river.  This low-pass grade smooths those terrace
         * transitions longitudinally while keeping the actual water profile untouched.
         */
        private double bankTerrainReferenceWaterLevel(int worldX, int worldZ,
                                                       double localCentreDistance,
                                                       double alongDistance,
                                                       int naturalSurface,
                                                       int actualWaterY) {
            /*
             * This is a CONTINUOUS HYDRAULIC reference, not a terrain/crest height. The former
             * implementation averaged an upstream-biased bank grade and could return 20-200
             * blocks above actual water. riverBankSurface then accidentally treated that value as
             * the valley floor. Use the solved floating H(s) directly and only spatially low-pass
             * nearby reaches of the same course to remove hairpin/Voronoi seams.
             */
            double localReference = longitudinalProfileY(alongDistance);
            double incision = Math.max(0.0D, naturalSurface - actualWaterY);
            if (incision < RIVER_CARVE_BLEND_START) return localReference;

            double fieldRadius = clamp(36.0D + incision * 0.18D, 44.0D, 112.0D);
            double maximumDistance = localCentreDistance + fieldRadius;
            int centreCellX = gridCoord(worldX, SEGMENT_GRID_SIZE);
            int centreCellZ = gridCoord(worldZ, SEGMENT_GRID_SIZE);
            int radiusCells = (int)Math.ceil((maximumDistance + 32.0D)
                    / SEGMENT_GRID_SIZE) + 1;

            double weighted = localReference * 1.5D;
            double totalWeight = 1.5D;
            double sigma = Math.max(20.0D, fieldRadius * 0.48D);
            double sigmaSq = sigma * sigma;
            for (Segment segment : nearbySegments(centreCellX, centreCellZ, radiusCells)) {
                double t = ((worldX - segment.start().x()) * segment.dx()
                        + (worldZ - segment.start().z()) * segment.dz())
                        * segment.inverseLengthSq();
                t = clamp(t, 0.0D, 1.0D);
                double nearestX = segment.start().x() + segment.dx() * t;
                double nearestZ = segment.start().z() + segment.dz() * t;
                double dx = worldX - nearestX;
                double dz = worldZ - nearestZ;
                double distance = Math.hypot(dx, dz);
                if (distance > maximumDistance) continue;
                double excess = Math.max(0.0D, distance - localCentreDistance);
                double taper = 1.0D - smoothstep(fieldRadius * 0.55D, fieldRadius, excess);
                if (taper <= 1.0E-6D) continue;
                double gaussianLike = 1.0D / (1.0D + excess * excess / sigmaSq);
                double weight = taper * gaussianLike * Math.max(1.0D, segment.length());
                double candidateAlong = segment.startDistance() + segment.length() * t;
                weighted += longitudinalProfileY(candidateAlong) * weight;
                totalWeight += weight;
            }
            return weighted / totalWeight;
        }

        /** Longitudinal component of the dry-bank reference, before the 2-D spatial envelope. */
        private double longitudinalBankTerrainReferenceWaterLevel(double alongDistance,
                                                                   int naturalSurface,
                                                                   int actualWaterY) {
            double progress = clamp(alongDistance / mouthDistance, 0.0D, 1.0D);
            double width = waterWidth(progress);
            double incision = Math.max(0.0D, naturalSurface - actualWaterY);
            double source = headwaterInfluence(alongDistance);
            double carve = smoothstep(RIVER_CARVE_BLEND_START, RIVER_DEEP_CARVE_FULL,
                    incision);

            // Ordinary reaches need only modest longitudinal filtering. Deep/high source cuts use
            // a much longer upstream window so a hydraulic terrace becomes a continuous valley
            // grade rather than a matching wall in both banks.
            double radius = clamp(80.0D + width * 1.45D
                            + source * 240.0D + carve * 220.0D,
                    96.0D, 560.0D);
            double q = radius * 0.25D;
            double h = radius * 0.50D;
            double tq = radius * 0.75D;

            double weighted =
                    longitudinalProfileY(alongDistance) * 5.0D
                    + longitudinalProfileY(alongDistance - q) * 4.0D
                    + longitudinalProfileY(alongDistance - h) * 3.0D
                    + longitudinalProfileY(alongDistance - tq) * 2.0D
                    + longitudinalProfileY(alongDistance - radius);
            double smoothed = weighted / 15.0D;
            return Math.max(actualWaterY, smoothed);
        }

        private double interpolatedWaterSurfaceY(double alongDistance) {
            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            if (distance >= mouthDistance) return terminalWaterY;
            double position = distance / RAPID_SPACING;
            int index = Math.min(waterProfile.length - 2,
                    Math.max(0, (int)Math.floor(position)));
            double fraction = position - index;
            int first = waterSurfaceY(index * RAPID_SPACING);
            int second = waterSurfaceY(Math.min(mouthDistance,
                    (index + 1) * RAPID_SPACING));
            return lerp(first, second, fraction);
        }

        /** Floating whole-river grade used by ValleyCarver / ShorelineRelaxer only. */
        private double longitudinalProfileY(double alongDistance) {
            if (longitudinalProfile == null || longitudinalProfile.length == 0) {
                return interpolatedWaterSurfaceY(alongDistance);
            }
            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            if (distance >= mouthDistance) return longitudinalProfile[longitudinalProfile.length - 1];
            double position = distance / PROFILE_SPACING;
            int index = Math.min(longitudinalProfile.length - 2,
                    Math.max(0, (int)Math.floor(position)));
            double fraction = position - index;
            return lerp(longitudinalProfile[index], longitudinalProfile[index + 1], fraction);
        }

        private DropInfo dropInfo(double alongDistance) {
            if (alongDistance <= 0.0D || alongDistance >= mouthDistance) {
                int y = waterSurfaceY(alongDistance);
                return new DropInfo(false, y, y, 0.0D);
            }

            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            // RAPID_SPACING is now one block, while a visual rapid intentionally extends several
            // blocks downstream.  The old "cell / cell+1" lookup could therefore see a fall only
            // for roughly one block and silently truncated every 5-9 block connector. Search the
            // whole physical influence window instead.
            int firstBoundary = Math.max(1, (int)Math.floor(
                    (distance - DROP_DOWNSTREAM_LENGTH) / RAPID_SPACING));
            int lastBoundary = Math.min(waterProfile.length - 1, (int)Math.ceil(
                    (distance + DROP_UPSTREAM_LENGTH) / RAPID_SPACING));

            DropInfo bestPast = null;
            double bestPastDelta = Double.POSITIVE_INFINITY;
            DropInfo bestFuture = null;
            double bestFutureAbs = Double.POSITIVE_INFINITY;
            for (int boundaryIndex = firstBoundary; boundaryIndex <= lastBoundary;
                 boundaryIndex++) {
                double boundaryDistance = boundaryIndex * RAPID_SPACING;
                if (boundaryDistance >= mouthDistance) continue;
                int upper = waterSurfaceY((boundaryIndex - 1) * RAPID_SPACING);
                int lower = waterSurfaceY(boundaryDistance);
                if (upper <= lower) continue;

                double delta = distance - boundaryDistance;
                if (delta < -DROP_UPSTREAM_LENGTH || delta > DROP_DOWNSTREAM_LENGTH) continue;
                DropInfo candidate = new DropInfo(true, upper, lower, delta);
                if (delta >= 0.0D) {
                    // Prefer the closest upstream boundary. This keeps an isolated rapid active
                    // through its complete downstream transition instead of being shadowed by a
                    // not-yet-reached future drop.
                    if (delta < bestPastDelta) {
                        bestPastDelta = delta;
                        bestPast = candidate;
                    }
                } else {
                    double abs = -delta;
                    if (abs < bestFutureAbs) {
                        bestFutureAbs = abs;
                        bestFuture = candidate;
                    }
                }
            }

            if (bestPast != null) return bestPast;
            if (bestFuture != null) return bestFuture;
            int y = waterSurfaceY(distance);
            return new DropInfo(false, y, y, 0.0D);
        }



        private boolean headwaterSurfaceStep(double alongDistance) {
            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            if (!continuousHeadwaterGrade(distance)) return false;
            int centre = waterSurfaceY(distance);
            double reach = 1.5D;
            int before = waterSurfaceY(Math.max(0.0D, distance - reach));
            int after = waterSurfaceY(Math.min(headwaterProfileLength, distance + reach));
            return centre != before || centre != after;
        }

        /**
         * Keep the actual falling-water curtain narrow while retaining the wider symmetric drop
         * lookup used by bank containment. The former fixed 14-block downstream connector turned
         * every 16-block terrace into a broad water sheet; several neighbouring sheets dominated
         * wide, sharply curved channels such as the Yodo outlet. A small height-aware thickness is
         * still wide enough for diagonals and centre-line curvature without visually flooding the
         * whole reach.
         */
        private double fallingConnectorLength(DropInfo drop, double width, double sampleDistance) {
            if (!drop.active()) return 0.0D;
            int dropHeight = Math.max(1, drop.upperWaterY() - drop.lowerWaterY());
            double base = dropHeight == 1
                    ? clamp(9.0D - width * 0.035D,
                    MIN_FALLING_CONNECTOR_LENGTH, MAX_FALLING_CONNECTOR_LENGTH)
                    : clamp(5.0D + dropHeight * 0.75D,
                    MIN_FALLING_CONNECTOR_LENGTH, MAX_FALLING_CONNECTOR_LENGTH);

            double boundary = sampleDistance - drop.offsetFromBoundary();
            double nextSpacing = distanceToNextRapid(boundary);
            if (Double.isFinite(nextSpacing)) {
                // Dense steep reaches may legitimately need one drop every four blocks. Shorten
                // the visual rapid there rather than letting neighbouring falling-water connectors
                // overlap into a broad artificial sheet.
                base = Math.min(base, Math.max(0.25D, nextSpacing - 0.25D));
            }
            return base;
        }

        private double distanceToNextRapid(double boundaryDistance) {
            int current = Math.max(1, (int)Math.round(boundaryDistance / RAPID_SPACING));
            for (int i = current + 1; i < waterProfile.length; i++) {
                if (waterProfile[i - 1] > waterProfile[i]) {
                    return i * RAPID_SPACING - boundaryDistance;
                }
            }
            return Double.POSITIVE_INFINITY;
        }

        private double turnAngleAt(double alongDistance, double width) {
            double reach = clamp(32.0D + width * 0.75D, 48.0D, 192.0D);
            WorldPoint before = pointAtDistance(Math.max(0.0D, alongDistance - reach));
            WorldPoint centre = pointAtDistance(alongDistance);
            WorldPoint after = pointAtDistance(Math.min(mouthDistance,
                    alongDistance + reach));
            double incomingX = centre.x() - before.x();
            double incomingZ = centre.z() - before.z();
            double outgoingX = after.x() - centre.x();
            double outgoingZ = after.z() - centre.z();
            double denominator = Math.hypot(incomingX, incomingZ)
                    * Math.hypot(outgoingX, outgoingZ);
            if (denominator < 1.0D) return 0.0D;
            double cosine = clamp((incomingX * outgoingX + incomingZ * outgoingZ)
                    / denominator, -1.0D, 1.0D);
            return Math.toDegrees(Math.acos(cosine));
        }

        // Ordinary bank terrain is no longer keyed to rapid/drop/headwater-step height.
        // See RiverBankTerrain NaturalBankPreservation + OverflowGuard.

        private int[] buildWaterProfile() {
            int count = Math.max(2, (int)Math.ceil(mouthDistance / PROFILE_SPACING) + 1);
            int[] desired = new int[count];
            int[] valleySurfaces = new int[count];
            double[] widths = new double[count];
            int[] profile = new int[count];

            for (int i = 0; i < count; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                double progress = distance / mouthDistance;
                double width = waterWidth(progress);
                int naturalSurface = valleySurfaceAt(distance, width);
                widths[i] = width;
                valleySurfaces[i] = naturalSurface;

                double incisionMetres = clamp(
                        4.0D + width * 0.04D + progress * 4.0D,
                        4.0D, 16.0D);
                int incision = Math.max(1, (int)Math.round(
                        incisionMetres / TerrainData.VERTICAL_METRES_PER_BLOCK));
                int terrainPreferred = naturalSurface - incision;

                // Retain the old long-profile curve only as a bounded hint. Older PJ versions
                // treated it as a hard cap, so one inaccurate source elevation could pull a river
                // tens of blocks below its valley for kilometres. The hint may now lower the
                // terrain-derived surface only within a small altitude/width-aware cut budget.
                int longitudinalHint = terminalWaterY + (int)Math.round(
                        (sourceWaterY - terminalWaterY) * Math.pow(1.0D - progress, 3.0D));
                int terrainCutLimit = profileTerrainCutLimit(progress, width, naturalSurface);
                int lowestTerrainCompatible = naturalSurface - terrainCutLimit;
                int hinted = Math.min(terrainPreferred, longitudinalHint);
                desired[i] = Math.max(terminalWaterY,
                        Math.max(lowestTerrainCompatible, hinted));

                if (!sourceLakeName.isEmpty() && distance <= sourceConnectionLength) {
                    double influence = smoothstep(sourceLakeGateHoldLength,
                            sourceConnectionLength, distance);
                    int localFloor = sourceLakeLocalTargetY == Integer.MIN_VALUE
                            ? desired[i] : Math.max(sourceLakeLocalTargetY, desired[i]);
                    desired[i] = Math.max(desired[i], (int)Math.round(
                            lerp(sourceWaterY, localFloor, influence)));
                }
                double remaining = mouthDistance - distance;
                if (!terminalLakeName.isEmpty() && remaining <= terminalConnectionLength) {
                    double influence = smoothstep(0.0D, terminalConnectionLength, remaining);
                    desired[i] = Math.max(terminalWaterY, (int)Math.round(
                            lerp(terminalWaterY, desired[i], influence)));
                }
                if (terminalRiver != null && remaining <= CONFLUENCE_LENGTH) {
                    double influence = smoothstep(CONFLUENCE_LEVEL_HOLD,
                            CONFLUENCE_LENGTH, remaining);
                    desired[i] = Math.max(terminalWaterY, (int)Math.round(
                            lerp(terminalWaterY, desired[i], influence)));
                }
            }

            // Once water has passed a genuine low point it must never climb back up a DEM ridge.
            // Build a monotonic ceiling first. If the authored route later crosses higher terrain,
            // the river may cut that ridge, but the adaptive shoreline corridor below turns that
            // cut into a valley instead of a near-vertical trench.
            int[] monotonicCeiling = new int[count];
            monotonicCeiling[0] = desired[0];
            for (int i = 1; i < count; i++) {
                monotonicCeiling[i] = Math.min(monotonicCeiling[i - 1], desired[i]);
            }
            monotonicCeiling[count - 1] = terminalWaterY;

            profile[profile.length - 1] = terminalWaterY;
            for (int i = profile.length - 2; i >= 0; i--) {
                int dropCap = terrainAwareDropCap(i + 1, desired, valleySurfaces, widths);
                int highestAllowed = profile[i + 1] + dropCap;
                profile[i] = Math.min(monotonicCeiling[i], highestAllowed);
                profile[i] = Math.max(profile[i + 1], profile[i]);
            }
            if (!sourceLakeName.isEmpty()) {
                // The source lake remains an explicit hydraulic lower envelope. This exception is
                // connection metadata, not a DEM clamp, and therefore cannot create reverse flow.
                for (int i = 0; i < profile.length; i++) {
                    double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                    if (distance > sourceConnectionLength) break;
                    profile[i] = Math.max(profile[i], desired[i]);
                }
                profile[0] = sourceWaterY;
            } else if (sourceRiver != null) {
                // A distributary starts on the parent's exact hydraulic surface.  Only the source
                // sample is pinned; the unchanged monotonic solver controls everything downstream.
                profile[0] = sourceWaterY;
            }
            for (int i = 1; i < profile.length; i++) {
                profile[i] = Math.min(profile[i - 1], profile[i]);
                int dropCap = terrainAwareDropCap(i, desired, valleySurfaces, widths);
                int lowestAllowed = profile[i - 1] - dropCap;
                if (profile[i] < lowestAllowed) {
                    if (lowestAllowed > desired[i] + SOURCE_GRADE_HARD_LIMIT_BLOCKS) {
                        throw new IllegalStateException("River " + name
                                + " has no contained source-lake grade at sample " + i
                                + ": required=" + lowestAllowed
                                + ", valleyCeiling=" + desired[i]);
                    }
                    profile[i] = lowestAllowed;
                }
            }
            profile[profile.length - 1] = terminalWaterY;
            if (!sourceLakeName.isEmpty() && profile[0] != sourceWaterY) {
                throw new IllegalStateException("River " + name + " cannot leave source lake "
                        + sourceLakeName + " continuously: profile=" + profile[0]
                        + ", lake=" + sourceWaterY + ", desired0=" + desired[0]
                        + ", ceiling0=" + monotonicCeiling[0]
                        + ", next=" + profile[Math.min(1, profile.length - 1)]
                        + ", connection=" + sourceConnectionLength
                        + ", length=" + mouthDistance
                        + ", samples=" + profile.length);
            }
            applyHighHeadwaterProfile(profile);
            this.longitudinalProfile = solveFloatingLongitudinalProfile(profile);
            this.longitudinalProfile = refitTerminalHydraulicApproach(this.longitudinalProfile);
            this.longitudinalProfile = refitFinalLakeGateProfiles(this.longitudinalProfile);
            return scheduleRapidProfile(this.longitudinalProfile, valleySurfaces, widths);
        }


        /**
         * CF-04 gate-aware profile refit for legal retained/authored lake crossings discovered from
         * final masks.  The lake is an immutable hydraulic anchor.  We lower a too-high river toward
         * that anchor and then re-apply monotonicity; a river already below the lake is never raised.
         * The rendered overlap itself is owned by the lake in PJChunkGenerator, so the exact shared
         * contact column always uses lake Y while this profile keeps both approaches non-rising.
         * Unlike CF-03 river-river reconciliation, a physically traversed fixed lake may raise an
         * undershooting provisional river profile exactly to lake Y; that adjustment is confined
         * to the gate approach and is then propagated monotonically upstream.
         */
        private double[] refitFinalLakeGateProfiles(double[] grade) {
            if (finalLakeConnections.isEmpty() || grade.length < 2) return grade;
            double[] refit = grade.clone();
            List<FinalLakeConnection> gates = new ArrayList<>(finalLakeConnections);
            gates.sort(Comparator.comparingDouble(FinalLakeConnection::firstStation));

            // A downstream retained lake may not sit above an upstream retained lake on the same
            // directed RiverCourse.  If it did, no profile-only CF repair could satisfy both fixed
            // lake anchors without reverse flow.
            int previousLakeY = Integer.MAX_VALUE;
            double previousStation = -1.0D;
            for (FinalLakeConnection gate : gates) {
                if (gate.firstStation >= previousStation && gate.lakeY > previousLakeY) {
                    throw new IllegalStateException("CF-04 non-monotonic fixed lake anchors on " + name
                            + ": downstream " + gate.lakeName + " Y=" + gate.lakeY
                            + " above upstream Y=" + previousLakeY);
                }
                previousLakeY = gate.lakeY;
                previousStation = gate.firstStation;
            }

            final int transitionSamples = Math.max(4, (int)Math.ceil(256.0D / PROFILE_SPACING));
            for (FinalLakeConnection gate : gates) {
                int first = Math.max(0, Math.min(refit.length - 1,
                        (int)Math.floor(gate.firstStation / PROFILE_SPACING)));
                int last = Math.max(first, Math.min(refit.length - 1,
                        (int)Math.ceil(gate.lastStation / PROFILE_SPACING)));
                int upstream = Math.max(0, first - transitionSamples);
                int downstream = Math.min(refit.length - 1, last + transitionSamples);

                // Fixed final lake Y is allowed to raise a too-low provisional river approach:
                // this is not a generic junction preference, but the physical requirement for a
                // RiverCourse that actually traverses an immutable lake surface.  The backward
                // monotonic pass below propagates only as far upstream as required, so no local
                // downstream rise is introduced.
                double upstreamBase = Math.max(refit[upstream], gate.lakeY);
                boolean lowerIntoLake = refit[first] >= gate.lakeY;
                for (int i = upstream; i <= first; i++) {
                    double t = (i - upstream) / (double)Math.max(1, first - upstream);
                    int target = (int)Math.round(lerp(upstreamBase, gate.lakeY,
                            smoothstep(0.0D, 1.0D, t)));
                    // CF-04 must not lift an already-higher approach merely because the smooth
                    // interpolation is flatter than the frozen pre-CF profile.  That exact leak
                    // raised Oi by 2-3 blocks hundreds of blocks before Hatanagi and exposed a
                    // six-block dry-bank seam.  A high approach is a lowering-only operation;
                    // only a genuinely undershooting approach may be raised to an immutable lake.
                    refit[i] = lowerIntoLake ? Math.min(refit[i], target)
                            : Math.max(refit[i], target);
                }
                for (int i = first; i <= last; i++) refit[i] = gate.lakeY;

                double downstreamBase = Math.min(refit[downstream], gate.lakeY);
                for (int i = last; i <= downstream; i++) {
                    double t = (i - last) / (double)Math.max(1, downstream - last);
                    int target = (int)Math.round(lerp(gate.lakeY, downstreamBase,
                            smoothstep(0.0D, 1.0D, t)));
                    refit[i] = target;
                }
            }

            /*
             * A middle-course lake is a hard hydraulic ceiling for every downstream sample until
             * the ordinary grade naturally falls below it.  Without this pass a profile such as
             * Oi/Hatanagi could enter the fixed lake at Y=239 and then recover to Y=253 after the
             * shoreline, which the renderer exposes as a 14-block downstream rise.  Clamp only
             * downward; lower terrain/river values are preserved.
             */
            for (FinalLakeConnection gate : gates) {
                int last = Math.max(0, Math.min(refit.length - 1,
                        (int)Math.ceil(gate.lastStation / PROFILE_SPACING)));
                for (int i = last + 1; i < refit.length; i++) {
                    refit[i] = Math.min(refit[i], gate.lakeY);
                }
            }

            // With downstream recoveries removed, a fixed lake that lies above the provisional
            // upstream grade may safely propagate its required level upstream.  Because lake
            // anchors were already checked to be non-increasing, this cannot raise an earlier
            // fixed lake above its own Y.
            for (int i = refit.length - 2; i >= 0; i--) {
                refit[i] = Math.max(refit[i], refit[i + 1]);
            }
            if (!sourceLakeName.isEmpty() && refit[0] != sourceWaterY) {
                throw new IllegalStateException("CF-04 lake anchor would move source-lake Y on " + name
                        + ": source=" + sourceWaterY + " refit=" + refit[0]);
            }
            if (refit[refit.length - 1] != grade[grade.length - 1]) {
                throw new IllegalStateException("CF-04 lake anchor would move terminal Y on " + name
                        + ": terminal=" + grade[grade.length - 1]
                        + " refit=" + refit[refit.length - 1]);
            }
            return refit;
        }

        /** Apply CF-04 again after all CF-03 river-river approach convergence is frozen. */
        private void freezeFinalLakeGateHydraulics() {
            if (finalLakeConnections.isEmpty()) return;
            double[] refit = refitFinalLakeGateProfiles(longitudinalProfile);
            longitudinalProfile = refit;
            int[] valleySurfaces = new int[refit.length];
            double[] widths = new double[refit.length];
            for (int i = 0; i < refit.length; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                double progress = clamp(distance / mouthDistance, 0.0D, 1.0D);
                widths[i] = waterWidth(progress);
                valleySurfaces[i] = naturalSurfaceAt(pointAtDistance(distance));
            }
            waterProfile = scheduleRapidProfile(longitudinalProfile, valleySurfaces, widths);
        }

        /**
         * Refit only a mismatched terminal approach after the receiving river has already built
         * its final profile. The W05 anchor remains fixed. The first parent-water contact inherits
         * the parent's local terrace, and the remaining drop is redistributed monotonically to the
         * unchanged terminal anchor. Existing approaches within one block are left byte-for-byte
         * unchanged.
         */
        private double[] refitTerminalHydraulicApproach(double[] grade) {
            terminalHydraulicApproachRefit = false;
            if (terminalRiver == null || grade.length < 2) return grade;
            ParentWaterGeometryContact contact = firstParentWaterMaskGeometryContact(this);
            if (!contact.found()) return grade;

            int childContactY = (int)Math.round(floatingProfileAt(grade,
                    contact.childStation()));
            int parentContactY = terminalRiver.waterSurfaceY(contact.parentStation());
            if (Math.abs(childContactY - parentContactY) <= 1) return grade;
            if (parentContactY < terminalWaterY) {
                throw new IllegalStateException("River " + name
                        + " parent first-contact level lies below fixed terminal anchor: contact="
                        + parentContactY + ", anchor=" + terminalWaterY);
            }
            // The known residual class is an approach flattened too early below a higher parent
            // terrace. Do not invent a lowering policy for an unobserved opposite-sign mismatch.
            if (parentContactY < childContactY) return grade;

            double[] refit = grade.clone();
            int contactIndex = Math.max(0, Math.min(refit.length - 2,
                    (int)Math.floor(contact.childStation() / PROFILE_SPACING)));
            double contactGrid = contactIndex * PROFILE_SPACING;
            double span = Math.max(PROFILE_SPACING, mouthDistance - contactGrid);
            for (int i = contactIndex; i < refit.length; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                double t = clamp((distance - contactGrid) / span, 0.0D, 1.0D);
                double target = lerp(parentContactY, terminalWaterY, smoothstep(0.0D, 1.0D, t));
                refit[i] = Math.max(refit[i], target);
            }
            refit[refit.length - 1] = terminalWaterY;

            // Raising the contact sample can expose the old flattened hold immediately upstream.
            // Propagate only as far as necessary to retain a non-increasing grade; once the
            // existing upstream profile is already high enough it is untouched.
            for (int i = contactIndex - 1; i >= 0; i--) {
                if (refit[i] >= refit[i + 1]) break;
                refit[i] = refit[i + 1];
            }
            for (int i = 1; i < refit.length; i++) {
                refit[i] = Math.min(refit[i - 1], refit[i]);
            }
            refit[refit.length - 1] = terminalWaterY;
            terminalHydraulicApproachRefit = true;
            return refit;
        }

        /**
         * Solve one floating longitudinal grade for the complete river before block quantisation.
         * The existing integer solver supplies a conservative monotonic ceiling and all lake /
         * confluence anchors.  This pass only spreads slope upstream by lowering intermediate
         * values; it can never raise a downstream station or make DEM control the final water Y.
         */
        private double[] solveFloatingLongitudinalProfile(int[] baseProfile) {
            double[] grade = new double[baseProfile.length];
            for (int i = 0; i < grade.length; i++) grade[i] = baseProfile[i];
            if (grade.length <= 2) return grade;

            // Repeated second-derivative relaxation removes the old terrace-shaped longitudinal
            // signal while preserving source/outlet anchors and monotonic flow.  Since the upper
            // clamp is the old solved profile, relaxation may only lower a station, never flood it.
            for (int pass = 0; pass < 10; pass++) {
                double[] next = grade.clone();
                for (int i = 1; i < grade.length - 1; i++) {
                    double curvatureAverage = (grade[i - 1] + grade[i] * 2.0D
                            + grade[i + 1]) * 0.25D;
                    double upper = Math.min(grade[i - 1], baseProfile[i]);
                    double lower = grade[i + 1];
                    next[i] = clamp(curvatureAverage, lower, upper);
                }
                next[0] = baseProfile[0];
                next[next.length - 1] = baseProfile[baseProfile.length - 1];
                for (int i = 1; i < next.length; i++) {
                    next[i] = Math.min(next[i - 1], next[i]);
                }
                grade = next;
            }
            return grade;
        }

        /**
         * Quantise the floating river grade separately from the solver. Every ordinary fall is a
         * one-block rapid; its position is selected from nearby terrain rather than mechanically
         * occurring every PROFILE_SPACING blocks. Drops are only moved upstream from the ideal
         * floating crossing, so scheduling can never hold water higher than the solved profile.
         */
        private int[] scheduleRapidProfile(double[] grade, int[] valleySurfaces, double[] widths) {
            int fineCount = Math.max(2, (int)Math.ceil(mouthDistance / RAPID_SPACING) + 1);
            int[] scheduled = new int[fineCount];
            int[] rapidTerrain = new int[fineCount];
            double[] rapidWidths = new double[fineCount];
            double[] rapidBends = new double[fineCount];

            int sourceLakeHoldIndex = sourceLakeName.isEmpty() ? 1
                    : Math.max(1, (int)Math.ceil(sourceLakeGateHoldLength / RAPID_SPACING));
            for (int i = 0; i < fineCount; i++) {
                double distance = Math.min(mouthDistance, i * RAPID_SPACING);
                double progress = clamp(distance / mouthDistance, 0.0D, 1.0D);
                rapidWidths[i] = waterWidth(progress);
                rapidTerrain[i] = naturalSurfaceAt(pointAtDistance(distance));
                rapidBends[i] = turnAngleAt(distance, rapidWidths[i]);
            }

            int sourceY = (int)Math.round(grade[0]);
            int terminalY = (int)Math.round(grade[grade.length - 1]);
            int totalDrop = Math.max(0, sourceY - terminalY);
            if (totalDrop == 0) {
                java.util.Arrays.fill(scheduled, sourceY);
                scheduled[scheduled.length - 1] = terminalY;
                return scheduled;
            }

            /*
             * First find the exact half-block crossings of the solved floating profile. These are
             * hydraulic deadlines, not suggestions. The previous reverse greedy scheduler could
             * relocate a crossing hundreds of blocks upstream while trying to satisfy visual
             * spacing preferences, causing integer water to sit 100-250 blocks below H(s).
             */
            int[] ideal = new int[totalDrop];
            int cursor = Math.max(1, sourceLakeHoldIndex);
            for (int d = 1; d <= totalDrop; d++) {
                double threshold = sourceY - d + 0.5D;
                while (cursor < fineCount - 1
                        && floatingProfileAt(grade, cursor * RAPID_SPACING) > threshold) {
                    cursor++;
                }
                ideal[d - 1] = Math.max(sourceLakeHoldIndex, cursor);
            }

            int[] event = new int[fineCount];
            int previousPlaced = sourceLakeHoldIndex - 1;
            int previousSpacing = -1;
            int repeatedSpacing = 0;
            for (int e = 0; e < ideal.length; e++) {
                int idealIndex = Math.max(sourceLakeHoldIndex,
                        Math.min(fineCount - 1, ideal[e]));
                int earliest = Math.max(previousPlaced + 1,
                        idealIndex - RAPID_RELOCATION_RADIUS_SAMPLES);
                int latest = Math.min(fineCount - (ideal.length - e),
                        idealIndex + RAPID_RELOCATION_RADIUS_SAMPLES);
                if (latest < earliest) latest = earliest;

                int upperY = sourceY - e;
                int lowerY = upperY - 1;
                int best = -1;
                double bestPenalty = Double.POSITIVE_INFINITY;
                for (int candidate = earliest; candidate <= latest; candidate++) {
                    if (candidate <= 0 || candidate >= fineCount || event[candidate] != 0) continue;
                    if (!rapidCandidateKeepsProfileError(grade, candidate, idealIndex,
                            upperY, lowerY)) continue;

                    double penalty = rapidPlacementPenalty(candidate, idealIndex,
                            rapidTerrain, rapidWidths);
                    double width = rapidWidths[candidate];
                    double bend = rapidBends[candidate];
                    if (width >= 48.0D && bend >= 25.0D) penalty += 250.0D;
                    int spacing = previousPlaced >= sourceLakeHoldIndex
                            ? candidate - previousPlaced : Integer.MAX_VALUE;
                    if (spacing != Integer.MAX_VALUE) {
                        double altitude = rapidTerrain[candidate] - PJChunkGenerator.SEA_LEVEL;
                        boolean mountainRapid = headwaterInfluence(candidate * RAPID_SPACING) > 0.0D
                                || (rapidWidths[candidate] < 32.0D && altitude >= 48.0D);
                        if (!mountainRapid && spacing <= 4) {
                            double crowd = 5 - spacing;
                            penalty += 120.0D + crowd * crowd * 30.0D;
                        }
                    }
                    if (spacing != Integer.MAX_VALUE && previousSpacing > 0) {
                        if (spacing == previousSpacing) {
                            penalty += 2.0D + repeatedSpacing * 4.0D;
                        } else if (Math.abs(spacing - previousSpacing) <= 1) {
                            penalty += 0.5D;
                        }
                    }
                    if (penalty < bestPenalty - 1.0E-9D
                            || (Math.abs(penalty - bestPenalty) <= 1.0E-9D
                            && Math.abs(candidate - idealIndex) < Math.abs(best - idealIndex))) {
                        bestPenalty = penalty;
                        best = candidate;
                    }
                }

                if (best < 0) {
                    // Hydraulic correctness wins over aesthetics. Use the nearest free threshold
                    // crossing rather than widening the search and silently pre-consuming future
                    // drop budget. If even that is impossible the floating profile itself must be
                    // fixed; never manufacture a distant rapid.
                    int fallbackStart = Math.max(previousPlaced + 1,
                            idealIndex - RAPID_RELOCATION_RADIUS_SAMPLES);
                    int fallbackEnd = Math.min(fineCount - (ideal.length - e),
                            idealIndex + RAPID_RELOCATION_RADIUS_SAMPLES);
                    for (int radius = 0; radius <= RAPID_RELOCATION_RADIUS_SAMPLES && best < 0; radius++) {
                        int left = idealIndex - radius;
                        int right = idealIndex + radius;
                        if (left >= fallbackStart && left <= fallbackEnd && event[left] == 0
                                && rapidCandidateKeepsProfileError(grade, left, idealIndex,
                                upperY, lowerY)) best = left;
                        if (best < 0 && right >= fallbackStart && right <= fallbackEnd
                                && event[right] == 0
                                && rapidCandidateKeepsProfileError(grade, right, idealIndex,
                                upperY, lowerY)) best = right;
                    }
                }
                if (best < 0) {
                    throw new IllegalStateException("River " + name
                            + " cannot quantise floating profile locally: event=" + e
                            + ", ideal=" + idealIndex + ", previous=" + previousPlaced);
                }

                event[best] = 1;
                if (previousPlaced >= sourceLakeHoldIndex) {
                    int spacing = best - previousPlaced;
                    if (spacing == previousSpacing) repeatedSpacing++;
                    else repeatedSpacing = 0;
                    previousSpacing = spacing;
                }
                previousPlaced = best;
            }

            int y = sourceY;
            scheduled[0] = y;
            double maximumError = Math.abs(y - floatingProfileAt(grade, 0.0D));
            for (int i = 1; i < scheduled.length; i++) {
                if (event[i] != 0) y -= 1;
                scheduled[i] = y;
                double distance = Math.min(mouthDistance, i * RAPID_SPACING);
                maximumError = Math.max(maximumError,
                        Math.abs(y - floatingProfileAt(grade, distance)));
                if (scheduled[i - 1] - scheduled[i] > 1) {
                    throw new IllegalStateException("River " + name
                            + " produced a multi-block ordinary rapid at station " + i);
                }
            }
            if (scheduled[scheduled.length - 1] != terminalY) {
                throw new IllegalStateException("River " + name
                        + " rapid scheduler missed terminal anchor: scheduled="
                        + scheduled[scheduled.length - 1] + ", terminal=" + terminalY);
            }
            if (maximumError > MAX_SCHEDULED_PROFILE_ERROR + 1.0E-9D) {
                throw new IllegalStateException("River " + name
                        + " integer profile drifted from floating grade by " + maximumError
                        + " blocks (limit=" + MAX_SCHEDULED_PROFILE_ERROR + ")");
            }
            return scheduled;
        }

        private boolean rapidCandidateKeepsProfileError(double[] grade, int candidate,
                                                        int idealIndex, int upperY, int lowerY) {
            int from = Math.min(candidate, idealIndex);
            int to = Math.max(candidate, idealIndex);
            for (int i = from; i <= to; i++) {
                int level = i < candidate ? upperY : lowerY;
                double floating = floatingProfileAt(grade,
                        Math.min(mouthDistance, i * RAPID_SPACING));
                if (Math.abs(level - floating) > MAX_SCHEDULED_PROFILE_ERROR) return false;
            }
            return true;
        }


        private double floatingProfileAt(double[] grade, double alongDistance) {
            double distance = clamp(alongDistance, 0.0D, mouthDistance);
            if (distance >= mouthDistance) return grade[grade.length - 1];
            double position = distance / PROFILE_SPACING;
            int index = Math.min(grade.length - 2,
                    Math.max(0, (int)Math.floor(position)));
            double fraction = position - index;
            return lerp(grade[index], grade[index + 1], fraction);
        }

        private int rapidMinimumSpacingSamples(double width, int naturalY) {
            double altitude = Math.max(0.0D, naturalY - PJChunkGenerator.SEA_LEVEL);
            if (width < 24.0D && altitude > 80.0D) return 10;  // >= 10 blocks
            if (width < 48.0D) return 12;                      // >= 12 blocks
            if (width < 96.0D) return 16;                      // >= 16 blocks
            return 20;                                          // >= 20 blocks
        }

        private double rapidPlacementPenalty(int candidate, int idealIndex,
                                             int[] rapidTerrain, double[] rapidWidths) {
            int before = Math.max(0, candidate - 3);
            int after = Math.min(rapidTerrain.length - 1, candidate + 3);
            double terrainDescent = Math.max(0.0D,
                    rapidTerrain[before] - rapidTerrain[after]);
            double distance = Math.min(mouthDistance, candidate * RAPID_SPACING);
            double width = rapidWidths[Math.max(0, Math.min(rapidWidths.length - 1, candidate))];
            double bend = turnAngleAt(distance, width);
            double shiftBlocks = Math.abs(candidate - idealIndex) * RAPID_SPACING;
            int hash = name.hashCode() * 31 + candidate * 1103515245;
            double tieBreak = ((hash >>> 8) & 1023) / 1023.0D * 0.05D;
            double sharpWidePenalty = width >= 48.0D && bend >= 25.0D ? 10_000.0D : 0.0D;
            return sharpWidePenalty + shiftBlocks * 0.19D
                    + bend * (0.055D + width / 7000.0D)
                    + width * 0.003D - terrainDescent * 0.70D + tieBreak;
        }

        private int profileTerrainCutLimit(double progress, double width, int naturalSurface) {
            double altitude = Math.max(0.0D, naturalSurface - PJChunkGenerator.SEA_LEVEL);
            double mountain = clamp(altitude / 320.0D, 0.0D, 1.0D);
            double narrow = 1.0D - smoothstep(28.0D, 72.0D, width);
            double allowance = mountain * narrow;
            return (int)Math.round(lerp(RIVER_PROFILE_PREFERRED_MAX_CUT,
                    RIVER_PROFILE_EMERGENCY_MAX_CUT, allowance));
        }

        private int terrainAwareDropCap(int boundaryIndex, int[] desired,
                                        int[] valleySurfaces, double[] widths) {
            int i = Math.max(1, Math.min(desired.length - 1, boundaryIndex));
            int base = maximumTerraceDrop;
            double width = Math.max(widths[i - 1], widths[i]);
            double altitude = Math.max(valleySurfaces[i - 1], valleySurfaces[i])
                    - PJChunkGenerator.SEA_LEVEL;
            double mountain = clamp(altitude / 240.0D, 0.0D, 1.0D);
            double narrow = 1.0D - smoothstep(28.0D, 56.0D, width);
            int terrainDrop = Math.max(0, desired[i - 1] - desired[i]);
            int requested = Math.min(RIVER_MAX_TERRAIN_AWARE_DROP,
                    Math.max(base, terrainDrop));
            int adaptive = (int)Math.round(lerp(base, requested, mountain * narrow));
            return Math.max(base, Math.min(RIVER_MAX_TERRAIN_AWARE_DROP, adaptive));
        }


        /**
         * Rebuild only the high-altitude source prefix from the authored centre-line terrain.
         * The preferred envelope is monotonically non-increasing, so a DEM rise can never raise a
         * downstream water sample.  As soon as the envelope meets the established hydraulic
         * profile, normal river logic resumes unchanged.
         */
        private void applyHighHeadwaterProfile(int[] profile) {
            this.headwaterProfileLength = 0.0D;
            if (!sourceLakeName.isEmpty() || sourceNaturalY < HIGH_HEADWATER_MIN_NATURAL_Y
                    || profile.length < 3) return;

            int minMerge = Math.max(2,
                    (int)Math.ceil(HEADWATER_MIN_PROFILE_LENGTH / PROFILE_SPACING));
            int maxMerge = Math.min(profile.length - 2,
                    (int)Math.ceil(HEADWATER_MAX_PROFILE_LENGTH / PROFILE_SPACING));
            if (maxMerge < minMerge) return;

            int sourceTarget = sourceNaturalY - HEADWATER_PREFERRED_SURFACE_CUT;
            int merge = -1;
            int fallback = minMerge;
            double fallbackScore = Double.POSITIVE_INFINITY;

            // Rejoin the existing monotonic profile where it is reasonably close to the route
            // terrain again.  This keeps the source fix local while avoiding a hard hand-off in
            // the middle of a ridge.  Earlier versions picked a merge mainly by Y difference and
            // could lock kilometres of mountain to one low terrace.
            for (int i = minMerge; i <= maxMerge; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                int natural = naturalSurfaceAt(pointAtDistance(distance));
                int target = profile[i];
                if (target > sourceTarget) continue;
                int centreCut = Math.max(0, natural - target);
                int nextDrop = Math.max(0, target - profile[Math.min(profile.length - 1, i + 1)]);
                double score = centreCut * 5.0D + nextDrop * 3.0D + i * 0.015D;
                if (score < fallbackScore) {
                    fallbackScore = score;
                    fallback = i;
                }
                if (centreCut <= HEADWATER_MERGE_MAX_CENTRE_CUT) {
                    merge = i;
                    break;
                }
            }
            if (merge < 0) merge = fallback;
            merge = Math.max(minMerge, Math.min(maxMerge, merge));

            int[] ceiling = new int[merge + 1];
            for (int i = 0; i <= merge; i++) {
                double distance = Math.min(mouthDistance, i * PROFILE_SPACING);
                int natural = naturalSurfaceAt(pointAtDistance(distance));
                ceiling[i] = natural - HEADWATER_GRADE_TERRAIN_CLEARANCE;
            }
            ceiling[0] = sourceTarget;
            // Exact downstream hand-off is a hydraulic constraint, not a DEM clamp.
            ceiling[merge] = Math.min(ceiling[merge], profile[merge]);

            int lookAheadSamples = Math.max(2,
                    (int)Math.ceil(HEADWATER_GRADE_LOOKAHEAD / PROFILE_SPACING));
            int current = sourceTarget;
            profile[0] = current;
            for (int i = 0; i < merge; i++) {
                int furthest = Math.min(merge, i + lookAheadSamples);
                double requiredAverageDrop = 0.0D;
                for (int j = i + 1; j <= furthest; j++) {
                    // Future terrain can force a descent, but it can never raise the river again.
                    // Looking ahead spreads that required descent over the available upstream
                    // distance instead of waiting for one 8-20 block waterfall at the low point.
                    int futureCeiling = ceiling[j];
                    if (j == merge) futureCeiling = Math.min(futureCeiling, profile[merge]);
                    requiredAverageDrop = Math.max(requiredAverageDrop,
                            (current - futureCeiling) / (double)(j - i));
                }
                int plannedDrop = Math.max(0, (int)Math.ceil(requiredAverageDrop - 1.0E-9D));
                int next = current - plannedDrop;
                next = Math.min(next, ceiling[i + 1]);
                next = Math.min(current, next);
                if (i + 1 == merge) next = profile[merge];
                profile[i + 1] = next;
                current = next;
            }

            // Quantisation and a very steep real centre-line slope can still require a larger local
            // drop, but never a reverse-flow step.  Source Y remains the successful DEM-aligned
            // value; every subsequent sample is monotonically non-increasing.
            for (int i = 1; i <= merge; i++) {
                profile[i] = Math.min(profile[i - 1], profile[i]);
            }
            this.headwaterProfileLength = Math.min(mouthDistance, merge * PROFILE_SPACING);
        }

        /**
         * Keep one-block and wide-river level changes separate, relocating an unchanged step only
         * when it falls on a sharp bend. Narrow, steeper rivers may combine closely spaced changes
         * into one controlled drop. Moving a later change upstream only lowers intermediate water
         * surfaces, so it cannot flood a bank that was safe under the terrain-derived profile. The
         * four-sample gap prevents adjacent 16-block bands from forming a broad sheet around a bend.
         */
        private void coalesceDenseTerraces(int[] profile) {
            int searchFrom = 1;
            int previousPlacedDrop = Integer.MIN_VALUE / 2;
            while (searchFrom < profile.length) {
                int firstDrop = nextDropBoundary(profile, searchFrom);
                if (firstDrop < 0) return;

                double firstDistance = Math.min(mouthDistance,
                        firstDrop * PROFILE_SPACING);
                double firstWidth = waterWidth(firstDistance / mouthDistance);

                // The high-source grade solver has already distributed unavoidable elevation loss
                // over its look-ahead window. Re-combining those steps here recreated the very
                // 6-20 block headwater walls that the solver was meant to remove.
                if (headwaterProfileLength > 0.0D && firstDistance <= headwaterProfileLength) {
                    previousPlacedDrop = firstDrop;
                    searchFrom = firstDrop + 1;
                    continue;
                }

                if (maximumTerraceDrop <= 1 || firstWidth >= 32.0D) {
                    // A lowland or full-width step must retain its configured height. 0.5.15
                    // combined Yodo's small steps into the reported three-block river-wide weir.
                    // A curved reach may move the unchanged single step to a straighter upstream
                    // slot, but it is never combined with neighbouring lowland steps.
                    int placedDrop = firstDrop;
                    double turnAngle = turnAngleAt(firstDistance, firstWidth);
                    if (turnAngle >= 25.0D) {
                        int followingDrop = nextDropBoundary(profile, firstDrop + 1);
                        placedDrop = safestUpstreamDropBoundary(profile, firstDrop,
                                previousPlacedDrop, followingDrop);
                        int lowerWaterY = profile[firstDrop];
                        for (int i = placedDrop; i < firstDrop; i++) {
                            profile[i] = lowerWaterY;
                        }
                    }
                    previousPlacedDrop = placedDrop;
                    searchFrom = firstDrop + 1;
                    continue;
                }

                int upperWaterY = profile[firstDrop - 1];
                int lastDrop = firstDrop;
                int nextSearch = firstDrop + 1;
                while (nextSearch < profile.length) {
                    int candidate = nextDropBoundary(profile, nextSearch);
                    if (candidate < 0
                            || candidate - firstDrop >= MIN_TERRACE_SPACING_SAMPLES
                            || upperWaterY - profile[candidate]
                            > coalescedTerraceDropCap()) {
                        break;
                    }
                    lastDrop = candidate;
                    nextSearch = candidate + 1;
                }

                int followingDrop = nextDropBoundary(profile, lastDrop + 1);
                int placedDrop = safestUpstreamDropBoundary(profile, firstDrop,
                        previousPlacedDrop, followingDrop);
                int combinedLowerY = profile[lastDrop];
                for (int i = placedDrop; i < lastDrop; i++) {
                    profile[i] = combinedLowerY;
                }
                previousPlacedDrop = placedDrop;
                searchFrom = lastDrop + 1;
            }
        }

        private int safestUpstreamDropBoundary(int[] profile, int originalBoundary,
                                                int previousPlacedDrop,
                                                int followingDrop) {
            int earliest = Math.max(1,
                    originalBoundary - DROP_RELOCATION_UPSTREAM_SAMPLES);
            earliest = Math.max(earliest,
                    previousPlacedDrop + MIN_TERRACE_SPACING_SAMPLES);
            if (!sourceLakeName.isEmpty()) {
                earliest = Math.max(earliest,
                        (int)Math.ceil(sourceConnectionLength / PROFILE_SPACING));
            }
            int latest = originalBoundary;
            if (followingDrop >= 0) {
                // Reserve room now for a following drop that could not be combined because the
                // total fall exceeded this river's cap. Otherwise the first drop can consume the
                // only straight slot and leave the next curtain inside the same bend.
                latest = Math.min(latest,
                        followingDrop - MIN_TERRACE_SPACING_SAMPLES);
            }
            if (latest < earliest) return originalBoundary;

            int upperWaterY = profile[originalBoundary - 1];
            int best = -1;
            double bestPenalty = Double.POSITIVE_INFINITY;
            for (int candidate = earliest; candidate <= latest; candidate++) {
                if (profile[candidate - 1] != upperWaterY) continue;
                double penalty = dropPlacementPenalty(candidate)
                        + (originalBoundary - candidate) * 0.20D;
                if (penalty + 1.0e-9D < bestPenalty) {
                    best = candidate;
                    bestPenalty = penalty;
                }
            }
            return best < 0 ? originalBoundary : best;
        }

        private double dropPlacementPenalty(int boundaryIndex) {
            double distance = Math.min(mouthDistance, boundaryIndex * PROFILE_SPACING);
            double progress = distance / mouthDistance;
            double width = waterWidth(progress);
            double angle = turnAngleAt(distance, width);
            // Curvature is increasingly visible across a wide channel. A small width term also
            // breaks ties in favour of the narrower upstream site without dominating bend safety.
            return angle * (1.0D + width / 96.0D) + width * 0.01D;
        }

        private static int nextDropBoundary(int[] profile, int start) {
            for (int i = Math.max(1, start); i < profile.length; i++) {
                if (profile[i - 1] > profile[i]) return i;
            }
            return -1;
        }

        private int coalescedTerraceDropCap() {
            // This path is used only while the channel is narrower than 32 blocks and its
            // configured drop is above one. Steep narrow rivers may combine two close drops into
            // one six-block fall rather than leaving interacting curtains inside one bend.
            return Math.min(MAX_COALESCED_TERRACE_DROP,
                    Math.max(3, maximumTerraceDrop * 2));
        }

        private int valleySurfaceAt(double distance, double width) {
            WorldPoint centre = pointAtDistance(distance);
            WorldPoint before = pointAtDistance(Math.max(0.0D, distance - 32.0D));
            WorldPoint after = pointAtDistance(Math.min(mouthDistance, distance + 32.0D));
            double tangentX = after.x() - before.x();
            double tangentZ = after.z() - before.z();
            double tangentLength = Math.max(1.0D, Math.hypot(tangentX, tangentZ));
            double unitX = tangentX / tangentLength;
            double unitZ = tangentZ / tangentLength;
            double normalX = -unitZ;
            double normalZ = unitX;
            int search = (int)Math.round(clamp(80.0D + width * 1.4D, 96.0D, 384.0D));

            int bestSurface = naturalSurfaceAt(centre);
            double bestScore = bestSurface;
            for (int lateral = -search; lateral <= search; lateral += 16) {
                for (int longitudinal = -32; longitudinal <= 32; longitudinal += 32) {
                    WorldPoint sample = new WorldPoint(
                            centre.x() + normalX * lateral + unitX * longitudinal,
                            centre.z() + normalZ * lateral + unitZ * longitudinal);
                    TerrainData.TerrainSample terrain = TerrainData.sampleWorld(
                            (int)Math.round(sample.x()), (int)Math.round(sample.z()));
                    if (!terrain.land()) continue;
                    int surface = naturalSurfaceAt(sample);
                    double score = surface + Math.abs(lateral) * 0.025D
                            + Math.abs(longitudinal) * 0.015D;
                    if (score < bestScore) {
                        bestScore = score;
                        bestSurface = surface;
                    }
                }
            }
            return bestSurface;
        }


        /**
         * Shift only interior spline samples toward the nearest plausible valley floor. The old
         * implementation sampled a low point beside the authored line but kept the actual river on
         * the mountain shoulder; its water profile therefore used the valley height while the blocks
         * were still carved through the ridge. This conservative lateral snap moves the geometry as
         * well as the height sample, preserves the exact source/mouth endpoint itself, and never
         * searches farther than 96 blocks (about 0.8 km in PJ scale).
         */
        private static List<WorldPoint> snapRouteToValleys(List<WorldPoint> raw) {
            int count = raw.size();
            // buildSpline() samples at about 32 blocks. Eight samples are roughly 256 blocks /
            // 2 km in PJ's real scale. The legacy value of 64 accidentally froze about 16 km at
            // each end and left large parts of mountain rivers unable to reach a nearby valley.
            // Keep only the true source/mouth connection neighbourhood fixed.
            final int endpointGuard = 8;
            if (count < endpointGuard * 2 + 1) return raw;
            double[] offsets = new double[count];

            for (int i = endpointGuard; i < count - endpointGuard; i++) {
                WorldPoint point = raw.get(i);
                WorldPoint before = raw.get(i - 1);
                WorldPoint after = raw.get(i + 1);
                double tx = after.x() - before.x();
                double tz = after.z() - before.z();
                double length = Math.max(1.0D, Math.hypot(tx, tz));
                double nx = -tz / length;
                double nz = tx / length;

                TerrainData.TerrainSample centreTerrain = TerrainData.sampleWorld(
                        (int)Math.round(point.x()), (int)Math.round(point.z()));
                if (!centreTerrain.land()) continue;
                double bestOffset = 0.0D;
                double bestScore = naturalSurfaceAt(point);
                for (int lateral = -96; lateral <= 96; lateral += 8) {
                    WorldPoint candidate = new WorldPoint(
                            point.x() + nx * lateral, point.z() + nz * lateral);
                    TerrainData.TerrainSample terrain = TerrainData.sampleWorld(
                            (int)Math.round(candidate.x()), (int)Math.round(candidate.z()));
                    if (!terrain.land()) continue;
                    double score = naturalSurfaceAt(candidate) + Math.abs(lateral) * 0.18D;
                    if (score < bestScore) {
                        bestScore = score;
                        bestOffset = lateral;
                    }
                }

                offsets[i] = bestOffset;
            }

            // Smooth lateral displacement along the route so adjacent dense spline samples do
            // not zig-zag between neighbouring DEM cells.
            double[] smoothed = new double[count];
            for (int i = endpointGuard; i < count - endpointGuard; i++) {
                double weighted = 0.0D;
                double weight = 0.0D;
                for (int j = Math.max(endpointGuard, i - 10);
                     j <= Math.min(count - endpointGuard - 1, i + 10); j++) {
                    double w = 11.0D - Math.abs(i - j);
                    weighted += offsets[j] * w;
                    weight += w;
                }
                smoothed[i] = weight > 0.0D ? weighted / weight : 0.0D;
            }

            List<WorldPoint> shifted = new ArrayList<>(count);
            shifted.add(raw.get(0));
            for (int i = 1; i < count - 1; i++) {
                if (i < endpointGuard || i >= count - endpointGuard) {
                    shifted.add(raw.get(i));
                    continue;
                }
                WorldPoint before = raw.get(i - 1);
                WorldPoint after = raw.get(i + 1);
                double tx = after.x() - before.x();
                double tz = after.z() - before.z();
                double length = Math.max(1.0D, Math.hypot(tx, tz));
                double nx = -tz / length;
                double nz = tx / length;
                shifted.add(new WorldPoint(raw.get(i).x() + nx * smoothed[i],
                        raw.get(i).z() + nz * smoothed[i]));
            }
            shifted.add(raw.get(count - 1));
            return shifted;
        }


        /**
         * Round the remaining polyline corners after valley snapping. Valley snapping operates on
         * discrete DEM samples and can still leave a visible change of heading at one sample even
         * when the original Catmull-Rom route was smooth. Three light Chaikin-like passes keep the
         * river close to the selected valley while removing those one-segment elbows.
         */
        private static List<WorldPoint> smoothRoute(List<WorldPoint> input) {
            List<WorldPoint> current = input;
            final int endpointGuard = 8;
            for (int pass = 0; pass < 4; pass++) {
                List<WorldPoint> next = new ArrayList<>(current.size());
                for (int i = 0; i < current.size(); i++) {
                    if (i < endpointGuard || i >= current.size() - endpointGuard) {
                        next.add(current.get(i));
                        continue;
                    }
                    WorldPoint before = current.get(i - 1);
                    WorldPoint point = current.get(i);
                    WorldPoint after = current.get(i + 1);
                    next.add(new WorldPoint(
                            (before.x() + point.x() * 2.0D + after.x()) * 0.25D,
                            (before.z() + point.z() * 2.0D + after.z()) * 0.25D));
                }
                current = next;
            }
            return current;
        }

        /** AR-only rounding: preserve exact source/mouth but blend right up to their approach. */
        private static List<WorldPoint> smoothAuthoredRefreshRoute(List<WorldPoint> input,
                                                                   boolean relocatedSource) {
            if (input.size() <= 4) return input;
            List<WorldPoint> current = input;
            final int sourceGuard = relocatedSource ? 1 : 2;
            final int mouthGuard = 2;
            // AR endpoint reconciliation already removes 48-block elbows before this stage.
            // Thirty-two Laplacian passes erased kilometre-scale W05 curvature and turned REBUILD
            // courses into artificial chords. Twelve light passes retain broad W05 curvature.
            for (int pass = 0; pass < 12; pass++) {
                List<WorldPoint> next = new ArrayList<>(current.size());
                for (int i = 0; i < current.size(); i++) {
                    if (i < sourceGuard || i >= current.size() - mouthGuard) {
                        next.add(current.get(i));
                        continue;
                    }
                    WorldPoint before = current.get(i - 1);
                    WorldPoint point = current.get(i);
                    WorldPoint after = current.get(i + 1);
                    next.add(new WorldPoint(
                            (before.x() + point.x() * 2.0D + after.x()) * 0.25D,
                            (before.z() + point.z() * 2.0D + after.z()) * 0.25D));
                }
                current = next;
            }
            for (int pass = 0; pass < 10; pass++) {
                List<WorldPoint> next = new ArrayList<>(current.size());
                boolean changed = false;
                for (int i = 0; i < current.size(); i++) {
                    if (i < sourceGuard || i >= current.size() - mouthGuard) { next.add(current.get(i)); continue; }
                    WorldPoint before=current.get(i-1), point=current.get(i), after=current.get(i+1);
                    double ax=point.x()-before.x(), az=point.z()-before.z(), bx=after.x()-point.x(), bz=after.z()-point.z();
                    double al=Math.hypot(ax,az), bl=Math.hypot(bx,bz), turn=0.0D;
                    if(al>1e-9&&bl>1e-9){ double dot=clamp((ax*bx+az*bz)/(al*bl),-1.0D,1.0D); turn=Math.toDegrees(Math.acos(dot)); }
                    if(turn>=10.0D){ next.add(new WorldPoint((before.x()+2*point.x()+after.x())*.25D,(before.z()+2*point.z()+after.z())*.25D)); changed=true; }
                    else next.add(point);
                }
                current=next; if(!changed) break;
            }
            return current;
        }

        /** Ensure no inland centre-line chord is long enough to be visible as a straight section. */
        private static List<WorldPoint> densifyRoute(List<WorldPoint> input) {
            List<WorldPoint> result = new ArrayList<>();
            for (int i = 0; i < input.size() - 1; i++) {
                WorldPoint start = input.get(i);
                WorldPoint end = input.get(i + 1);
                double length = Math.hypot(end.x() - start.x(), end.z() - start.z());
                int steps = Math.max(1, (int)Math.ceil(length / 24.0D));
                for (int step = 0; step < steps; step++) {
                    double t = step / (double)steps;
                    result.add(new WorldPoint(
                            lerp(start.x(), end.x(), t),
                            lerp(start.z(), end.z(), t)));
                }
            }
            result.add(input.get(input.size() - 1));
            return result;
        }


        private static List<WorldPoint> buildSpline(List<WorldPoint> controls) {
            List<WorldPoint> result = new ArrayList<>();
            for (int i = 0; i < controls.size() - 1; i++) {
                WorldPoint p0 = controls.get(Math.max(0, i - 1));
                WorldPoint p1 = controls.get(i);
                WorldPoint p2 = controls.get(i + 1);
                WorldPoint p3 = controls.get(Math.min(controls.size() - 1, i + 2));
                double length = Math.hypot(p2.x() - p1.x(), p2.z() - p1.z());
                // 32-block chord spacing (~256 real metres at 1:8) keeps broad river bends
                // visually continuous. The former 128-block chords were visible as straight
                // segments joined by corners on wide rivers.
                int steps = Math.max(1, (int)Math.ceil(length / 32.0D));
                for (int step = 0; step < steps; step++) {
                    double t = step / (double)steps;
                    result.add(catmullRom(p0, p1, p2, p3, t));
                }
            }
            result.add(controls.get(controls.size() - 1));
            return result;
        }

        private static WorldPoint catmullRom(WorldPoint p0, WorldPoint p1,
                                              WorldPoint p2, WorldPoint p3, double t) {
            double t2 = t * t;
            double t3 = t2 * t;
            double x = 0.5D * ((2.0D * p1.x())
                    + (-p0.x() + p2.x()) * t
                    + (2.0D * p0.x() - 5.0D * p1.x() + 4.0D * p2.x() - p3.x()) * t2
                    + (-p0.x() + 3.0D * p1.x() - 3.0D * p2.x() + p3.x()) * t3);
            double z = 0.5D * ((2.0D * p1.z())
                    + (-p0.z() + p2.z()) * t
                    + (2.0D * p0.z() - 5.0D * p1.z() + 4.0D * p2.z() - p3.z()) * t2
                    + (-p0.z() + 3.0D * p1.z() - 3.0D * p2.z() + p3.z()) * t3);
            return new WorldPoint(x, z);
        }

        private WorldPoint pointAtDistance(double target) {
            if (target <= 0.0D) return segments.get(0).start();
            if (target >= totalLength && totalLength > 0.0D) {
                return segments.get(segments.size() - 1).end();
            }

            // Segment start distances are monotonic. 0.5.8 linearly walked up to thousands of
            // 16-24 block segments for every water-profile and valley query during class init.
            // Binary search makes route setup O(log n) without changing any geometry.
            int low = 0;
            int high = segments.size() - 1;
            while (low < high) {
                int mid = (low + high) >>> 1;
                Segment segment = segments.get(mid);
                double endDistance = segment.startDistance() + segment.length();
                if (target <= endDistance) high = mid;
                else low = mid + 1;
            }
            Segment segment = segments.get(low);
            double t = clamp((target - segment.startDistance()) / segment.length(), 0.0D, 1.0D);
            return new WorldPoint(
                    segment.start().x() + segment.dx() * t,
                    segment.start().z() + segment.dz() * t);
        }

        private static int naturalSurfaceAt(WorldPoint point) {
            TerrainData.TerrainSample terrain = TerrainData.sampleWorld(
                    (int)Math.round(point.x()), (int)Math.round(point.z()));
            if (!terrain.land()) return PJChunkGenerator.SEA_LEVEL;
            double coastRise = smoothstep(0.50D, 0.84D, terrain.landCoverage());
            int elevationBlocks = Math.max(1, (int)Math.round(
                    terrain.elevationMetres() / TerrainData.VERTICAL_METRES_PER_BLOCK * coastRise));
            return PJChunkGenerator.SEA_LEVEL + elevationBlocks;
        }

        private static double smoothstep(double edge0, double edge1, double value) {
            double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
            return t * t * (3.0D - 2.0D * t);
        }
    }
}
