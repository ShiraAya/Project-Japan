package com.sora.projectjapan.worldgen;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Frozen AR-02 KEEP/REFIT/REBUILD/MANUAL_KEEP decisions consumed by AR-03..09. */
final class AuthoredRiverARPolicy {
    private static final String RESOURCE = "/assets/projectjapan/hydrology/policy/ar_authored_river_classification_v1.csv";
    record Decision(AuthoredRiverGeometryRefresher.Mode mode, String reason) {}
    private static final Map<String, Decision> DECISIONS = load();

    private AuthoredRiverARPolicy() {}

    static Decision decision(String river) {
        Decision decision = DECISIONS.get(river);
        if (decision == null) throw new IllegalStateException("Missing AR-02 classification for " + river);
        return decision;
    }

    static int size() { return DECISIONS.size(); }

    private static Map<String, Decision> load() {
        InputStream in = AuthoredRiverARPolicy.class.getResourceAsStream(RESOURCE);
        if (in == null) throw new IllegalStateException("Missing AR-02 policy resource " + RESOURCE);
        Map<String, Decision> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = parseCsv(line);
                if (fields.length < 5) throw new IllegalStateException("Invalid AR-02 policy row: " + line);
                String river = fields[0];
                AuthoredRiverGeometryRefresher.Mode mode = AuthoredRiverGeometryRefresher.Mode.valueOf(fields[3]);
                Decision old = result.put(river, new Decision(mode, fields[4]));
                if (old != null) throw new IllegalStateException("Duplicate AR-02 river " + river);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load AR-02 policy", e);
        }
        if (result.size() != 115) throw new IllegalStateException("AR-02 policy count=" + result.size());
        return Map.copyOf(result);
    }

    private static String[] parseCsv(String line) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(current.toString()); current.setLength(0);
            } else current.append(c);
        }
        fields.add(current.toString());
        return fields.toArray(String[]::new);
    }
}
