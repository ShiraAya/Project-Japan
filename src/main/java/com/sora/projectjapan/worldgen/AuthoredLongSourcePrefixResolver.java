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

/** Deterministic ordered W05 prefixes for >1024-block source corrections. */
final class AuthoredLongSourcePrefixResolver {
    private static final String RESOURCE =
            "/assets/projectjapan/hydrology/generated/authored_long_source_routes_v1.csv";
    private static final Map<String, Route> ROUTES = load();

    record Point(int x, int z) {}
    record Route(String river, String officialIdentifier, List<Point> points,
                 double sourceShift, double routeLength, double maximumRawChord,
                 double joinDistance, int graphRadius) {}

    private AuthoredLongSourcePrefixResolver() {}

    static Route resolve(String river, String officialIdentifier) {
        Route route = ROUTES.get(river);
        if (route == null) return null;
        if (officialIdentifier != null && !officialIdentifier.isEmpty()
                && !officialIdentifier.equals(route.officialIdentifier())) return null;
        return route;
    }

    private static Map<String, Route> load() {
        try (InputStream raw = AuthoredLongSourcePrefixResolver.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("Missing resource " + RESOURCE);
            Map<String, Builder> builders = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] c = line.split(",", -1);
                    if (c.length < 10) throw new IOException("Invalid long-source route row: " + line);
                    Builder b = builders.computeIfAbsent(c[0], ignored -> new Builder(c[0], c[1],
                            Double.parseDouble(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7]),
                            Double.parseDouble(c[8]), Integer.parseInt(c[9])));
                    b.points.add(new IndexedPoint(Integer.parseInt(c[2]),
                            new Point(Integer.parseInt(c[3]), Integer.parseInt(c[4]))));
                }
            }
            Map<String, Route> out = new LinkedHashMap<>();
            for (Builder b : builders.values()) {
                b.points.sort(Comparator.comparingInt(IndexedPoint::sequence));
                List<Point> points = b.points.stream().map(IndexedPoint::point).toList();
                if (points.size() < 2) throw new IOException("Long-source route too short: " + b.river);
                out.put(b.river, new Route(b.river, b.officialIdentifier, List.copyOf(points),
                        b.sourceShift, b.routeLength, b.maximumRawChord, b.joinDistance, b.graphRadius));
            }
            return Map.copyOf(out);
        } catch (IOException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record IndexedPoint(int sequence, Point point) {}
    private static final class Builder {
        final String river, officialIdentifier;
        final double sourceShift, routeLength, maximumRawChord, joinDistance;
        final int graphRadius;
        final List<IndexedPoint> points = new ArrayList<>();
        Builder(String river, String officialIdentifier, double sourceShift, double routeLength,
                double maximumRawChord, double joinDistance, int graphRadius) {
            this.river = river; this.officialIdentifier = officialIdentifier; this.sourceShift = sourceShift;
            this.routeLength = routeLength; this.maximumRawChord = maximumRawChord;
            this.joinDistance = joinDistance; this.graphRadius = graphRadius;
        }
    }
}
