package com.sora.projectjapan.worldgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven source-lake outlet evidence derived from the LR-12/W05 gate inventory.
 *
 * <p>The resolver never contains per-river coordinates in code.  It prefers an outlet whose W05
 * logical river code matches the authored river reference, then falls back to the same water
 * system.  The small CSV is generated from the frozen LR-12 evidence and is runtime hydrology
 * data, not a validation report.</p>
 */
final class SourceLakeOutletResolver {
    private static final String RESOURCE =
            "/assets/projectjapan/hydrology/generated/source_lake_outlet_reference_v1.csv";
    private static final List<Outlet> OUTLETS = load();

    record Outlet(String lakeId, String lakeName, String riverId, String riverName,
                  int lr12GateX, int lr12GateZ, int routeShoreX, int routeShoreZ,
                  Integer physicalX, Integer physicalZ, String lr12Status) {}

    private SourceLakeOutletResolver() {}

    static Outlet resolve(String lakeName, String authoredOfficialIdentifier) {
        String exactCode = riverCode(authoredOfficialIdentifier);
        String system = waterSystem(authoredOfficialIdentifier);
        Outlet exact = null;
        Outlet sameSystem = null;
        for (Outlet outlet : OUTLETS) {
            if (!outlet.lakeName().equals(lakeName)) continue;
            if (!exactCode.isEmpty() && riverCode(outlet.riverId()).equals(exactCode)) {
                if (exact == null || preferred(outlet, exact)) exact = outlet;
            } else if (!system.isEmpty() && waterSystem(outlet.riverId()).equals(system)) {
                if (sameSystem == null || preferred(outlet, sameSystem)) sameSystem = outlet;
            }
        }
        return exact != null ? exact : sameSystem;
    }

    static List<Outlet> outlets() { return OUTLETS; }

    private static boolean preferred(Outlet candidate, Outlet current) {
        boolean candidatePhysical = candidate.physicalX() != null && candidate.physicalZ() != null;
        boolean currentPhysical = current.physicalX() != null && current.physicalZ() != null;
        if (candidatePhysical != currentPhysical) return candidatePhysical;
        boolean candidateMain = riverCode(candidate.riverId()).endsWith("0001");
        boolean currentMain = riverCode(current.riverId()).endsWith("0001");
        if (candidateMain != currentMain) return candidateMain;
        return candidate.riverId().compareTo(current.riverId()) < 0;
    }

    private static String waterSystem(String id) {
        if (id == null) return "";
        String[] parts = id.split(":", 5);
        return parts.length >= 3 ? parts[1] : "";
    }

    private static String riverCode(String id) {
        if (id == null) return "";
        String[] parts = id.split(":", 5);
        return parts.length >= 4 ? parts[2] : "";
    }

    private static List<Outlet> load() {
        try (InputStream raw = SourceLakeOutletResolver.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("Missing resource " + RESOURCE);
            List<Outlet> result = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
                String line = reader.readLine(); // header
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] c = parseCsv(line);
                    if (c.length < 11) throw new IOException("Invalid outlet row: " + line);
                    result.add(new Outlet(c[0], c[1], c[2], c[3], Integer.parseInt(c[4]),
                            Integer.parseInt(c[5]), Integer.parseInt(c[6]), Integer.parseInt(c[7]),
                            parseNullableInt(c[8]), parseNullableInt(c[9]), c[10]));
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Integer parseNullableInt(String value) {
        return value == null || value.isEmpty() ? null : Integer.valueOf(value);
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
