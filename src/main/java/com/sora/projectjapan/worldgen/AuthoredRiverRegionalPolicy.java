package com.sora.projectjapan.worldgen;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Frozen AR-04..09 regional assignment for the 115 authored rivers. */
final class AuthoredRiverRegionalPolicy {
    private static final String RESOURCE = "/assets/projectjapan/hydrology/policy/ar_authored_river_regional_batches_v1.csv";
    private static final Map<String, String> BATCHES = load();

    private AuthoredRiverRegionalPolicy() {}

    static String batch(String river) {
        String batch = BATCHES.get(river);
        if (batch == null) throw new IllegalStateException("Missing AR regional batch for " + river);
        return batch;
    }

    static int size() { return BATCHES.size(); }

    private static Map<String, String> load() {
        InputStream in = AuthoredRiverRegionalPolicy.class.getResourceAsStream(RESOURCE);
        if (in == null) throw new IllegalStateException("Missing AR regional policy " + RESOURCE);
        Map<String, String> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length < 2) throw new IllegalStateException("Invalid AR regional row: " + line);
                String old = result.put(fields[0], fields[1]);
                if (old != null) throw new IllegalStateException("Duplicate AR regional river " + fields[0]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load AR regional policy", e);
        }
        if (result.size() != 115) throw new IllegalStateException("AR regional policy count=" + result.size());
        return Map.copyOf(result);
    }
}
