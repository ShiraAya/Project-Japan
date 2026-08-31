package com.sora.projectjapan.worldgen;

/**
 * CF-FINAL LakeUnion Fix3: local shoreline opening at a real final river/lake gate.
 * Bathymetry union remains restricted to lake.water(); this class only suppresses the
 * shoreline/bank apron in the short inlet/outlet funnel.
 */
final class LakeRiverShorelineAperture {
    private LakeRiverShorelineAperture() {}

    static Result resolve(LakeData.LakeSample lake, RiverData.RiverSample river) {
        if (!lake.corridor() || !river.corridor()
                || !river.connectsLake(lake.name())
                || river.lakeShorelineApertureWeight() <= 0.0D) return Result.NONE;
        double lateralBlend = LakeRiverBathymetryUnion.lateralBlendWidth(lake, river);
        double outer = river.halfWaterWidth() + lateralBlend;
        if (river.distanceToCentre() > outer) return Result.NONE;
        double outsideWet = Math.max(0.0D, river.distanceToCentre() - river.halfWaterWidth());
        double cross = outsideWet <= 0.0D ? 1.0D
                : 1.0D - clamp(outsideWet / Math.max(1.0D, lateralBlend), 0.0D, 1.0D);
        double weight = clamp(river.lakeShorelineApertureWeight() * cross, 0.0D, 1.0D);
        if (weight <= 0.0D) return Result.NONE;

        // Gate floor: use an explicit bounded-slope radial channel while the shoreline aperture
        // is active.  RiverData's ordinary rounded cross-section is ideal in free river reaches,
        // but a diagonal block transect can skip two depth levels at once.  At a lake gate that
        // becomes a visible underwater notch/seam.  Requiring roughly 1.5 horizontal blocks per
        // depth block makes the rasterised gate floor one-block continuous even on diagonal
        // normals, while still reaching the authored maximum depth in the core.  Inside actual
        // lake water this is depth-only: never lift a deeper lake floor.
        double insideWet = Math.max(0.0D, river.halfWaterWidth() - river.distanceToCentre());
        int gateDepth = 1 + (int)Math.floor(insideWet / 1.5D + 1.0e-6D);
        gateDepth = Math.max(1, Math.min(Math.max(1, river.maximumDepth()), gateDepth));
        int waterY = lake.water() ? lake.waterSurfaceY() : river.waterSurfaceY();
        int gateBedY = waterY - gateDepth;
        if (lake.water()) gateBedY = Math.min(lake.bedY(), gateBedY);
        gateBedY = Math.min(waterY - 1, gateBedY);
        return new Result(true, weight, lateralBlend, outer, outsideWet, cross, gateBedY, waterY);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    record Result(boolean active, double weight, double lateralBlendWidth, double outerRadius,
                  double distanceOutsideWetMask, double crossWeight, int gateBedY, int waterY) {
        static final Result NONE = new Result(false, 0.0D, 0.0D, 0.0D,
                Double.POSITIVE_INFINITY, 0.0D, 0, 0);
    }
}
