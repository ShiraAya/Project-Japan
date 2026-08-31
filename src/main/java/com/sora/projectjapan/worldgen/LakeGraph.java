package com.sora.projectjapan.worldgen;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Runtime topology graph for the RFIX-03 frozen 205 active waterbodies.
 *
 * <p>The original 223-member candidate pool remains in retention metadata. This graph contains
 * only 33 authored + 172 active retained nodes. RFIX-06 further separates connector topology definitions from runtime activation in LakeConnectorData.
 * The Urabandai three-lake follow-up keeps W05 topology evidence but suppresses all runtime
 * waterways attached to Hibara / Onogawa / Akimoto; those lakes render as independent owners.</p>
 */
public final class LakeGraph {
    private static final String RESOURCE =
            "/assets/projectjapan/hydrology/generated/lake_graph.bin.gz";
    private static final int MAGIC = 0x504A4752; // PJGR
    private static final int VERSION = 1;

    private static final GraphData DATA = load();
    private static final Map<String, Node> BY_ID = indexNodes(DATA.nodes);

    private LakeGraph() {}

    public static List<Node> nodes() { return DATA.nodes; }
    public static List<Edge> edges() { return DATA.edges; }
    public static List<ConnectorDefinition> connectors() { return DATA.connectors; }
    public static Node node(String runtimeLakeId) { return BY_ID.get(runtimeLakeId); }
    public static int lakeNodeCount() { return DATA.nodes.size(); }
    public static int connectorOnlyCount() { return DATA.connectors.size(); }

    private static Map<String, Node> indexNodes(List<Node> nodes) {
        Map<String, Node> result = new HashMap<>();
        for (Node node : nodes) {
            if (result.put(node.runtimeLakeId(), node) != null) {
                throw new IllegalStateException("Duplicate LakeGraph node: " + node.runtimeLakeId());
            }
        }
        return Map.copyOf(result);
    }

    private static GraphData load() {
        try (InputStream raw = LakeGraph.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("Missing resource " + RESOURCE);
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(new GZIPInputStream(raw)))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid LakeGraph magic");
                if (in.readInt() != VERSION) throw new IOException("Unsupported LakeGraph version");

                int nodeCount = readCount(in, 10_000, "nodes");
                List<Node> nodes = new ArrayList<>(nodeCount);
                for (int i = 0; i < nodeCount; i++) {
                    String runtimeId = readString(in);
                    String officialId = readString(in);
                    String name = readString(in);
                    boolean authored = in.readUnsignedByte() != 0;
                    WaterbodyType type = WaterbodyType.fromCode(in.readUnsignedByte());
                    boolean coastal = in.readUnsignedByte() != 0;
                    nodes.add(new Node(runtimeId, officialId, name, authored, type, coastal,
                            readStringList(in), readStringList(in)));
                }

                int edgeCount = readCount(in, 100_000, "edges");
                List<Edge> edges = new ArrayList<>(edgeCount);
                for (int i = 0; i < edgeCount; i++) {
                    EdgeType type = EdgeType.fromCode(in.readUnsignedByte());
                    boolean mandatory = in.readUnsignedByte() != 0;
                    boolean existing = in.readUnsignedByte() != 0;
                    edges.add(new Edge(type, readString(in), readString(in), readString(in),
                            readString(in), readString(in), mandatory, existing));
                }

                int connectorCount = readCount(in, 10_000, "connectors");
                List<ConnectorDefinition> connectors = new ArrayList<>(connectorCount);
                for (int i = 0; i < connectorCount; i++) {
                    String connectorId = readString(in);
                    ConnectorKind kind = ConnectorKind.fromCode(in.readUnsignedByte());
                    ConnectorBatch batch = ConnectorBatch.fromCode(in.readUnsignedByte());
                    String riverId = readString(in);
                    String riverName = readString(in);
                    String sourceLakeId = readString(in);
                    String targetLakeId = readString(in);
                    double startWidth = in.readFloat();
                    double endWidth = in.readFloat();
                    int pointCount = readCount(in, 100_000, "connector points");
                    List<Point> route = new ArrayList<>(pointCount);
                    for (int p = 0; p < pointCount; p++) route.add(new Point(in.readInt(), in.readInt()));
                    connectors.add(new ConnectorDefinition(connectorId, kind, batch, riverId, riverName,
                            sourceLakeId, targetLakeId, startWidth, endWidth, List.copyOf(route)));
                }

                if (nodeCount != 205 || connectorCount != 13) {
                    throw new IOException("LakeGraph freeze count mismatch nodes=" + nodeCount
                            + " connectors=" + connectorCount);
                }
                return new GraphData(List.copyOf(nodes), List.copyOf(edges), List.copyOf(connectors));
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int readCount(DataInputStream in, int maximum, String label) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid " + label + " count: " + count);
        return count;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readCount(in, 1 << 20, "string bytes");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> readStringList(DataInputStream in) throws IOException {
        int count = readCount(in, 100_000, "string-list");
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(readString(in));
        return List.copyOf(result);
    }

    public enum WaterbodyType {
        AUTHORED, LAKE, LAGOON, RESERVOIR, COASTAL_WATERBODY;
        private static WaterbodyType fromCode(int code) throws IOException {
            return switch (code) {
                case 0 -> AUTHORED;
                case 1 -> LAKE;
                case 2 -> LAGOON;
                case 3 -> RESERVOIR;
                case 4 -> COASTAL_WATERBODY;
                default -> throw new IOException("Unknown LakeGraph waterbody type " + code);
            };
        }
    }

    public enum EdgeType {
        EXISTING_LAKE_TO_RIVER, EXISTING_RIVER_TO_LAKE, MANDATORY_INLET,
        MANDATORY_OUTLET, LAKE_TO_LAKE_CHANNEL, COASTAL_CHANNEL;
        private static EdgeType fromCode(int code) throws IOException {
            return switch (code) {
                case 0 -> EXISTING_LAKE_TO_RIVER;
                case 1 -> EXISTING_RIVER_TO_LAKE;
                case 2 -> MANDATORY_INLET;
                case 3 -> MANDATORY_OUTLET;
                case 4 -> LAKE_TO_LAKE_CHANNEL;
                case 5 -> COASTAL_CHANNEL;
                default -> throw new IOException("Unknown LakeGraph edge type " + code);
            };
        }
    }

    public enum ConnectorKind {
        INLET, OUTLET, LAKE_CHANNEL;
        private static ConnectorKind fromCode(int code) throws IOException {
            return switch (code) {
                case 0 -> INLET;
                case 1 -> OUTLET;
                case 2 -> LAKE_CHANNEL;
                default -> throw new IOException("Unknown LakeGraph connector kind " + code);
            };
        }
    }

    public enum ConnectorBatch {
        G_05_A, G_06_B;
        private static ConnectorBatch fromCode(int code) throws IOException {
            return switch (code) {
                case 0 -> G_05_A;
                case 1 -> G_06_B;
                default -> throw new IOException("Unknown LakeGraph connector batch " + code);
            };
        }
    }

    public record Node(String runtimeLakeId, String officialLakeId, String name,
                       boolean authoredOverride, WaterbodyType waterbodyType,
                       boolean coastalConnection, List<String> inletRiverIds,
                       List<String> outletRiverIds) {}

    public record Edge(EdgeType type, String sourceLakeId, String targetLakeId,
                       String riverId, String riverName, String runtimeRiverName,
                       boolean mandatory, boolean existing) {}

    public record Point(int x, int z) {}

    public record ConnectorDefinition(String connectorId, ConnectorKind kind,
                                      ConnectorBatch batch, String riverId, String riverName,
                                      String sourceLakeId, String targetLakeId,
                                      double startWidth, double endWidth, List<Point> route) {}

    private record GraphData(List<Node> nodes, List<Edge> edges,
                             List<ConnectorDefinition> connectors) {}
}
