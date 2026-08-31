package com.sora.projectjapan.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RFIX-04..06 runtime state layer for topology connector definitions.
 *
 * <p>LakeGraph keeps topology evidence. This class alone decides whether a definition is allowed
 * to own runtime water. A connector with a dangling land endpoint or an undeclared third-party
 * lake crossing remains PENDING_CONNECTOR and therefore produces no water / bed / bank geometry.</p>
 */
final class LakeConnectorData {
    private static final int GRID_SIZE = 512;
    private static final double BANK_MARGIN = 5.0D;
    private static final int PROXIMITY_SCAN_LIMIT = 4096;
    private static final int PROXIMITY_SCAN_STEP = 64;
    private static final Map<String, List<LakeData.LakeSpatialCandidate>> ACTIVE_LAKES_BY_NAME =
            buildActiveLakesByName();

    private static final List<Connector> CONNECTORS = LakeGraph.connectors().stream()
            .map(Connector::new).toList();
    // RFIX-19 indexes only connectors that are runtime-active when the worldgen state layer is
    // materialized. Effective state is still re-checked per indexed candidate, so a later visible
    // owner registration can disable an ACTIVE connector without duplicate water geometry.
    private static final List<Connector> INITIAL_RUNTIME_CONNECTORS = CONNECTORS.stream()
            .filter(connector -> connector.currentDecision().state == ConnectorState.ACTIVE_CONNECTOR_ONLY).toList();
    private static final Map<Long, List<Connector>> GRID = buildGrid(INITIAL_RUNTIME_CONNECTORS);

    private LakeConnectorData() {}

    /** Full topology registry count. Pending definitions stay visible here for later RFIX/P handoff. */
    static int connectorCount() { return CONNECTORS.size(); }
    static int activeConnectorCount() {
        return (int)CONNECTORS.stream().filter(c -> c.currentDecision().state == ConnectorState.ACTIVE_CONNECTOR_ONLY).count();
    }
    static int pendingConnectorCount() {
        return (int)CONNECTORS.stream().filter(c -> c.currentDecision().state == ConnectorState.PENDING_CONNECTOR).count();
    }
    static int promotedVisibleCount() {
        return (int)CONNECTORS.stream().filter(c -> c.currentDecision().state == ConnectorState.PROMOTED_VISIBLE).count();
    }


    /** RFIX-19 audit-only promotion preview; does not mutate the runtime visible-owner registry. */
    static ConnectorDecisionPreview previewStateWithSyntheticVisibleOwner(String officialIdentifier) {
        for (Connector connector : CONNECTORS) {
            if (!connector.definition.riverId().equals(officialIdentifier)) continue;
            StateDecision decision = connector.decideState(Set.of(officialIdentifier));
            return new ConnectorDecisionPreview(decision.state(), decision.reason());
        }
        throw new IllegalArgumentException("Unknown connector W05 identifier: " + officialIdentifier);
    }

    static boolean runtimeEnabled(String connectorId) {
        for (Connector connector : CONNECTORS) {
            if (connector.definition.connectorId().equals(connectorId)) {
                return connector.currentDecision().state == ConnectorState.ACTIVE_CONNECTOR_ONLY;
            }
        }
        return false;
    }

    static List<ConnectorAudit> auditSnapshots() {
        return CONNECTORS.stream().map(connector -> connector.audit(false)).toList();
    }

    /**
     * CF-02 current physical connector registry. PENDING_CONNECTOR entries are deliberately
     * topology intents only: they retain the frozen LakeGraph evidence needed by RFIX-19 but do
     * not represent a physical endpoint, water corridor or runtime stub during Connection Freeze.
     */
    static List<ConnectorAudit> physicalConnectionAuditSnapshots() {
        return CONNECTORS.stream()
                .filter(connector -> connector.currentDecision().state != ConnectorState.PENDING_CONNECTOR)
                .map(connector -> connector.audit(false)).toList();
    }

    /** RFIX-04 full proximity audit; kept separate so routine G/RFIX gates stay lightweight. */
    static List<ConnectorAudit> ownershipAuditSnapshots() {
        return CONNECTORS.stream().map(connector -> connector.audit(true)).toList();
    }

    static RiverData.RiverSample sampleWorld(int worldX, int worldZ) {
        RiverData.RiverSample best = RiverData.RiverSample.NONE;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Connector connector : candidates(worldX, worldZ)) {
            if (connector.currentDecision().state != ConnectorState.ACTIVE_CONNECTOR_ONLY) continue;
            if (!connector.mayContain(worldX, worldZ)) continue;
            Nearest nearest = connector.nearest(worldX, worldZ);
            if (nearest == null) continue;
            double progress = clamp(nearest.along / connector.length, 0.0D, 1.0D);
            double fullWidth = lerp(connector.startWidth, connector.endWidth, progress);
            double halfWater = Math.max(1.5D, fullWidth * 0.5D);
            double halfCorridor = halfWater + BANK_MARGIN;
            if (nearest.distance > halfCorridor) continue;
            boolean water = nearest.distance <= halfWater + 1.25D;
            int waterY = connector.waterY(progress);
            int depth = Math.max(1, (int)Math.round(clamp(2.0D + fullWidth * 0.18D, 2.0D, 5.0D)));
            double score = nearest.distance / halfCorridor;
            String lakeName = connector.connectedLakeName(progress);
            double lakeBlend = connector.lakeBlend(progress);
            RiverData.RiverSample sample = new RiverData.RiverSample(
                    true, water, connector.displayName, nearest.distance, halfWater, halfCorridor,
                    progress, waterY, waterY - depth, depth, false, false, false,
                    waterY, waterY, false, 0.28D, Math.max(0.0D, nearest.distance - halfWater),
                    lakeName, lakeBlend, 0.0D, "", waterY, 0.0D);
            if (sample.water() != best.water()) {
                if (sample.water()) { best = sample; bestScore = score; }
            } else if (score < bestScore) {
                best = sample; bestScore = score;
            }
        }
        return best;
    }

    private static RiverData.RiverSample merge(RiverData.RiverSample authored,
                                                RiverData.RiverSample connector) {
        if (!connector.corridor()) return authored;
        if (!authored.corridor()) return connector;
        if (authored.water()) return authored;
        if (connector.water()) return connector;
        return authored.distanceFromWater() <= connector.distanceFromWater() ? authored : connector;
    }

    static RiverData.RiverSample mergeWithAuthored(RiverData.RiverSample authored,
                                                    int worldX, int worldZ) {
        // RFIX-10: frozen topology owners are runtime water corridors but remain outside
        // RiverData's visible/authored course registry. Authored wet water always wins.
        RiverData.RiverSample withTopology = merge(authored, FrozenRiverTopologyData.sampleWorld(worldX, worldZ));
        return merge(withTopology, sampleWorld(worldX, worldZ));
    }

    private static List<Connector> candidates(int x, int z) {
        return GRID.getOrDefault(gridKey(Math.floorDiv(x, GRID_SIZE), Math.floorDiv(z, GRID_SIZE)), List.of());
    }

    private static Map<Long, List<Connector>> buildGrid(List<Connector> connectors) {
        Map<Long, List<Connector>> mutable = new HashMap<>();
        for (Connector connector : connectors) {
            int minX = Math.floorDiv((int)Math.floor(connector.minX), GRID_SIZE);
            int maxX = Math.floorDiv((int)Math.ceil(connector.maxX), GRID_SIZE);
            int minZ = Math.floorDiv((int)Math.floor(connector.minZ), GRID_SIZE);
            int maxZ = Math.floorDiv((int)Math.ceil(connector.maxZ), GRID_SIZE);
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
                mutable.computeIfAbsent(gridKey(cx, cz), ignored -> new ArrayList<>()).add(connector);
            }
        }
        Map<Long, List<Connector>> result = new HashMap<>();
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static long gridKey(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) return value >= edge1 ? 1.0D : 0.0D;
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static Map<String, List<LakeData.LakeSpatialCandidate>> buildActiveLakesByName() {
        Map<String, List<LakeData.LakeSpatialCandidate>> mutable = new HashMap<>();
        for (LakeData.LakeSpatialCandidate lake : LakeData.spatialAuditCandidates()) {
            if (!lake.active()) continue;
            mutable.computeIfAbsent(lake.name(), ignored -> new ArrayList<>()).add(lake);
        }
        Map<String, List<LakeData.LakeSpatialCandidate>> result = new HashMap<>();
        mutable.forEach((name, lakes) -> result.put(name, List.copyOf(lakes)));
        return Map.copyOf(result);
    }

    /** Resolve the actual rendered active lake owner without changing the frozen lake engine. */
    private static String activeLakeOwnerId(int worldX, int worldZ) {
        LakeData.LakeSample sample = LakeData.sampleWorld(worldX, worldZ);
        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(worldX, worldZ);
        if (!sample.water() || !sample.rendersWaterOn(terrain)) return "";
        for (LakeData.LakeSpatialCandidate lake : ACTIVE_LAKES_BY_NAME.getOrDefault(sample.name(), List.of())) {
            if (sample.waterbodyType() == LakeData.WaterbodyType.AUTHORED && !lake.authored()) continue;
            if (sample.waterbodyType() != LakeData.WaterbodyType.AUTHORED && lake.authored()) continue;
            LakeData.SpatialDistance distance = LakeData.spatialAuditDistance(lake.lakeId(), worldX, worldZ);
            if (distance.inside()) return lake.lakeId();
        }
        return "";
    }

    private static EndpointOwner classifyEndpoint(LakeGraph.Point point) {
        String lakeId = activeLakeOwnerId(point.x(), point.z());
        if (!lakeId.isEmpty()) {
            LakeData.LakeMetadata lake = LakeData.metadataById(lakeId);
            return new EndpointOwner(EndpointOwnerType.ACTIVE_LAKE, lakeId,
                    lake == null ? lakeId : lake.name(), lake == null ? Integer.MIN_VALUE : lake.waterSurfaceY());
        }
        String topologyOwnerId = FrozenRiverTopologyData.ownerIdAt(point.x(), point.z());
        if (!topologyOwnerId.isEmpty()) {
            return new EndpointOwner(EndpointOwnerType.FROZEN_TOPOLOGY_OWNER, topologyOwnerId,
                    topologyOwnerId, FrozenRiverTopologyData.waterYAt(point.x(), point.z()));
        }
        RiverData.RiverSample river = RiverData.sampleWorld(point.x(), point.z());
        if (river.water()) {
            return new EndpointOwner(EndpointOwnerType.AUTHORED_RIVER, "RIVER:" + river.name(),
                    river.name(), river.waterSurfaceY());
        }
        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(point.x(), point.z());
        if (!terrain.land()) {
            return new EndpointOwner(EndpointOwnerType.OCEAN, "OCEAN", "ocean",
                    PJChunkGenerator.SEA_LEVEL);
        }
        return new EndpointOwner(EndpointOwnerType.LAND, "", "land", Integer.MIN_VALUE);
    }

    private static LakeProximity nearestActiveLake(LakeGraph.Point point) {
        String bestId = "";
        String bestName = "";
        double best = Double.POSITIVE_INFINITY;
        for (LakeData.LakeSpatialCandidate lake : LakeData.spatialAuditCandidates()) {
            if (!lake.active()) continue;
            LakeData.SpatialDistance distance = LakeData.spatialAuditDistance(lake.lakeId(), point.x(), point.z());
            double value = distance.inside() ? 0.0D : distance.distanceToShore();
            if (value < best) {
                best = value;
                bestId = lake.lakeId();
                bestName = lake.name();
            }
        }
        return new LakeProximity(bestId, bestName, best);
    }

    /** RFIX-04 nearest frozen authored river from the bundled W05 reference geometry. */
    private static RiverProximity nearestAuthoredRiver(LakeGraph.Point point) {
        String bestName = "";
        double best = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, W05AuthoredReference.Route> entry : W05AuthoredReference.routesForAudit().entrySet()) {
            for (W05AuthoredReference.LatLon sample : entry.getValue().samples()) {
                int x = TerrainData.worldXFromLongitude(sample.longitude());
                int z = TerrainData.worldZFromLatitude(sample.latitude());
                double distance = Math.hypot(point.x() - x, point.z() - z);
                if (distance < best) {
                    best = distance;
                    bestName = entry.getKey();
                }
            }
        }
        return new RiverProximity(bestName, best);
    }

    private static double nearestOceanDistance(LakeGraph.Point point) {
        TerrainData.TerrainSample direct = TerrainData.sampleWorld(point.x(), point.z());
        if (!direct.land()) return 0.0D;
        double best = Double.POSITIVE_INFINITY;
        for (int radius = PROXIMITY_SCAN_STEP; radius <= PROXIMITY_SCAN_LIMIT; radius += PROXIMITY_SCAN_STEP) {
            for (int offset = -radius; offset <= radius; offset += PROXIMITY_SCAN_STEP) {
                best = probeOcean(point, radius, offset, best);
                best = probeOcean(point, -radius, offset, best);
                if (offset != -radius && offset != radius) {
                    best = probeOcean(point, offset, radius, best);
                    best = probeOcean(point, offset, -radius, best);
                }
            }
            if (Double.isFinite(best)) break;
        }
        return best;
    }

    private static double probeOcean(LakeGraph.Point origin, int dx, int dz, double best) {
        TerrainData.TerrainSample terrain = TerrainData.sampleWorld(origin.x() + dx, origin.z() + dz);
        if (terrain.land()) return best;
        return Math.min(best, Math.hypot(dx, dz));
    }

    private static final class Connector {
        final LakeGraph.ConnectorDefinition definition;
        final String displayName;
        final List<LakeGraph.Point> route;
        final double[] station;
        final double length;
        final double startWidth, endWidth;
        final int startY, endY;
        final int sourceOwnerY, targetOwnerY;
        final String sourceLakeName, targetLakeName;
        final EndpointOwner sourceEndpointOwner, targetEndpointOwner;
        final RouteOwnershipTrace ownershipTrace;
        final double minX, maxX, minZ, maxZ;

        Connector(LakeGraph.ConnectorDefinition definition) {
            this.definition = definition;
            this.displayName = "CONNECTOR_ONLY:" + (definition.riverName().isEmpty()
                    ? definition.riverId() : definition.riverName());
            this.route = definition.route();
            if (route.size() < 2) throw new IllegalStateException("Connector route too short: " + definition.connectorId());
            this.station = new double[route.size()];
            double total = 0.0D;
            double loX = Double.POSITIVE_INFINITY, hiX = Double.NEGATIVE_INFINITY;
            double loZ = Double.POSITIVE_INFINITY, hiZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < route.size(); i++) {
                LakeGraph.Point point = route.get(i);
                loX = Math.min(loX, point.x()); hiX = Math.max(hiX, point.x());
                loZ = Math.min(loZ, point.z()); hiZ = Math.max(hiZ, point.z());
                if (i > 0) {
                    LakeGraph.Point previous = route.get(i - 1);
                    total += Math.hypot(point.x() - previous.x(), point.z() - previous.z());
                }
                station[i] = total;
            }
            this.length = Math.max(1.0D, total);
            this.startWidth = definition.startWidth();
            this.endWidth = definition.endWidth();
            EndpointOwner sourceConfigured = resolveEndpointOwner(definition.sourceLakeId(), route.get(0));
            EndpointOwner targetConfigured = resolveEndpointOwner(definition.targetLakeId(), route.get(route.size() - 1));
            this.sourceLakeName = sourceConfigured.ownerName;
            this.targetLakeName = targetConfigured.ownerName;
            this.sourceOwnerY = sourceConfigured.waterY;
            this.targetOwnerY = targetConfigured.waterY;
            this.sourceEndpointOwner = classifyEndpoint(route.get(0));
            this.targetEndpointOwner = classifyEndpoint(route.get(route.size() - 1));
            this.ownershipTrace = auditRouteOwnership();
            StateDecision decision = currentDecision();
            this.startY = resolveStartY();
            this.endY = resolveEndY();
            if (endY > startY && decision.state == ConnectorState.ACTIVE_CONNECTOR_ONLY) {
                throw new IllegalStateException("ACTIVE_CONNECTOR_ONLY reverse profile: " + definition.connectorId()
                        + " " + startY + "->" + endY);
            }
            double pad = Math.max(startWidth, endWidth) * 0.5D + BANK_MARGIN + 4.0D;
            this.minX = loX - pad; this.maxX = hiX + pad;
            this.minZ = loZ - pad; this.maxZ = hiZ + pad;
        }

        private StateDecision currentDecision() {
            return decideState(Set.of());
        }

        private StateDecision decideState(Set<String> syntheticVisibleOwners) {
            if (VisibleRiverRegistry.hasVisibleOwner(definition.riverId(), syntheticVisibleOwners)) {
                return new StateDecision(ConnectorState.PROMOTED_VISIBLE,
                        ConnectorDecisionReason.PROMOTED_VISIBLE_OWNER);
            }
            boolean sourceFreeLand = definition.sourceLakeId().isEmpty()
                    && sourceEndpointOwner.type == EndpointOwnerType.LAND;
            boolean targetFreeLand = definition.targetLakeId().isEmpty()
                    && targetEndpointOwner.type == EndpointOwnerType.LAND;
            if (sourceFreeLand || targetFreeLand) {
                return new StateDecision(ConnectorState.PENDING_CONNECTOR,
                        ConnectorDecisionReason.BARE_LAND_FREE_ENDPOINT);
            }
            if (!endpointResolved(definition.sourceLakeId(), sourceEndpointOwner)
                    || !endpointResolved(definition.targetLakeId(), targetEndpointOwner)) {
                return new StateDecision(ConnectorState.PENDING_CONNECTOR,
                        ConnectorDecisionReason.ENDPOINT_OWNER_MISSING);
            }
            if (!ownershipTrace.thirdPartyLakeIds.isEmpty()) {
                return new StateDecision(ConnectorState.PENDING_CONNECTOR,
                        ConnectorDecisionReason.THIRD_PARTY_LAKE_CROSSING);
            }
            return new StateDecision(ConnectorState.ACTIVE_CONNECTOR_ONLY,
                    ConnectorDecisionReason.READY_BOTH_ENDPOINTS_OWNED);
        }

        private boolean endpointResolved(String configuredLakeId, EndpointOwner owner) {
            if (configuredLakeId != null && !configuredLakeId.isEmpty()) {
                return owner.type == EndpointOwnerType.ACTIVE_LAKE
                        && configuredLakeId.equals(owner.ownerId);
            }
            return owner.type == EndpointOwnerType.AUTHORED_RIVER
                    || owner.type == EndpointOwnerType.OCEAN
                    || owner.type == EndpointOwnerType.FROZEN_TOPOLOGY_OWNER;
        }

        private RouteOwnershipTrace auditRouteOwnership() {
            PhysicalContact sourceContact = PhysicalContact.NONE;
            PhysicalContact targetContact = PhysicalContact.NONE;
            Set<String> thirdParty = new LinkedHashSet<>();
            List<String> sequence = new ArrayList<>();
            String previousOwner = null;
            for (int i = 0; i + 1 < route.size(); i++) {
                LakeGraph.Point a = route.get(i), b = route.get(i + 1);
                double segmentLength = Math.hypot(b.x() - a.x(), b.z() - a.z());
                int steps = Math.max(1, (int)Math.ceil(segmentLength));
                for (int step = 0; step <= steps; step++) {
                    if (i > 0 && step == 0) continue;
                    double t = step / (double)steps;
                    int x = (int)Math.round(lerp(a.x(), b.x(), t));
                    int z = (int)Math.round(lerp(a.z(), b.z(), t));
                    double along = station[i] + segmentLength * t;
                    String owner = activeLakeOwnerId(x, z);
                    String sequenceOwner = owner.isEmpty() ? "LAND_OR_RIVER" : owner;
                    if (!sequenceOwner.equals(previousOwner)) {
                        sequence.add(sequenceOwner);
                        previousOwner = sequenceOwner;
                    }
                    if (!definition.sourceLakeId().isEmpty() && owner.equals(definition.sourceLakeId())) {
                        sourceContact = new PhysicalContact(true, x, z, along);
                    }
                    if (!definition.targetLakeId().isEmpty() && owner.equals(definition.targetLakeId())
                            && !targetContact.found) {
                        targetContact = new PhysicalContact(true, x, z, along);
                    }
                    if (!owner.isEmpty() && !owner.equals(definition.sourceLakeId())
                            && !owner.equals(definition.targetLakeId())) {
                        thirdParty.add(owner);
                    }
                }
            }
            return new RouteOwnershipTrace(sourceContact, targetContact,
                    List.copyOf(thirdParty), List.copyOf(sequence));
        }

        private int resolveStartY() {
            if (!definition.sourceLakeId().isEmpty()) return sourceOwnerY;
            int lakeY = targetOwnerY;
            RiverData.RiverSample topologyOwner = FrozenRiverTopologyData.sampleWorld(route.get(0).x(), route.get(0).z());
            if (topologyOwner.water()) return topologyOwner.waterSurfaceY();
            RiverData.RiverSample riverOwner = RiverData.sampleWorld(route.get(0).x(), route.get(0).z());
            if (riverOwner.water()) return riverOwner.waterSurfaceY();
            int natural = terrainSurface(route.get(0));
            int allowance = Math.max(1, (int)Math.floor(length / 64.0D));
            return Math.max(lakeY, Math.min(lakeY + allowance, natural - 1));
        }

        private int resolveEndY() {
            if (!definition.targetLakeId().isEmpty()) return targetOwnerY;
            int lakeY = sourceOwnerY;
            LakeGraph.Point end = route.get(route.size() - 1);
            RiverData.RiverSample topologyOwner = FrozenRiverTopologyData.sampleWorld(end.x(), end.z());
            if (topologyOwner.water()) return topologyOwner.waterSurfaceY();
            RiverData.RiverSample riverOwner = RiverData.sampleWorld(end.x(), end.z());
            if (riverOwner.water()) return riverOwner.waterSurfaceY();
            int natural = terrainSurface(end);
            int allowance = Math.max(1, (int)Math.floor(length / 64.0D));
            int terrainTarget = Math.min(lakeY, natural - 1);
            return Math.max(PJChunkGenerator.SEA_LEVEL, Math.max(lakeY - allowance, terrainTarget));
        }

        private static EndpointOwner resolveEndpointOwner(String configuredLakeId, LakeGraph.Point point) {
            if (configuredLakeId == null || configuredLakeId.isEmpty()) {
                return new EndpointOwner(EndpointOwnerType.NONE, "", "", Integer.MIN_VALUE);
            }
            String physicalId = activeLakeOwnerId(point.x(), point.z());
            if (!physicalId.isEmpty()) {
                LakeData.LakeMetadata physical = LakeData.metadataById(physicalId);
                return new EndpointOwner(EndpointOwnerType.ACTIVE_LAKE, physicalId,
                        physical == null ? physicalId : physical.name(),
                        physical == null ? Integer.MIN_VALUE : physical.waterSurfaceY());
            }
            return new EndpointOwner(EndpointOwnerType.FROZEN_TOPOLOGY_OWNER, configuredLakeId,
                    LakeData.nameById(configuredLakeId), LakeData.waterSurfaceYById(configuredLakeId));
        }

        private static int terrainSurface(LakeGraph.Point point) {
            TerrainData.TerrainSample terrain = TerrainData.sampleWorld(point.x(), point.z());
            if (!terrain.land()) return PJChunkGenerator.SEA_LEVEL;
            double coastRise = smoothstep(0.50D, 0.84D, terrain.landCoverage());
            return Math.max(1, (int)Math.round(terrain.elevationMetres()
                    / TerrainData.VERTICAL_METRES_PER_BLOCK * coastRise));
        }

        ConnectorAudit audit(boolean includeProximity) {
            int configuredSourceY = definition.sourceLakeId().isEmpty() ? Integer.MIN_VALUE
                    : LakeData.waterSurfaceYById(definition.sourceLakeId());
            int configuredTargetY = definition.targetLakeId().isEmpty() ? Integer.MIN_VALUE
                    : LakeData.waterSurfaceYById(definition.targetLakeId());
            LakeData.LakeSample startPhysical = LakeData.sampleWorld(route.get(0).x(), route.get(0).z());
            LakeData.LakeSample endPhysical = LakeData.sampleWorld(
                    route.get(route.size() - 1).x(), route.get(route.size() - 1).z());
            int maximumRise = 0;
            int maximumDrop = 0;
            int previous = waterY(0.0D);
            int samples = Math.max(2, (int)Math.ceil(length) + 1);
            for (int i = 1; i < samples; i++) {
                double progress = i / (double)(samples - 1);
                int current = waterY(progress);
                maximumRise = Math.max(maximumRise, current - previous);
                maximumDrop = Math.max(maximumDrop, previous - current);
                previous = current;
            }
            EndpointAudit sourceAudit = endpointAudit(route.get(0), sourceEndpointOwner,
                    definition.sourceLakeId().isEmpty(), includeProximity);
            EndpointAudit targetAudit = endpointAudit(route.get(route.size() - 1), targetEndpointOwner,
                    definition.targetLakeId().isEmpty(), includeProximity);
            StateDecision decision = currentDecision();
            return new ConnectorAudit(definition.connectorId(), definition.kind(), definition.batch(), decision.state, decision.reason,
                    definition.sourceLakeId(), definition.targetLakeId(), sourceLakeName, targetLakeName,
                    configuredSourceY, configuredTargetY, startY, endY, maximumRise, maximumDrop, length,
                    startWidth, endWidth, startPhysical.water(), endPhysical.water(),
                    startPhysical.name(), endPhysical.name(), startPhysical.waterSurfaceY(),
                    endPhysical.waterSurfaceY(), sourceAudit, targetAudit,
                    ownershipTrace.sourceContact, ownershipTrace.targetContact,
                    ownershipTrace.thirdPartyLakeIds, ownershipTrace.ownerSequence);
        }

        private static EndpointAudit endpointAudit(LakeGraph.Point point, EndpointOwner owner,
                                                   boolean freeEndpoint, boolean includeProximity) {
            if (!includeProximity) {
                return new EndpointAudit(point.x(), point.z(), freeEndpoint, owner.type, owner.ownerId,
                        owner.ownerName, owner.waterY, "", Double.POSITIVE_INFINITY, "", "",
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
            RiverProximity river = nearestAuthoredRiver(point);
            LakeProximity lake = nearestActiveLake(point);
            double ocean = nearestOceanDistance(point);
            return new EndpointAudit(point.x(), point.z(), freeEndpoint, owner.type, owner.ownerId,
                    owner.ownerName, owner.waterY, river.riverName, river.distanceBlocks,
                    lake.lakeId, lake.lakeName, lake.distanceBlocks, ocean);
        }

        boolean mayContain(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        Nearest nearest(int x, int z) {
            Nearest best = null;
            for (int i = 0; i + 1 < route.size(); i++) {
                LakeGraph.Point a = route.get(i), b = route.get(i + 1);
                double dx = b.x() - a.x(), dz = b.z() - a.z();
                double denom = dx * dx + dz * dz;
                double t = denom <= 0.0D ? 0.0D
                        : clamp(((x - a.x()) * dx + (z - a.z()) * dz) / denom, 0.0D, 1.0D);
                double px = a.x() + dx * t, pz = a.z() + dz * t;
                double distance = Math.hypot(x - px, z - pz);
                double along = station[i] + Math.sqrt(denom) * t;
                if (best == null || distance < best.distance) best = new Nearest(distance, along);
            }
            return best;
        }

        int waterY(double progress) {
            double value = lerp(startY, endY, clamp(progress, 0.0D, 1.0D));
            return (int)Math.round(value);
        }

        String connectedLakeName(double progress) {
            if (!sourceLakeName.isEmpty() && progress <= 0.18D) return sourceLakeName;
            if (!targetLakeName.isEmpty() && progress >= 0.82D) return targetLakeName;
            return "";
        }

        double lakeBlend(double progress) {
            if (!sourceLakeName.isEmpty() && progress <= 0.18D) {
                return 1.0D - smoothstep(0.0D, 0.18D, progress);
            }
            if (!targetLakeName.isEmpty() && progress >= 0.82D) {
                return smoothstep(0.82D, 1.0D, progress);
            }
            return 0.0D;
        }
    }

    enum ConnectorState { PENDING_CONNECTOR, ACTIVE_CONNECTOR_ONLY, PROMOTED_VISIBLE }
    enum ConnectorDecisionReason {
        READY_BOTH_ENDPOINTS_OWNED, BARE_LAND_FREE_ENDPOINT, ENDPOINT_OWNER_MISSING,
        THIRD_PARTY_LAKE_CROSSING, PROMOTED_VISIBLE_OWNER
    }
    enum EndpointOwnerType { NONE, LAND, ACTIVE_LAKE, AUTHORED_RIVER, OCEAN, FROZEN_TOPOLOGY_OWNER }

    record EndpointAudit(int x, int z, boolean freeEndpoint, EndpointOwnerType ownerType,
                         String ownerId, String ownerName, int waterY,
                         String nearestAuthoredRiver, double nearestAuthoredRiverDistance,
                         String nearestActiveLakeId, String nearestActiveLake,
                         double nearestActiveLakeDistance, double nearestOceanDistance) {}

    record PhysicalContact(boolean found, int x, int z, double stationBlocks) {
        static final PhysicalContact NONE = new PhysicalContact(false, 0, 0, Double.NaN);
    }

    record ConnectorDecisionPreview(ConnectorState state, ConnectorDecisionReason reason) {}

    record ConnectorAudit(String connectorId, LakeGraph.ConnectorKind kind, LakeGraph.ConnectorBatch batch,
                          ConnectorState state, ConnectorDecisionReason decisionReason,
                          String sourceLakeId, String targetLakeId,
                          String sourceOwnerName, String targetOwnerName,
                          int configuredSourceY, int configuredTargetY,
                          int startY, int endY, int maximumRise, int maximumDrop, double lengthBlocks,
                          double startWidth, double endWidth, boolean startPhysicalWater,
                          boolean endPhysicalWater, String startPhysicalName, String endPhysicalName,
                          int startPhysicalY, int endPhysicalY, EndpointAudit sourceEndpoint,
                          EndpointAudit targetEndpoint, PhysicalContact sourcePhysicalContact,
                          PhysicalContact targetPhysicalContact, List<String> thirdPartyLakeIds,
                          List<String> physicalOwnerSequence) {}

    private record StateDecision(ConnectorState state, ConnectorDecisionReason reason) {}
    private record EndpointOwner(EndpointOwnerType type, String ownerId, String ownerName, int waterY) {}
    private record RouteOwnershipTrace(PhysicalContact sourceContact, PhysicalContact targetContact,
                                       List<String> thirdPartyLakeIds, List<String> ownerSequence) {}
    private record RiverProximity(String riverName, double distanceBlocks) {}
    private record LakeProximity(String lakeId, String lakeName, double distanceBlocks) {}
    private record Nearest(double distance, double along) {}
}
