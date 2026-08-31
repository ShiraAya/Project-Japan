package com.sora.projectjapan.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public final class PJChunkGenerator extends ChunkGenerator {
    public static final int MIN_Y = -256;
    public static final int WORLD_HEIGHT = 2256;
    public static final int MAX_Y_EXCLUSIVE = MIN_Y + WORLD_HEIGHT;
    public static final int SEA_LEVEL = 0;
    /** Preserve the former representative 128 m shelf depth under the new vertical scale. */
    private static final int OCEAN_DEPTH = (int)Math.round(
            128.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    private static final int ELEVATED_LAKE_BLOCKS = (int)Math.round(
            60.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    private static final int LAKE_RIVER_APRON_GUARD_BLOCKS = (int)Math.round(
            128.0D / TerrainData.VERTICAL_METRES_PER_BLOCK);
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
    private static final BlockState SOURCE_WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState FALLING_WATER = SOURCE_WATER.setValue(LiquidBlock.LEVEL, 8);

    public static final Codec<PJChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PJBiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.pjBiomeSource)
    ).apply(instance, PJChunkGenerator::new));

    private final PJBiomeSource pjBiomeSource;

    public PJChunkGenerator(PJBiomeSource biomeSource) {
        super(biomeSource);
        this.pjBiomeSource = biomeSource;
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures,
                                                     RandomState randomState, long seed) {
        return ChunkGeneratorStructureState.createForFlat(randomState, seed, biomeSource, Stream.empty());
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures,
                             RandomState randomState, ChunkAccess chunk) {
        // SF-01: geometry is already frozen by fillFromNoise. Repaint only the final dry
        // topsoil here so river/lake bank carving and shoreline blending are reflected by the
        // visible palette. Water, bed geometry and hydraulic state are never changed here.
        paintFinalSurface(chunk);
    }

    private static void paintFinalSurface(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                ColumnProfile profile = columnProfile(worldX, worldZ);
                if (!isFinalDrySurface(profile)) continue;

                SurfaceLayers layers = finalSurfaceLayers(profile, worldX, worldZ);
                int surfaceY = profile.groundY();
                if (surfaceY <= MIN_Y || surfaceY >= MAX_Y_EXCLUSIVE) continue;

                chunk.setBlockState(pos.set(worldX, surfaceY, worldZ), layers.top(), false);
                int bottomY = Math.max(MIN_Y + 1, surfaceY - layers.depth());
                for (int y = surfaceY - 1; y >= bottomY; y--) {
                    // A final dry profile is authoritative for these soil layers. This does not
                    // touch any water column or bed because wet profiles are excluded above.
                    chunk.setBlockState(pos.set(worldX, y, worldZ), layers.subsurface(), false);
                }
            }
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender,
                                                         RandomState randomState,
                                                         StructureManager structures,
                                                         ChunkAccess chunk) {
        fillChunk(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    /** Package-private entry point for the standalone final terrain+surface CPU benchmark. */
    static void fillChunkForBenchmark(ChunkAccess chunk) {
        fillChunk(chunk);
        paintFinalSurface(chunk);
    }

    @SuppressWarnings("deprecation")
    private static void fillChunk(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        LevelChunkSection[] sections = chunk.getSections();
        int baseSectionIndex = chunk.getSectionIndex(MIN_Y);
        int highestTouchedY = MIN_Y;
        ColumnPlan[] plans = new ColumnPlan[16 * 16];
        int fullStoneThroughY = MAX_Y_EXCLUSIVE;

        // Plan all columns first. With no caves, ores or underground structures, any complete
        // section below every column's subsurface is exactly one block state: stone.
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                ColumnProfile profile = columnProfile(worldX, worldZ);
                SurfaceLayers layers = surfaceLayers(profile, worldX, worldZ);
                plans[(localX << 4) | localZ] = new ColumnPlan(profile, layers);
                fullStoneThroughY = Math.min(fullStoneThroughY,
                        profile.groundY() - layers.depth() - 1);
            }
        }

        int partialStoneStartY = MIN_Y + 16;
        for (int index = baseSectionIndex + 1; index < sections.length; index++) {
            int sectionTopY = MIN_Y + ((index - baseSectionIndex) << 4) + 15;
            if (sectionTopY > fullStoneThroughY) break;
            PalettedContainer<BlockState> stoneStates = new PalettedContainer<>(
                    Block.BLOCK_STATE_REGISTRY, STONE,
                    PalettedContainer.Strategy.SECTION_STATES);
            sections[index] = new LevelChunkSection(stoneStates, sections[index].getBiomes());
            partialStoneStartY = sectionTopY + 1;
        }

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkPos.getMinBlockX() + localX;
                int worldZ = chunkPos.getMinBlockZ() + localZ;
                ColumnPlan plan = plans[(localX << 4) | localZ];
                ColumnProfile profile = plan.profile();
                SurfaceLayers layers = plan.layers();
                BlockState groundTop = profile.groundY() == MIN_Y ? BEDROCK : layers.top();

                // Write directly to the palette and rebuild the section counters once after all
                // 256 columns. LevelChunkSection#setBlockState otherwise inspects the old/new
                // block and fluid state for every stone block in tall mountain columns.
                writeVertical(sections, baseSectionIndex, localX, localZ,
                        MIN_Y, MIN_Y, BEDROCK);
                writeVertical(sections, baseSectionIndex, localX, localZ,
                        MIN_Y + 1, Math.min(MIN_Y + 15,
                                profile.groundY() - layers.depth() - 1), STONE);
                writeVertical(sections, baseSectionIndex, localX, localZ,
                        partialStoneStartY,
                        profile.groundY() - layers.depth() - 1, STONE);
                writeVertical(sections, baseSectionIndex, localX, localZ,
                        Math.max(MIN_Y + 1, profile.groundY() - layers.depth()),
                        profile.groundY() - 1, layers.subsurface());
                writeRaw(sections, baseSectionIndex, localX, profile.groundY(),
                        localZ, groundTop);

                BlockState worldTop = groundTop;
                if (profile.hasWater()) {
                    int waterStart = profile.groundY() + 1;
                    int fallingStart = profile.river().fallingConnector()
                            ? Math.max(waterStart, profile.river().lowerWaterY() + 1)
                            : profile.waterTopY() + 1;
                    writeVertical(sections, baseSectionIndex, localX, localZ,
                            waterStart, Math.min(profile.waterTopY(), fallingStart - 1),
                            SOURCE_WATER);
                    writeVertical(sections, baseSectionIndex, localX, localZ,
                            fallingStart, profile.waterTopY(), FALLING_WATER);
                    worldTop = waterState(profile, profile.waterTopY());

                    // Palette writes do not call LiquidBlock#onPlace and therefore never start
                    // fluid ticks. Mark only terrace lips and generated waterfall columns.
                    if (profile.river().needsFluidPostProcessing()) {
                        int postProcessStart = Math.max(waterStart,
                                profile.river().lowerWaterY());
                        for (int y = postProcessStart; y <= profile.waterTopY(); y++) {
                            chunk.markPosForPostprocessing(pos.set(localX, y, localZ));
                        }
                    }
                }
                highestTouchedY = Math.max(highestTouchedY,
                        profile.hasWater() ? profile.waterTopY() : profile.groundY());

                // Direct section writes avoid ProtoChunk#setBlockState updating both generation
                // heightmaps for every stone block. A heightmap only needs the highest matching
                // state in each column, so update the ocean floor and visible surface once here.
                oceanFloor.update(localX, profile.groundY(), localZ, groundTop);
                int worldTopY = profile.hasWater() ? profile.waterTopY() : profile.groundY();
                worldSurface.update(localX, worldTopY, localZ, worldTop);
            }
        }

        int highestSectionIndex = chunk.getSectionIndex(highestTouchedY);
        for (int index = baseSectionIndex; index <= highestSectionIndex; index++) {
            sections[index].recalcBlockCounts();
        }
    }

    private static void writeVertical(LevelChunkSection[] sections, int baseSectionIndex,
                                      int x, int z, int startY, int endY,
                                      BlockState state) {
        int y = startY;
        while (y <= endY) {
            int sectionIndex = baseSectionIndex + ((y - MIN_Y) >> 4);
            PalettedContainer<BlockState> states = sections[sectionIndex].getStates();
            int sectionEndY = Math.min(endY, y | 15);
            for (; y <= sectionEndY; y++) {
                states.getAndSetUnchecked(x, y & 15, z, state);
            }
        }
    }

    private static void writeRaw(LevelChunkSection[] sections, int baseSectionIndex,
                                 int x, int y, int z, BlockState state) {
        int sectionIndex = baseSectionIndex + ((y - MIN_Y) >> 4);
        sections[sectionIndex].getStates().getAndSetUnchecked(x, y & 15, z, state);
    }

    private static BlockState waterState(ColumnProfile profile, int y) {
        RiverData.RiverSample river = profile.river();
        if (river.fallingConnector() && y > river.lowerWaterY()) {
            return FALLING_WATER;
        }
        return SOURCE_WATER;
    }

    private static RiverData.RiverSample runtimeRiverSample(int worldX, int worldZ, int naturalSurface) {
        RiverData.RiverSample river = RiverData.sampleWorld(worldX, worldZ, naturalSurface);
        // G/RFIX connector metadata and CF-FINAL gap closures stay outside the authored 115-course
        // registry, but must participate in the same terrain/vegetation ownership decisions.
        river = LakeConnectorData.mergeWithAuthored(river, worldX, worldZ);
        return FinalConnectionData.mergeWithExisting(river, worldX, worldZ);
    }

    private static ColumnProfile columnProfile(int worldX, int worldZ) {
        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(worldX, worldZ);
        int naturalSurface = terrainSurface(terrain);

        LakeData.LakeSample lake = LakeData.sampleWorld(worldX, worldZ);
        RiverData.RiverSample river = runtimeRiverSample(worldX, worldZ, naturalSurface);

        // Sea-level control geometry is deliberately allowed to cross the anti-aliased coast,
        // but open-ocean pixels must remain ocean rather than becoming a lake or a sand-ringed
        // artificial island.
        if (lake.water() && !lake.rendersWaterOn(terrain)) {
            lake = LakeData.LakeSample.NONE;
        }

        // Fix3 shoreline gate state is resolved independently from bathymetry.  It is also
        // available on the wet shoreline pixels so the final gate floor can stay one-block
        // continuous while the bank/apron suppression below remains dry-corridor only.
        LakeRiverShorelineAperture.Result shorelineAperture =
                LakeRiverShorelineAperture.resolve(lake, river);

        // CF-LAKE-UNION: a connected river/lake is one water footprint, not two overlapping
        // bathymetry masks.  The old implementation stopped blending exactly at river.water(),
        // which left two submerged river-bank ridges inside the lake.  Keep the river thalweg in
        // the core, then fade it laterally for 12..32 blocks *outside* the wet river edge while a
        // separate longitudinal weight fades the trench into/out of the lake.
        LakeRiverBathymetryUnion.Result lakeUnion = LakeRiverBathymetryUnion.resolve(lake, river);
        if (lakeUnion.active()) {
            int unionBed = shorelineAperture.active()
                    ? shorelineAperture.gateBedY() : lakeUnion.bedY();
            int bedY = Math.max(MIN_Y + 1, Math.min(lakeUnion.waterY() - 1, unionBed));
            return new ColumnProfile(terrain, river, lake, bedY, lakeUnion.waterY(),
                    SurfaceKind.RIVER_BED);
        }

        if (lake.water()) {
            int waterY = Math.min(MAX_Y_EXCLUSIVE - 80, lake.waterSurfaceY());
            int lakeBed = shorelineAperture.active()
                    ? shorelineAperture.gateBedY() : lake.bedY();
            int bedY = Math.max(MIN_Y + 1, Math.min(waterY - 1, lakeBed));
            return new ColumnProfile(terrain, RiverData.RiverSample.NONE, lake, bedY,
                    waterY, SurfaceKind.LAKE_BED);
        }

        // A river unrelated to an elevated lake must never tunnel beneath the lake's support
        // apron. The high-resolution DEM can still undersample a steep lake rim or outlet at a few columns.
        // The lake basin support therefore takes priority until the river route leaves the apron. Actual
        // named lake outlets/inlets are exempt and continue through the shoreline normally.
        if (river.water() && lake.corridor() && !lake.water()
                && !river.connectsLake(lake.name())
                && naturalSurface < lake.waterSurfaceY() - LAKE_RIVER_APRON_GUARD_BLOCKS) {
            int bankSurface = lakeBankSurface(naturalSurface, lake);
            bankSurface = Math.max(MIN_Y + 1,
                    Math.min(MAX_Y_EXCLUSIVE - 80, bankSurface));
            return new ColumnProfile(terrain, RiverData.RiverSample.NONE, lake, bankSurface,
                    Integer.MIN_VALUE, SurfaceKind.LAKE_BANK);
        }

        // CF-FINAL LakeUnion Fix3: the bathymetry union above deliberately remains lake.water()
        // only.  At the actual shoreline, open a short, width-scaled funnel through the lake bank
        // and apron instead of letting lakeBankSurface cross the river gate.  Outside this final
        // connection aperture the frozen LR shoreline is unchanged.
        if (shorelineAperture.active() && !river.water()) {
            int bankSurface = riverBankSurface(worldX, worldZ, naturalSurface, river);
            bankSurface = Math.max(MIN_Y + 1,
                    Math.min(MAX_Y_EXCLUSIVE - 80, bankSurface));
            return new ColumnProfile(terrain, river, LakeData.LakeSample.NONE, bankSurface,
                    Integer.MIN_VALUE, SurfaceKind.RIVER_BANK);
        }

        // A sea-level lagoon must keep a narrow continuous spit even where the anti-aliased
        // national land mask classifies the outside shore as ocean. Only a river explicitly linked to this lake
        // may cut an opening; unrelated course geometry cannot silently erase the barrier.
        if (lake.coastalBarrier(terrain)
                && !(river.water() && river.connectsLake(lake.name()))
                && !shorelineAperture.active()) {
            int barrierSurface = SEA_LEVEL + 1;
            return new ColumnProfile(terrain, RiverData.RiverSample.NONE, lake,
                    barrierSurface, Integer.MIN_VALUE, SurfaceKind.LAKE_BANK);
        }

        // River water wins over the shoreline corridor, so lake outlets/inlets physically cut an
        // opening through the old bank instead of stopping in a round pool beside the lake.
        if (river.water()) {
            boolean overOcean = !terrain.land();
            int waterY = overOcean ? SEA_LEVEL
                    : Math.min(MAX_Y_EXCLUSIVE - 80, river.waterSurfaceY());
            int generatedWaterTopY = overOcean ? SEA_LEVEL
                    : Math.min(MAX_Y_EXCLUSIVE - 80, river.generatedWaterTopY());
            int riverBed = shorelineAperture.active()
                    ? shorelineAperture.gateBedY() : river.bedY();
            int bedY = Math.max(MIN_Y + 1, Math.min(waterY - 1, riverBed));
            if (overOcean) bedY = Math.min(bedY, naturalSurface);
            return new ColumnProfile(terrain, river, LakeData.LakeSample.NONE, bedY,
                    generatedWaterTopY, SurfaceKind.RIVER_BED);
        }

        if (!terrain.land()) {
            return new ColumnProfile(terrain, RiverData.RiverSample.NONE,
                    LakeData.LakeSample.NONE, naturalSurface, SEA_LEVEL, SurfaceKind.OCEAN);
        }

        if (river.corridor() && lake.corridor()) {
            int riverBank = riverBankSurface(worldX, worldZ, naturalSurface, river);
            int lakeBank = lakeBankSurface(naturalSurface, lake);
            int shoreY = lake.waterSurfaceY();
            int bankSurface = naturalSurface < shoreY
                    ? Math.max(riverBank, lakeBank)
                    : Math.min(riverBank, lakeBank);
            bankSurface = Math.max(MIN_Y + 1,
                    Math.min(MAX_Y_EXCLUSIVE - 80, bankSurface));
            return new ColumnProfile(terrain, river, lake, bankSurface,
                    Integer.MIN_VALUE, SurfaceKind.RIVER_BANK);
        }

        if (river.corridor()) {
            int bankSurface = riverBankSurface(worldX, worldZ, naturalSurface, river);
            bankSurface = Math.max(MIN_Y + 1,
                    Math.min(MAX_Y_EXCLUSIVE - 80, bankSurface));
            return new ColumnProfile(terrain, river, LakeData.LakeSample.NONE, bankSurface,
                    Integer.MIN_VALUE, SurfaceKind.RIVER_BANK);
        }

        if (lake.corridor()) {
            int bankSurface = lakeBankSurface(naturalSurface, lake);
            bankSurface = Math.max(MIN_Y + 1,
                    Math.min(MAX_Y_EXCLUSIVE - 80, bankSurface));
            return new ColumnProfile(terrain, RiverData.RiverSample.NONE, lake, bankSurface,
                    Integer.MIN_VALUE, SurfaceKind.LAKE_BANK);
        }

        return new ColumnProfile(terrain, river, lake, naturalSurface,
                Integer.MIN_VALUE, SurfaceKind.NATURAL_LAND);
    }

    static int riverBankSurface(int worldX, int worldZ, int naturalSurface,
                                RiverData.RiverSample river) {
        return RiverBankTerrain.surface(worldX, worldZ, naturalSurface, river);
    }

    private static int lakeBankSurface(int naturalSurface,
                                      LakeData.LakeSample lake) {
        int shoreY = Math.min(MAX_Y_EXCLUSIVE - 80, lake.waterSurfaceY());
        int bankSurface = naturalSurface;
        if (naturalSurface < shoreY) {
            boolean elevatedLake = lake.waterSurfaceY() - SEA_LEVEL >= ELEVATED_LAKE_BLOCKS;
            double shoreInfluence = 1.0D - smoothstep(0.0D, 1.0D, lake.bankBlend());
            if (elevatedLake) {
                int supportFloor = shoreY
                        - (int)Math.floor(lake.distanceToShore() * lake.bankSlope());
                int raisedSurface = Math.max(naturalSurface, supportFloor);
                bankSurface = (int)Math.round(lerp(naturalSurface, raisedSurface,
                        shoreInfluence));
            } else if (lake.distanceToShore() <= 24.0D) {
                double influence = 1.0D - smoothstep(0.0D, 24.0D,
                        lake.distanceToShore());
                bankSurface = (int)Math.round(lerp(naturalSurface, shoreY, influence));
            }
        } else if (naturalSurface > shoreY) {
            int allowedCeiling = shoreY
                    + (int)Math.floor(lake.distanceToShore() * lake.bankSlope());
            // Both highland and lowland lakes use a continuous shore envelope. The corridor width
            // is computed from surrounding terrain in LakeData, so this envelope reaches the
            // untouched surface before it ends. The old lowland-only 40-block lowering cap left a
            // 70-block wall at Lake Shinji even though the lake itself was correctly positioned.
            int carvedSurface = Math.min(naturalSurface, allowedCeiling);
            double shoreInfluence = 1.0D - smoothstep(0.0D, 1.0D, lake.bankBlend());
            bankSurface = (int)Math.round(lerp(naturalSurface, carvedSurface,
                    shoreInfluence));
        }
        if (lake.distanceToShore() <= 2.0D && naturalSurface < shoreY) {
            bankSurface = Math.max(bankSurface, shoreY);
        }
        return bankSurface;
    }

    private static int terrainSurface(TerrainData.TerrainSample sample) {
        double coverage = sample.landCoverage();
        if (!sample.land()) {
            // Ocean floor rises gradually from 64 blocks deep to a two-block coastal shelf.
            double coast = smoothstep(0.02D, 0.50D, coverage);
            int depth = 2 + (int)Math.round((OCEAN_DEPTH - 2) * (1.0D - coast));
            return SEA_LEVEL - depth;
        }

        // The first strip of land rises from sea level instead of forming a vertical dirt wall.
        double coastRise = smoothstep(0.50D, 0.84D, coverage);
        int elevationBlocks = Math.max(1, (int)Math.round(
                sample.elevationMetres() / TerrainData.VERTICAL_METRES_PER_BLOCK * coastRise));
        return Math.min(MAX_Y_EXCLUSIVE - 64, SEA_LEVEL + elevationBlocks);
    }

    /** Computes the only non-stone ground states once per column. */
    private static SurfaceLayers surfaceLayers(ColumnProfile profile, int x, int z) {
        return switch (profile.kind()) {
            case LAKE_BED -> new SurfaceLayers(
                    lakeBedTop(x, z, profile.lake().depth()),
                    lakeBedSubsurface(profile.lake().depth()), 2);
            case LAKE_BANK -> profile.lake().coastalBarrier(profile.terrain())
                    ? new SurfaceLayers(SAND, SAND, 3)
                    : naturalLandLayers(profile.terrain(), x, z);
            case RIVER_BANK -> naturalLandLayers(profile.terrain(), x, z);
            case RIVER_BED -> new SurfaceLayers(
                    profile.river().estuary() ? estuaryBedTop(x, z) : riverBedTop(x, z),
                    profile.river().estuary() ? GRAVEL : riverBedSubsurface(x, z), 2);
            case NATURAL_LAND, OCEAN -> {
                int oceanDepth = SEA_LEVEL - profile.groundY();
                boolean beach = profile.terrain().land()
                        && profile.terrain().landCoverage() < 0.58D
                        && profile.groundY() <= SEA_LEVEL + 2;
                SurfaceLayers landLayers = profile.terrain().land()
                        ? naturalLandLayers(profile.terrain(), x, z) : null;
                BlockState top = !profile.terrain().land()
                        ? oceanFloorTop(x, z, oceanDepth)
                        : beach ? SAND : landLayers.top();
                BlockState subsurface = !profile.terrain().land()
                        ? oceanFloorSubsurface(oceanDepth)
                        : beach ? SAND : landLayers.subsurface();
                yield new SurfaceLayers(top, subsurface, 3);
            }
        };
    }

    /**
     * Deterministic regional surface palette. Low city plains stay build-friendly grass, while
     * forest mountains, alpine rock and the climatic snow line no longer share one green skin.
     */
    private static SurfaceLayers naturalLandLayers(TerrainData.TerrainSample terrain,
                                                    int x, int z) {
        // Legacy NOISE-stage paint retained temporarily for SF correctness-first staging.
        return elevationPaletteLayers(terrain.elevationMetres(), x, z);
    }

    /** SF-02 palette input: final ground height after all bank/carve/blend geometry. */
    private static SurfaceLayers finalDryLandLayers(ColumnProfile profile, int x, int z) {
        return elevationPaletteLayers(finalElevationMetres(profile), x, z);
    }

    private static SurfaceLayers elevationPaletteLayers(int elevation, int x, int z) {
        double latitude = TerrainData.TOKYO_LAT
                - z * TerrainData.HORIZONTAL_METRES_PER_BLOCK / TerrainData.METRES_PER_DEGREE_LAT;
        double snowLine = clamp(2_550.0D - Math.max(0.0D, latitude - 30.0D) * 82.0D,
                1_450.0D, 2_550.0D);
        int selector = (int)Math.floorMod(mixCoordinates(x, z), 100L);

        if (elevation >= snowLine + 120.0D) {
            if (selector < 76) return new SurfaceLayers(Blocks.SNOW_BLOCK.defaultBlockState(),
                    STONE, 2);
            return exposedRockLayers(selector);
        }
        if (elevation >= snowLine - 160.0D) {
            if (selector < 28) return new SurfaceLayers(Blocks.SNOW_BLOCK.defaultBlockState(),
                    STONE, 2);
            if (selector < 82) return exposedRockLayers(selector);
            return new SurfaceLayers(Blocks.COARSE_DIRT.defaultBlockState(), DIRT, 3);
        }
        if (elevation >= 1_650) {
            if (selector < 62) return exposedRockLayers(selector);
            if (selector < 86) return new SurfaceLayers(Blocks.PODZOL.defaultBlockState(),
                    DIRT, 3);
            return new SurfaceLayers(Blocks.COARSE_DIRT.defaultBlockState(), DIRT, 3);
        }
        if (elevation >= 850) {
            if (selector < 18) return exposedRockLayers(selector);
            if (selector < 62) return new SurfaceLayers(Blocks.PODZOL.defaultBlockState(),
                    DIRT, 3);
            if (selector < 78) return new SurfaceLayers(Blocks.COARSE_DIRT.defaultBlockState(), DIRT, 3);
        } else if (elevation >= 420 || (latitude >= 41.5D && elevation >= 220)) {
            if (selector < 24) return new SurfaceLayers(Blocks.PODZOL.defaultBlockState(),
                    DIRT, 3);
            if (selector < 34) return new SurfaceLayers(Blocks.COARSE_DIRT.defaultBlockState(),
                    DIRT, 3);
        }
        return new SurfaceLayers(GRASS_BLOCK, DIRT, 3);
    }

    private static int finalElevationMetres(ColumnProfile profile) {
        return (int)Math.round((profile.groundY() - SEA_LEVEL)
                * TerrainData.VERTICAL_METRES_PER_BLOCK);
    }

    private static boolean isFinalDrySurface(ColumnProfile profile) {
        if (profile.hasWater()) return false;
        return profile.kind() == SurfaceKind.NATURAL_LAND
                || profile.kind() == SurfaceKind.RIVER_BANK
                || profile.kind() == SurfaceKind.LAKE_BANK;
    }

    private static boolean isFinalOceanBeach(ColumnProfile profile) {
        return profile.kind() == SurfaceKind.NATURAL_LAND
                && profile.terrain().land()
                && profile.terrain().landCoverage() < 0.58D
                && profile.groundY() <= SEA_LEVEL + 2;
    }

    /** SF-01/SF-03/SF-04 final material decision. Wet beds remain owned by their bed palettes. */
    private static SurfaceLayers finalSurfaceLayers(ColumnProfile profile, int x, int z) {
        return switch (profile.kind()) {
            case LAKE_BED, RIVER_BED, OCEAN -> surfaceLayers(profile, x, z);
            case LAKE_BANK -> profile.lake().coastalBarrier(profile.terrain())
                    ? new SurfaceLayers(SAND, SAND, 3)
                    : finalDryLandLayers(profile, x, z);
            case RIVER_BANK -> finalDryLandLayers(profile, x, z);
            case NATURAL_LAND -> isFinalOceanBeach(profile)
                    ? new SurfaceLayers(SAND, SAND, 3)
                    : finalDryLandLayers(profile, x, z);
        };
    }

    private static SurfaceLayers exposedRockLayers(int selector) {
        BlockState rock = selector % 10 < 5 ? STONE
                : selector % 10 < 8 ? Blocks.ANDESITE.defaultBlockState()
                : selector % 10 == 8 ? Blocks.TUFF.defaultBlockState()
                : GRAVEL;
        BlockState below = rock.is(Blocks.GRAVEL) ? STONE : rock;
        return new SurfaceLayers(rock, below, 2);
    }

    private static BlockState groundState(ColumnProfile profile, SurfaceLayers layers, int y) {
        if (y == MIN_Y) return BEDROCK;
        int surfaceY = profile.groundY();
        if (y == surfaceY) return layers.top();
        if (y >= surfaceY - layers.depth()) return layers.subsurface();
        return STONE;
    }

    private static BlockState lakeBedTop(int x, int z, int depth) {
        long hash = mixCoordinates(x + 73, z - 29);
        int selector = (int)Math.floorMod(hash, 12L);
        if (depth <= 3) return selector < 8
                ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        if (depth <= 16) {
            if (selector < 5) return Blocks.GRAVEL.defaultBlockState();
            if (selector < 9) return Blocks.CLAY.defaultBlockState();
            return Blocks.SAND.defaultBlockState();
        }
        if (selector < 5) return Blocks.CLAY.defaultBlockState();
        if (selector < 9) return Blocks.GRAVEL.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState lakeBedSubsurface(int depth) {
        if (depth <= 4) return Blocks.SAND.defaultBlockState();
        if (depth <= 20) return Blocks.GRAVEL.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState riverBedTop(int x, int z) {
        long hash = mixCoordinates(x, z);
        int selector = (int)Math.floorMod(hash, 10L);
        if (selector < 5) return Blocks.GRAVEL.defaultBlockState();
        if (selector < 8) return Blocks.SAND.defaultBlockState();
        return Blocks.CLAY.defaultBlockState();
    }

    private static BlockState riverBedSubsurface(int x, int z) {
        return Math.floorMod(mixCoordinates(x + 31, z - 17), 4L) == 0L
                ? Blocks.CLAY.defaultBlockState()
                : Blocks.GRAVEL.defaultBlockState();
    }

    private static BlockState estuaryBedTop(int x, int z) {
        int selector = (int)Math.floorMod(mixCoordinates(x - 19, z + 43), 10L);
        if (selector < 5) return Blocks.GRAVEL.defaultBlockState();
        if (selector < 8) return Blocks.CLAY.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState oceanFloorTop(int x, int z, int depth) {
        if (depth <= 10) return Blocks.SAND.defaultBlockState();
        if (depth <= 28) {
            return Math.floorMod(mixCoordinates(x, z), 5L) == 0L
                    ? Blocks.CLAY.defaultBlockState()
                    : Blocks.GRAVEL.defaultBlockState();
        }
        return Math.floorMod(mixCoordinates(x, z), 7L) == 0L
                ? Blocks.GRAVEL.defaultBlockState()
                : Blocks.STONE.defaultBlockState();
    }

    private static BlockState oceanFloorSubsurface(int depth) {
        if (depth <= 10) return Blocks.SAND.defaultBlockState();
        if (depth <= 28) return Blocks.GRAVEL.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private static long mixCoordinates(int x, int z) {
        long value = ((long)x * 341873128712L) ^ ((long)z * 132897987541L);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
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

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // Skip the passive creature pack normally created with a new chunk.
        // Runtime NATURAL spawns are rejected separately by PJMobEvents.
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structures,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Deliberately empty: no caves, ravines, fissures or underground caverns.
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structures) {
        // PJ deliberately does not call the vanilla decoration pipeline. Only the sparse,
        // controlled vegetation below is generated, so lava lakes, ores and structures remain absent.
        ChunkPos chunkPos = chunk.getPos();
        long decorationSeed = level.getSeed()
                ^ ((long)chunkPos.x * 341873128712L)
                ^ ((long)chunkPos.z * 132897987541L)
                ^ 0x504A56454745544CL;
        RandomSource random = RandomSource.create(decorationSeed);

        placeSparseTrees(level, chunkPos, random);
        placeGroundPlants(level, chunkPos, random);
    }

    private static void placeSparseTrees(WorldGenLevel level, ChunkPos chunkPos, RandomSource random) {
        int probeX = chunkPos.getMinBlockX() + 8;
        int probeZ = chunkPos.getMinBlockZ() + 8;
        ColumnProfile centre = columnProfile(probeX, probeZ);
        int elevation = finalElevationMetres(centre);
        if (elevation > 2400) return;

        float chance;
        if (elevation < 120) chance = 0.20F;       // keep city plains open
        else if (elevation < 700) chance = 0.36F;
        else if (elevation < 1700) chance = 0.46F;
        else chance = 0.18F;                       // sparse near the tree line

        if (random.nextFloat() < chance) {
            tryPlaceTree(level, chunkPos, random);
            if (random.nextFloat() < 0.08F) tryPlaceTree(level, chunkPos, random);
        }
    }

    private static void tryPlaceTree(WorldGenLevel level, ChunkPos chunkPos, RandomSource random) {
        // Keep the canopy inside the owning chunk so neighbouring chunks generate independently.
        int x = chunkPos.getMinBlockX() + 3 + random.nextInt(10);
        int z = chunkPos.getMinBlockZ() + 3 + random.nextInt(10);
        ColumnProfile profile = columnProfile(x, z);
        if (!isFinalDrySurface(profile)) return;
        int elevation = finalElevationMetres(profile);
        if (elevation > 2400) return;

        SurfaceLayers finalLayers = finalSurfaceLayers(profile, x, z);
        if (!supportsOrdinaryTree(finalLayers.top())) return;
        if (finalLayers.top().is(Blocks.COARSE_DIRT) && random.nextFloat() >= 0.25F) return;
        // Keep trunks out of the immediate wet edge without excluding the wider dry bank/blend.
        if (profile.river().corridor() && profile.river().distanceFromWater() <= 2.0D) return;
        if (profile.lake().corridor() && profile.lake().distanceToShore() <= 2.0D) return;

        int baseY = profile.groundY() + 1;
        if (baseY + 9 >= MAX_Y_EXCLUSIVE) return;

        TreePalette palette = chooseTreePalette(random, elevation);
        int trunkHeight = palette == TreePalette.SPRUCE ? 6 + random.nextInt(3) : 4 + random.nextInt(3);
        if (!hasTreeSpace(level, x, baseY, z, trunkHeight, palette)) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        level.setBlock(pos.set(x, baseY - 1, z), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);

        if (palette == TreePalette.SPRUCE) {
            placeSpruceLeaves(level, x, baseY, z, trunkHeight, palette.leaves());
        } else {
            placeRoundLeaves(level, x, baseY, z, trunkHeight, palette.leaves());
        }

        for (int dy = 0; dy < trunkHeight; dy++) {
            level.setBlock(pos.set(x, baseY + dy, z), palette.log().defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static boolean hasTreeSpace(WorldGenLevel level, int x, int baseY, int z,
                                        int trunkHeight, TreePalette palette) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState ground = level.getBlockState(pos.set(x, baseY - 1, z));
        if (!supportsOrdinaryTree(ground)) return false;

        int top = baseY + trunkHeight;
        int canopyStart = palette == TreePalette.SPRUCE ? top - 4 : top - 2;
        for (int y = baseY; y <= top + 1; y++) {
            int radius = y < canopyStart ? 0 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!level.getBlockState(pos.set(x + dx, y, z + dz)).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static void placeRoundLeaves(WorldGenLevel level, int x, int baseY, int z,
                                         int trunkHeight, Block leavesBlock) {
        BlockState leaves = leavesBlock.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = baseY + trunkHeight;

        for (int dy = -2; dy <= 0; dy++) {
            int radius = dy == 0 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    setLeavesIfAir(level, pos.set(x + dx, top + dy, z + dz), leaves);
                }
            }
        }
        setLeavesIfAir(level, pos.set(x, top + 1, z), leaves);
    }

    private static void placeSpruceLeaves(WorldGenLevel level, int x, int baseY, int z,
                                          int trunkHeight, Block leavesBlock) {
        BlockState leaves = leavesBlock.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = baseY + trunkHeight;
        for (int dy = -4; dy <= 1; dy++) {
            int radius = switch (dy) {
                case -4, -2 -> 2;
                case -3, -1 -> 1;
                default -> 0;
            };
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    setLeavesIfAir(level, pos.set(x + dx, top + dy, z + dz), leaves);
                }
            }
        }
    }

    private static void setLeavesIfAir(WorldGenLevel level, BlockPos pos, BlockState leaves) {
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, leaves, Block.UPDATE_CLIENTS);
        }
    }

    private static TreePalette chooseTreePalette(RandomSource random, int elevationMetres) {
        if (elevationMetres > 900 && random.nextFloat() < 0.55F) return TreePalette.SPRUCE;
        float roll = random.nextFloat();
        if (elevationMetres < 500 && roll < 0.14F) return TreePalette.CHERRY;
        if (roll < 0.43F) return TreePalette.BIRCH;
        return TreePalette.OAK;
    }

    private static void placeGroundPlants(WorldGenLevel level, ChunkPos chunkPos, RandomSource random) {
        int centreX = chunkPos.getMinBlockX() + 8;
        int centreZ = chunkPos.getMinBlockZ() + 8;
        ColumnProfile centre = columnProfile(centreX, centreZ);
        int centreElevation = finalElevationMetres(centre);
        if (centreElevation > 2600) return;

        int grassAttempts = centreElevation < 300 ? 10 + random.nextInt(9) : 6 + random.nextInt(7);
        for (int i = 0; i < grassAttempts; i++) {
            int x = chunkPos.getMinBlockX() + random.nextInt(16);
            int z = chunkPos.getMinBlockZ() + random.nextInt(16);
            placePlant(level, x, z, Blocks.GRASS.defaultBlockState());
        }

        int flowerAttempts = random.nextInt(4);
        for (int i = 0; i < flowerAttempts; i++) {
            int x = chunkPos.getMinBlockX() + random.nextInt(16);
            int z = chunkPos.getMinBlockZ() + random.nextInt(16);
            BlockState flower = switch (random.nextInt(5)) {
                case 0 -> Blocks.POPPY.defaultBlockState();
                case 1 -> Blocks.AZURE_BLUET.defaultBlockState();
                case 2 -> Blocks.OXEYE_DAISY.defaultBlockState();
                case 3 -> Blocks.CORNFLOWER.defaultBlockState();
                default -> Blocks.DANDELION.defaultBlockState();
            };
            placePlant(level, x, z, flower);
        }
    }

    private static void placePlant(WorldGenLevel level, int x, int z, BlockState plant) {
        ColumnProfile profile = columnProfile(x, z);
        if (!isFinalDrySurface(profile) || finalElevationMetres(profile) > 2600) return;
        int y = profile.groundY() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState ground = level.getBlockState(pos.setY(y - 1));
        if (!supportsOrdinaryGroundPlant(ground, plant, x, z)) return;
        pos.setY(y);
        if (plant.canSurvive(level, pos)) {
            level.setBlock(pos, plant, Block.UPDATE_CLIENTS);
        }
    }

    private static boolean supportsOrdinaryTree(BlockState ground) {
        return ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT)
                || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT);
    }

    private static boolean supportsOrdinaryGroundPlant(BlockState ground, BlockState plant,
                                                        int x, int z) {
        if (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT)) return true;
        if (!ground.is(Blocks.PODZOL)) return false;
        // Podzol remains vegetated but much more sparsely; coarse dirt/rock/snow/sand stay bare.
        long salt = plant.is(Blocks.GRASS) ? 0x47524153534CL : 0x464C4F5745524CL;
        return Math.floorMod(mixCoordinates(x, z) ^ salt, plant.is(Blocks.GRASS) ? 4L : 7L) == 0L;
    }

    private enum TreePalette {
        OAK(Blocks.OAK_LOG, Blocks.OAK_LEAVES),
        BIRCH(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES),
        CHERRY(Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES),
        SPRUCE(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);

        private final Block log;
        private final Block leaves;

        TreePalette(Block log, Block leaves) {
            this.log = log;
            this.leaves = leaves;
        }

        public Block log() { return log; }
        public Block leaves() { return leaves; }
    }

    private enum SurfaceKind {
        NATURAL_LAND,
        OCEAN,
        LAKE_BED,
        LAKE_BANK,
        RIVER_BED,
        RIVER_BANK
    }

    private record SurfaceLayers(BlockState top, BlockState subsurface, int depth) {}

    private record ColumnPlan(ColumnProfile profile, SurfaceLayers layers) {}

    /** Package-private generated-column view used by hydrology containment validation. */
    static ValidationColumn validationColumn(int worldX, int worldZ) {
        ColumnProfile profile = columnProfile(worldX, worldZ);
        SurfaceLayers layers = finalSurfaceLayers(profile, worldX, worldZ);
        return new ValidationColumn(profile.groundY(), profile.waterTopY(),
                profile.hasWater(), profile.river().corridor(), profile.river().water(),
                profile.river().needsFluidPostProcessing(),
                profile.river().fallingConnector(), profile.river().name(),
                profile.river().waterSurfaceY(), profile.river().upperWaterY(),
                profile.river().lowerWaterY(), profile.river().distanceToCentre(),
                profile.river().halfWaterWidth(), profile.river().distanceFromWater(),
                profile.river().terraceDrop(),
                profile.river().progress(), profile.kind().name(),
                profile.river().bankReferenceWaterY(),
                layers.top().getBlock().getDescriptionId());
    }

    record ValidationColumn(int groundY, int waterTopY, boolean hasWater, boolean riverCorridor,
                            boolean riverWater, boolean dynamicRiverWater,
                            boolean fallingConnector, String riverName,
                            int waterSurfaceY, int upperWaterY, int lowerWaterY,
                            double distanceToCentre, double halfWaterWidth,
                            double distanceFromWater, boolean terraceDrop,
                            double progress, String surfaceKind,
                            int bankReferenceWaterY, String surfaceBlock) {}

    /** Detailed SF validation view: old DEM-derived terrain vs final generated surface context. */
    static FinalSurfaceValidation finalSurfaceValidation(int worldX, int worldZ) {
        ColumnProfile profile = columnProfile(worldX, worldZ);
        SurfaceLayers initial = surfaceLayers(profile, worldX, worldZ);
        SurfaceLayers fin = finalSurfaceLayers(profile, worldX, worldZ);
        return new FinalSurfaceValidation(worldX, worldZ, terrainSurface(profile.terrain()),
                profile.groundY(), profile.terrain().elevationMetres(),
                finalElevationMetres(profile), profile.kind().name(), profile.hasWater(),
                profile.river().water(), profile.lake().water(),
                profile.river().corridor(), profile.lake().corridor(),
                profile.river().distanceFromWater(), profile.lake().distanceToShore(),
                profile.river().bankBlend(), profile.lake().bankBlend(),
                initial.top().getBlock().getDescriptionId(),
                fin.top().getBlock().getDescriptionId(),
                fin.subsurface().getBlock().getDescriptionId(),
                isFinalDrySurface(profile), isFinalOceanBeach(profile),
                supportsOrdinaryTree(fin.top()),
                supportsOrdinaryGroundPlant(fin.top(), Blocks.GRASS.defaultBlockState(), worldX, worldZ),
                supportsOrdinaryGroundPlant(fin.top(), Blocks.DANDELION.defaultBlockState(), worldX, worldZ));
    }

    record FinalSurfaceValidation(int x, int z, int oldTerrainY, int finalGroundY,
                                  int oldElevationMetres, int finalElevationMetres,
                                  String surfaceKind, boolean hasWater,
                                  boolean riverWater, boolean lakeWater,
                                  boolean riverCorridor, boolean lakeCorridor,
                                  double distanceToRiverWater, double distanceToLakeShore,
                                  double riverBlendWeight, double lakeBlendWeight,
                                  String initialTopBlock, String finalTopBlock,
                                  String finalSubsurfaceBlock, boolean drySurface,
                                  boolean oceanBeach, boolean treeMaterialEligible,
                                  boolean grassMaterialEligible, boolean flowerMaterialEligible) {}

    private record ColumnProfile(TerrainData.TerrainSample terrain,
                                 RiverData.RiverSample river,
                                 LakeData.LakeSample lake,
                                 int groundY, int waterTopY,
                                 SurfaceKind kind) {
        private boolean hasWater() {
            return waterTopY >= groundY + 1;
        }
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState randomState) {
        ColumnProfile profile = columnProfile(x, z);
        if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) {
            return profile.groundY() + 1;
        }
        return profile.hasWater() ? profile.waterTopY() + 1 : profile.groundY() + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState randomState) {
        ColumnProfile profile = columnProfile(x, z);
        SurfaceLayers layers = finalSurfaceLayers(profile, x, z);
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        int min = level.getMinBuildHeight();
        for (int y = Math.max(MIN_Y, min);
             y <= profile.groundY() && y < level.getMaxBuildHeight(); y++) {
            states[y - min] = groundState(profile, layers, y);
        }
        if (profile.hasWater()) {
            for (int y = profile.groundY() + 1;
                 y <= profile.waterTopY() && y < level.getMaxBuildHeight(); y++) {
                states[y - min] = waterState(profile, y);
            }
        }
        return new NoiseColumn(min, states);
    }

    @Override public int getSeaLevel() { return SEA_LEVEL; }
    @Override public int getMinY() { return MIN_Y; }
    @Override public int getGenDepth() { return WORLD_HEIGHT; }
    @Override public int getSpawnHeight(LevelHeightAccessor level) {
        ColumnProfile spawn = columnProfile(0, 0);
        return spawn.hasWater() ? spawn.waterTopY() + 1 : spawn.groundY() + 1;
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
        ColumnProfile profile = columnProfile(pos.getX(), pos.getZ());
        TerrainData.TerrainSample sample = profile.terrain();
        int coastPercent = (int)Math.round(sample.landCoverage() * 100.0D);
        if (profile.lake().corridor()) {
            lines.add("PJ lake: " + profile.lake().name()
                    + ", water Y=" + profile.lake().waterSurfaceY()
                    + (profile.lake().water()
                    ? ", depth " + profile.lake().depth() + "/"
                    + profile.lake().maximumDepth()
                    : ", shore"));
        } else if (profile.river().corridor()) {
            lines.add("PJ river: " + profile.river().name()
                    + ", width " + profile.river().waterWidth()
                    + ", water Y=" + profile.river().waterSurfaceY()
                    + (profile.river().water()
                    ? ", depth " + (profile.river().waterSurfaceY() - profile.river().bedY())
                    + "/" + profile.river().maximumDepth()
                    + (profile.river().fallingConnector()
                    ? ", falling " + profile.river().upperWaterY() + "->" + profile.river().lowerWaterY()
                    : profile.river().flowUpdate()
                    ? ", drop lip " + profile.river().upperWaterY() + "->" + profile.river().lowerWaterY()
                    : ", channel")
                    : ", natural bank"));
        } else {
            lines.add("PJ: " + (sample.land()
                    ? "land " + sample.elevationMetres() + "m / "
                    + Math.round(sample.elevationMetres() / TerrainData.VERTICAL_METRES_PER_BLOCK)
                    + " blocks, coast " + coastPercent + "%"
                    : "ocean, coast " + coastPercent + "%"));
        }
        lines.add("PJ hydrology: " + RiverData.courseCount() + " channels, "
                + LakeData.lakeCount() + " lakes");
    }
}
