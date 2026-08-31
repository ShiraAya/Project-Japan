package com.sora.projectjapan.worldgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W05 official route prefixes for SOURCE_FROM_LAKE topology repairs.
 *
 * <p>The generated data is deliberately separate from {@link SourceLakeOutletResolver}: LR-12
 * identifies the source-lake outlet evidence, while this resource preserves the actual W05 route
 * between that shoreline and the upstream end of the authored target river.  It is used only for
 * illegal land -> source lake -> land geometry.  Ordinary source-lake routes continue to use the
 * much smaller last-exit trim.</p>
 */
final class SourceLakeOfficialRouteResolver {
    private static final String RESOURCE =
            "/assets/projectjapan/hydrology/generated/source_lake_official_routes_v1.csv";
    private static final Map<String, Route> ROUTES = load();

    record Point(int x, int z) {}
    record Route(String river, String lake, String outletRiverId, String targetRiverId,
                 List<Point> points, int graphRadius, double routeLength,
                 double maximumRawChord, int targetEntrySequence, String entityChain) {}

    private SourceLakeOfficialRouteResolver() {}

    static Route resolve(String river, String lake, String authoredOfficialIdentifier) {
        Route route = ROUTES.get(key(river, lake));
        if (route == null) return null;
        String targetCode = riverCode(authoredOfficialIdentifier);
        if (!targetCode.isEmpty() && !riverCode(route.targetRiverId()).equals(targetCode)) return null;
        return route;
    }

    static List<Route> routes() { return List.copyOf(ROUTES.values()); }

    private static Map<String, Route> load() {
        try (InputStream raw = SourceLakeOfficialRouteResolver.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("Missing resource " + RESOURCE);
            Map<String, Builder> builders = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
                String line = reader.readLine(); // header
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] c = parseCsv(line);
                    if (c.length < 12) throw new IOException("Invalid source-lake official route row: " + line);
                    String river = c[0], lake = c[1];
                    Builder builder = builders.computeIfAbsent(key(river, lake), ignored ->
                            new Builder(river, lake, c[2], c[3], Integer.parseInt(c[7]),
                                    Double.parseDouble(c[8]), Double.parseDouble(c[9]),
                                    Integer.parseInt(c[10]), c[11]));
                    builder.points.add(new IndexedPoint(Integer.parseInt(c[4]),
                            new Point(Integer.parseInt(c[5]), Integer.parseInt(c[6]))));
                }
            }
            Map<String, Route> result = new LinkedHashMap<>();
            for (Map.Entry<String, Builder> entry : builders.entrySet()) {
                Builder b = entry.getValue();
                b.points.sort(Comparator.comparingInt(IndexedPoint::sequence));
                List<Point> points = b.points.stream().map(IndexedPoint::point).toList();
                if (points.size() < 2) throw new IOException("Official source-lake prefix too short: " + entry.getKey());
                result.put(entry.getKey(), new Route(b.river, b.lake, b.outletRiverId,
                        b.targetRiverId, List.copyOf(points), b.graphRadius, b.routeLength,
                        b.maximumRawChord, b.targetEntrySequence, b.entityChain));
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String key(String river, String lake) { return river + "\u0000" + lake; }

    private static String riverCode(String id) {
        if (id == null) return "";
        String[] parts = id.split(":", 5);
        return parts.length >= 4 ? parts[2] : "";
    }

    private record IndexedPoint(int sequence, Point point) {}
    private static final class Builder {
        final String river, lake, outletRiverId, targetRiverId, entityChain;
        final int graphRadius, targetEntrySequence;
        final double routeLength, maximumRawChord;
        final List<IndexedPoint> points = new ArrayList<>();
        Builder(String river, String lake, String outletRiverId, String targetRiverId,
                int graphRadius, double routeLength, double maximumRawChord,
                int targetEntrySequence, String entityChain) {
            this.river = river; this.lake = lake; this.outletRiverId = outletRiverId;
            this.targetRiverId = targetRiverId; this.graphRadius = graphRadius;
            this.routeLength = routeLength; this.maximumRawChord = maximumRawChord;
            this.targetEntrySequence = targetEntrySequence;
            this.entityChain = entityChain;
        }
    }

    private static String[] parseCsv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                values.add(current.toString()); current.setLength(0);
            } else current.append(ch);
        }
        values.add(current.toString());
        return values.toArray(String[]::new);
    }
}
