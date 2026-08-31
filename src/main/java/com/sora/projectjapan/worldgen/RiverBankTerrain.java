package com.sora.projectjapan.worldgen;

/**
 * Pure-Java river-bank terrain shaping shared by runtime generation and standalone audits.
 * Keeping this logic outside {@link PJChunkGenerator} lets validation run without bootstrapping
 * Minecraft registries while still exercising the exact production ValleyCarver,
 * ShorelineRelaxer, NaturalBankPreservation and OverflowGuard implementation.
 */
final class RiverBankTerrain {
    private static final int MAX_WATER_SURFACE_Y = 1920; // PJ MAX_Y_EXCLUSIVE (2000) - 80
    private static final double DYNAMIC_SPILL_EDGE_REACH = 2.5D;
    /**
     * Static water immediately beside a real local hydraulic step gets a one-block *preserved*
     * natural lip. This is deliberately shorter than the visual drop window: it is inferred from
     * actual neighbouring wet-column Y values and never from drop.active()/headwaterStep flags.
     */
    private static final int RAPID_EDGE_CONTEXT_REACH = 5;
    private static final int MOUNTAIN_RAPID_EDGE_FULL_WATER_Y = 112;
    private static final int HEADWATER_RAPID_EDGE_MIN_WATER_Y = 56;
    private static final int[][] CARDINAL_NEIGHBOURS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1}
    };

    private RiverBankTerrain() {}

    static int surface(int naturalSurface, RiverData.RiverSample river) {
        double distance = Math.max(0.0D, river.distanceFromWater());
        int actualWaterY = Math.min(MAX_WATER_SURFACE_Y, river.waterSurfaceY());
        int gradeY = Math.min(MAX_WATER_SURFACE_Y, river.bankReferenceWaterY());
        double bankBlend = river.bankBlend();
        double outerRelax = smoothstep(0.58D, 1.0D, bankBlend);
        int bankSurface = naturalSurface;

        if (naturalSurface >= actualWaterY) {
            // ValleyCarver: create a narrow natural river bench and then climb back toward the
            // untouched DEM. The longitudinal reference is a smoothing field only; local water is
            // the hydraulic base at the wet edge.
            double benchWidth = river.valleyBenchWidth();
            double benchRise = river.progress() < 0.35D ? 0.5D
                    : (river.progress() < 0.75D ? 1.0D : 1.5D);

            double actualIncision = Math.max(0.0D, naturalSurface - actualWaterY);
            double referenceBlendStart = Math.max(1.0D, benchWidth * 0.65D);
            double referenceBlendLength = clamp(16.0D + actualIncision * 0.12D,
                    20.0D, 64.0D);
            double gradeInfluence = smoothstep(referenceBlendStart,
                    referenceBlendStart + referenceBlendLength, distance);
            double floorGrade = lerp(actualWaterY, gradeY, gradeInfluence);

            double target;
            if (distance <= benchWidth) {
                double t = smoothstep(0.0D, benchWidth, distance);
                target = floorGrade + benchRise * t;
            } else {
                target = floorGrade + benchRise
                        + (distance - benchWidth) * river.bankSlope();
            }

            int valleyCeiling = (int)Math.floor(target + 1.0E-9D);
            int carved = Math.min(naturalSurface, valleyCeiling);
            bankSurface = (int)Math.round(lerp(carved, naturalSurface, outerRelax));

            // Natural Bank Preservation: bank protection means "do not carve away an already
            // sufficient natural bank", not "build a crest".  Only the near-water containment
            // band matters for side spill.  This stage may restore terrain that ValleyCarver would
            // otherwise cut below local water, but it can NEVER exceed untouched naturalSurface.
            if (distance <= river.naturalBankPreservationWidth() && bankSurface < actualWaterY) {
                int protectedFloor = Math.min(naturalSurface, actualWaterY);
                bankSurface = Math.max(bankSurface, protectedFloor);
            }
        }

        // OverflowGuard is the sole fill stage. If the untouched DEM is already lower than the
        // local water surface, fill only a very narrow strip up to actualWaterY. It never creates
        // waterY+1/+2 levees and is independent of rapid/headwater-step semantics.
        if (naturalSurface < actualWaterY) {
            double guardWidth = river.overflowGuardWidth();
            if (distance <= guardWidth) {
                if (distance <= 1.5D) {
                    bankSurface = Math.max(bankSurface, actualWaterY);
                } else {
                    double influence = 1.0D - smoothstep(1.5D, guardWidth, distance);
                    int guarded = (int)Math.round(lerp(naturalSurface, actualWaterY, influence));
                    bankSurface = Math.max(bankSurface, guarded);
                }
            }
        }
        return bankSurface;
    }


    /**
     * Final production bank surface including dynamic falling-water containment.
     *
     * <p>The two-argument {@link #surface(int, RiverData.RiverSample)} method is intentionally
     * kept as the pure ValleyCarver/ShorelineRelaxer/NaturalBankPreservation/OverflowGuard base
     * and is used while RiverData is still choosing among competing dry-bank candidates. This
     * coordinate-aware overload runs only after that hydraulic sample has been resolved, so it can
     * safely inspect the four neighbouring wet columns without creating recursive sample selection.
     * Dynamic containment therefore answers the real physical question: can a fluid-ticked falling
     * water column immediately beside this dry bank reach higher than the local static water Y?
     */
    static int surface(int worldX, int worldZ, int naturalSurface,
                       RiverData.RiverSample river) {
        int bankSurface = surface(naturalSurface, river);
        if (!river.corridor() || river.water()
                || river.distanceFromWater() > DYNAMIC_SPILL_EDGE_REACH) {
            return bankSurface;
        }

        int localWaterY = Math.min(MAX_WATER_SURFACE_Y, river.waterSurfaceY());
        int spillSurfaceY = localWaterY;
        boolean sideSpillRisk = false;
        for (int[] offset : CARDINAL_NEIGHBOURS) {
            RiverData.RiverSample wet = RiverData.sampleWorld(
                    worldX + offset[0], worldZ + offset[1]);
            if (!wet.corridor() || !wet.water()) continue;

            int neighbourWaterY = Math.min(MAX_WATER_SURFACE_Y, wet.waterSurfaceY());
            if (wet.needsFluidPostProcessing()) {
                int neighbourTopY = Math.min(MAX_WATER_SURFACE_Y, wet.generatedWaterTopY());
                if (neighbourTopY > localWaterY) {
                    sideSpillRisk = true;
                    spillSurfaceY = Math.max(spillSurfaceY, neighbourTopY);
                }
            }
        }

        if (sideSpillRisk && bankSurface < spillSurfaceY) {
            // If the untouched bank was already high enough, this is still preservation: undo only
            // ValleyCarver's over-cut and never exceed natural DEM. If the untouched DEM itself is
            // lower than the genuinely adjacent dynamic spill surface, this one edge column is the
            // only place where filling above natural terrain is physically necessary. There is no
            // drop/headwater/rapid-wide shoulder and no waterY+1/+2 crest rule.
            if (naturalSurface >= spillSurfaceY) {
                bankSurface = Math.max(bankSurface, spillSurfaceY);
            } else {
                bankSurface = spillSurfaceY;
            }
        }

        // Adaptive Rapid-Edge Preservation. Flat/lowland reaches remain flush; only the first dry
        // edge beside static water that is demonstrably inside a local hydraulic step preserves
        // one block of already-existing natural bank.
        int rapidEdgeFloorY = rapidEdgePreservationY(worldX, worldZ, naturalSurface,
                bankSurface, river);
        if (rapidEdgeFloorY != Integer.MIN_VALUE) {
            bankSurface = Math.max(bankSurface, rapidEdgeFloorY);
        }
        return bankSurface;
    }


    /**
     * Returns the one-block natural-bank floor for a static rapid edge, or Integer.MIN_VALUE when
     * no visual edge preservation is justified. Package-private so the standalone audit can
     * validate the exact production predicate instead of re-implementing it.
     */
    static int rapidEdgePreservationY(int worldX, int worldZ, int naturalSurface,
                                      int bankSurface, RiverData.RiverSample river) {
        if (!river.corridor() || river.water()
                || river.distanceFromWater() > DYNAMIC_SPILL_EDGE_REACH) {
            return Integer.MIN_VALUE;
        }

        // The visual edge exists only on the first dry cell beside this same river. Do not use
        // drop.active()/terraceDrop windows: those describe a longitudinal visual rapid and are
        // far wider than the physical bank edge.
        int edgeWaterY = Integer.MIN_VALUE;
        boolean adjacentWet = false;
        for (int[] offset : CARDINAL_NEIGHBOURS) {
            RiverData.RiverSample wet = RiverData.sampleWorld(
                    worldX + offset[0], worldZ + offset[1]);
            if (!wet.corridor() || !wet.water() || !wet.name().equals(river.name())) continue;
            adjacentWet = true;
            edgeWaterY = Math.max(edgeWaterY,
                    Math.min(MAX_WATER_SURFACE_Y, wet.waterSurfaceY()));
        }
        if (!adjacentWet || naturalSurface < edgeWaterY + 1
                || bankSurface >= edgeWaterY + 1) {
            return Integer.MIN_VALUE;
        }

        // Find the nearest real same-river water-surface change around this edge. This catches
        // static cells embedded inside a falling/rapid reach as well as purely static one-block
        // steps, but it no longer requires the immediately adjacent wet cell itself to be static.
        int minWetY = edgeWaterY;
        int maxWetY = edgeWaterY;
        int nearestDifferent = Integer.MAX_VALUE;
        for (int dz = -RAPID_EDGE_CONTEXT_REACH; dz <= RAPID_EDGE_CONTEXT_REACH; dz++) {
            for (int dx = -RAPID_EDGE_CONTEXT_REACH; dx <= RAPID_EDGE_CONTEXT_REACH; dx++) {
                int manhattan = Math.abs(dx) + Math.abs(dz);
                if (manhattan == 0 || manhattan > RAPID_EDGE_CONTEXT_REACH) continue;
                RiverData.RiverSample wet = RiverData.sampleWorld(worldX + dx, worldZ + dz);
                if (!wet.corridor() || !wet.water() || !wet.name().equals(river.name())) continue;
                int wetY = Math.min(MAX_WATER_SURFACE_Y, wet.waterSurfaceY());
                minWetY = Math.min(minWetY, wetY);
                maxWetY = Math.max(maxWetY, wetY);
                if (wetY != edgeWaterY) {
                    nearestDifferent = Math.min(nearestDifferent, manhattan);
                }
            }
        }
        if (nearestDifferent == Integer.MAX_VALUE || maxWetY <= minWetY) {
            return Integer.MIN_VALUE;
        }

        // Risk is continuous rather than a binary "near any step" switch. High mountain rapids
        // receive the strongest visual containment; lower headwaters may still qualify when the
        // step is immediately adjacent. Mature lowland reaches remain flush even if a one-block
        // quantisation crossing happens to fall nearby, preventing isolated artificial lips.
        double mountain = smoothstep(72.0D, MOUNTAIN_RAPID_EDGE_FULL_WATER_Y, edgeWaterY);
        double headwater = 1.0D - smoothstep(0.10D, 0.28D, river.progress());
        double lowHeadwater = smoothstep(HEADWATER_RAPID_EDGE_MIN_WATER_Y,
                MOUNTAIN_RAPID_EDGE_FULL_WATER_Y, edgeWaterY) * headwater;
        double hydraulicContext = Math.max(mountain, lowHeadwater);

        double proximity = 1.0D - smoothstep(1.0D, RAPID_EDGE_CONTEXT_REACH,
                nearestDifferent);
        // A single one-block hydraulic step is already the full visual condition we are trying
        // to contain. Do not halve its score merely because the local range is only one block.
        double stepSeverity = clamp(maxWetY - minWetY, 0.0D, 1.0D);
        double edgeRisk = hydraulicContext * (0.55D + 0.45D * proximity) * stepSeverity;

        // A one-block natural lip is useful only when risk is genuinely high. The threshold also
        // leaves ordinary lowland banks flush, while high-altitude rapid edges can remain
        // continuous through dynamic/static wet-column alternation.
        if (edgeRisk < 0.52D) {
            return Integer.MIN_VALUE;
        }
        return Math.min(naturalSurface, edgeWaterY + 1);
    }

    /** Package-private diagnostic used by the standalone audit. */
    static double rapidEdgeRisk(int worldX, int worldZ, int naturalSurface,
                                int bankSurface, RiverData.RiverSample river) {
        if (!river.corridor() || river.water() || river.distanceFromWater() > DYNAMIC_SPILL_EDGE_REACH) {
            return 0.0D;
        }
        int edgeWaterY = Integer.MIN_VALUE;
        boolean adjacentWet = false;
        for (int[] offset : CARDINAL_NEIGHBOURS) {
            RiverData.RiverSample wet = RiverData.sampleWorld(worldX + offset[0], worldZ + offset[1]);
            if (!wet.corridor() || !wet.water() || !wet.name().equals(river.name())) continue;
            adjacentWet = true;
            edgeWaterY = Math.max(edgeWaterY, Math.min(MAX_WATER_SURFACE_Y, wet.waterSurfaceY()));
        }
        if (!adjacentWet || naturalSurface < edgeWaterY + 1 || bankSurface >= edgeWaterY + 1) return 0.0D;
        int minWetY = edgeWaterY, maxWetY = edgeWaterY, nearestDifferent = Integer.MAX_VALUE;
        for (int dz = -RAPID_EDGE_CONTEXT_REACH; dz <= RAPID_EDGE_CONTEXT_REACH; dz++) {
            for (int dx = -RAPID_EDGE_CONTEXT_REACH; dx <= RAPID_EDGE_CONTEXT_REACH; dx++) {
                int manhattan = Math.abs(dx) + Math.abs(dz);
                if (manhattan == 0 || manhattan > RAPID_EDGE_CONTEXT_REACH) continue;
                RiverData.RiverSample wet = RiverData.sampleWorld(worldX + dx, worldZ + dz);
                if (!wet.corridor() || !wet.water() || !wet.name().equals(river.name())) continue;
                int wetY = Math.min(MAX_WATER_SURFACE_Y, wet.waterSurfaceY());
                minWetY = Math.min(minWetY, wetY); maxWetY = Math.max(maxWetY, wetY);
                if (wetY != edgeWaterY) nearestDifferent = Math.min(nearestDifferent, manhattan);
            }
        }
        if (nearestDifferent == Integer.MAX_VALUE || maxWetY <= minWetY) return 0.0D;
        double mountain = smoothstep(72.0D, MOUNTAIN_RAPID_EDGE_FULL_WATER_Y, edgeWaterY);
        double headwater = 1.0D - smoothstep(0.10D, 0.28D, river.progress());
        double lowHeadwater = smoothstep(HEADWATER_RAPID_EDGE_MIN_WATER_Y,
                MOUNTAIN_RAPID_EDGE_FULL_WATER_Y, edgeWaterY) * headwater;
        double hydraulicContext = Math.max(mountain, lowHeadwater);
        double proximity = 1.0D - smoothstep(1.0D, RAPID_EDGE_CONTEXT_REACH, nearestDifferent);
        // A single one-block hydraulic step is already the full visual condition we are trying
        // to contain. Do not halve its score merely because the local range is only one block.
        double stepSeverity = clamp(maxWetY - minWetY, 0.0D, 1.0D);
        return hydraulicContext * (0.55D + 0.45D * proximity) * stepSeverity;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0D, Math.min(1.0D, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0D - 2.0D * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
