package com.sora.projectjapan.worldgen;

/**
 * CF-LAKE-UNION: one shared lateral/longitudinal bathymetry rule for a declared
 * river/lake connection.  Keeping this outside PJChunkGenerator makes the exact
 * production rule directly auditable without duplicating parameters in validation.
 */
final class LakeRiverBathymetryUnion {
    private LakeRiverBathymetryUnion() {}

    static Result resolve(LakeData.LakeSample lake, RiverData.RiverSample river) {
        if (!lake.water() || !river.corridor() || !river.connectsLake(lake.name())) {
            return Result.NONE;
        }
        double longitudinal = clamp(river.lakeConnectionBlend(), 0.0D, 1.0D);
        if (longitudinal <= 0.0D) return Result.NONE;

        int waterY = lake.waterSurfaceY();
        int riverDepth = Math.max(1, river.waterSurfaceY() - river.bedY());
        // A connected river may cut a thalweg into the lake bed, but it must never raise an
        // underwater "river bank" above the surrounding lake floor.  This is the exact visual
        // failure seen at Sendai-Kagoshima/Otsuru and Yoshino/Sameura: the shallow outer river
        // cross-section survived inside the lake as two pale ridges.  Union bathymetry is thus
        // depth-only: retain a deeper river trench, otherwise use the lake bed unchanged.
        int riverBedAtLakeLevel = Math.min(lake.bedY(), waterY - riverDepth);
        double lateralBlend = lateralBlendWidth(lake, river, riverBedAtLakeLevel);
        double outsideWet = Math.max(0.0D,
                river.distanceToCentre() - river.halfWaterWidth());
        // Use a bounded-slope linear lateral fade at block scale. A cubic smoothstep has a
        // 1.5x peak derivative and can quantise a perfectly valid 20-30 block bathymetry delta
        // into a two-block seam even with a 32-block corridor (Takase/Ogawara canary). The
        // linear fade is still gradual, but guarantees the strongest possible one-block-per-column
        // transition for a given corridor width. Longitudinal gate blending remains independent.
        double crossBlend = outsideWet <= 0.0D ? 1.0D
                : 1.0D - clamp(outsideWet / lateralBlend, 0.0D, 1.0D);
        double blend = clamp(longitudinal * crossBlend, 0.0D, 1.0D);
        if (blend <= 0.0D) return Result.NONE;

        int bedY = (int)Math.round(lerp(lake.bedY(), riverBedAtLakeLevel, blend));
        bedY = Math.min(waterY - 1, bedY);
        return new Result(true, bedY, waterY, riverBedAtLakeLevel,
                lateralBlend, outsideWet, crossBlend, longitudinal, blend);
    }

    /** Shared by bathymetry union and shoreline aperture; keep one width policy. */
    static double lateralBlendWidth(LakeData.LakeSample lake, RiverData.RiverSample river) {
        int waterY = lake.waterSurfaceY();
        int riverDepth = Math.max(1, river.waterSurfaceY() - river.bedY());
        int riverBedAtLakeLevel = Math.min(lake.bedY(), waterY - riverDepth);
        return lateralBlendWidth(lake, river, riverBedAtLakeLevel);
    }

    private static double lateralBlendWidth(LakeData.LakeSample lake, RiverData.RiverSample river,
                                             int riverBedAtLakeLevel) {
        int bedDelta = Math.abs(riverBedAtLakeLevel - lake.bedY());
        double effectiveWidth = Math.max(1.0D, river.halfWaterWidth() * 2.0D);
        return clamp(Math.max(12.0D,
                Math.max(effectiveWidth * 0.32D, bedDelta * 2.25D)), 12.0D, 32.0D);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) return value >= edge1 ? 1.0D : 0.0D;
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record Result(boolean active, int bedY, int waterY, int riverBedAtLakeLevel,
                  double lateralBlendWidth, double distanceOutsideWetMask,
                  double crossBlend, double longitudinalBlend, double unionBlend) {
        private static final Result NONE = new Result(false, 0, 0, 0,
                0.0D, Double.POSITIVE_INFINITY, 0.0D, 0.0D, 0.0D);
    }
}
