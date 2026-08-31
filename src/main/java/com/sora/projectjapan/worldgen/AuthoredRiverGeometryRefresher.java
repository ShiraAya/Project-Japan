package com.sora.projectjapan.worldgen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AR-03 geometry-only refresh core for the frozen 115 authored rivers.
 *
 * <p>This class deliberately does not construct a RiverCourse and therefore cannot change water Y,
 * width hierarchy, widening, hydraulic overrides or graph ownership. AR-04..09 will consume the
 * candidate geometry after the AR-02 classification is frozen.</p>
 */
final class AuthoredRiverGeometryRefresher {
    enum Mode { KEEP, REFIT, REBUILD, MANUAL_KEEP }
    record Point(double x, double z) {}
    record Preview(String river, Mode mode, int currentPoints, int officialPoints,
                   int candidatePoints, double maximumCandidateShiftBlocks,
                   double medianCandidateShiftBlocks, double sourceShiftBlocks,
                   double mouthShiftBlocks, boolean endpointsProtected,
                   boolean candidateFinite) {}

    /**
     * AR endpoint safety decision made before densification.  W05 headNodes are evidence, not an
     * instruction to teleport the authored source: same-code graphs can contain reversed/shared
     * fragments.  A proposed move is accepted only when it is local and remains continuous with
     * the already ordered authored scaffold.
     */
    record SourceRelocationDecision(Point oldSource, Point proposedSource, Point acceptedSource,
                                    double proposedShiftBlocks, double acceptedShiftBlocks,
                                    double oldFirstChordBlocks, double acceptedFirstChordBlocks,
                                    double headingChangeDegrees, boolean accepted, String reason) {}

    private AuthoredRiverGeometryRefresher() {}

    static Preview preview(String river, Mode mode, List<Point> current, List<Point> protectedPoints) {
        W05AuthoredReference.Route official = W05AuthoredReference.route(river);
        List<Point> officialWorld = official == null ? List.of() : project(official.samples());
        List<Point> candidate = switch (mode) {
            case KEEP, MANUAL_KEEP -> List.copyOf(current);
            case REFIT -> refit(current, officialWorld, protectedPoints);
            case REBUILD -> rebuild(officialWorld, protectedPoints);
        };
        if (candidate.size() < 2) candidate = List.copyOf(current);

        List<Double> shifts = new ArrayList<>();
        double max = 0.0D;
        for (Point point : candidate) {
            double d = distanceToPolyline(point, current);
            shifts.add(d);
            max = Math.max(max, d);
        }
        shifts.sort(Double::compareTo);
        double median = shifts.isEmpty() ? 0.0D : shifts.get(shifts.size() / 2);
        double sourceShift = candidate.isEmpty() || current.isEmpty() ? Double.NaN
                : distance(candidate.get(0), current.get(0));
        double mouthShift = candidate.isEmpty() || current.isEmpty() ? Double.NaN
                : distance(candidate.get(candidate.size() - 1), current.get(current.size() - 1));
        // KEEP/MANUAL_KEEP and REFIT preserve the current authored endpoints. REBUILD is allowed
        // to correct a bad authored source/mouth to the W05 logical-course endpoints; in that mode
        // "protected" means the rebuilt candidate still begins/ends on the official route.
        boolean endpointsProtected = mode == Mode.REBUILD
                ? (!officialWorld.isEmpty()
                    && distance(candidate.get(0), officialWorld.get(0)) <= 1.0E-6D
                    && distance(candidate.get(candidate.size() - 1), officialWorld.get(officialWorld.size() - 1)) <= 1.0E-6D)
                : (sourceShift <= 1.0E-6D && mouthShift <= 1.0E-6D);
        boolean finite = candidate.stream().allMatch(p -> Double.isFinite(p.x()) && Double.isFinite(p.z()));
        return new Preview(river, mode, current.size(), officialWorld.size(), candidate.size(), max,
                median, sourceShift, mouthShift, endpointsProtected, finite);
    }

    /**
     * AR-04..09 runtime candidate. W05AuthoredReference.samples() is a point cloud assembled from
     * several official fragments, not a guaranteed ordered polyline, so the authored along-route
     * order remains the topology scaffold. REFIT and REBUILD differ only in correction strength.
     * Source/mouth are fixed here; AR-10/11/CF own final endpoint/contact relocation.
     */
    static List<Point> runtimeCandidate(String river, Mode mode, List<Point> current,
                                        List<Point> protectedPoints) {
        return runtimeCandidate(river, mode, current, protectedPoints, false);
    }

    static List<Point> runtimeCandidate(String river, Mode mode, List<Point> current,
                                        List<Point> protectedPoints, boolean relocateSource) {
        if (mode == Mode.KEEP || mode == Mode.MANUAL_KEEP || current.size() < 3) {
            return List.copyOf(current);
        }
        W05AuthoredReference.Route official = W05AuthoredReference.route(river);
        List<Point> cloud = official == null ? List.of() : project(official.samples());
        if (cloud.isEmpty()) return List.copyOf(current);

        SourceRelocationDecision relocation = mode == Mode.REBUILD && relocateSource
                ? sourceRelocationDecision(river, current) : null;
        List<Point> activeProtected = protectedPoints;
        if (relocation != null) {
            Point oldSource = current.get(0);
            List<Point> adjusted = new ArrayList<>();
            for (Point p : protectedPoints) {
                if (relocation.accepted() && distance(p, oldSource) <= 1.0D) continue;
                adjusted.add(p);
            }
            if (!relocation.accepted()) {
                // If a dubious headNode teleport is rejected, also preserve a real authored
                // source reach.  Otherwise point #1/#2 can still chase the distant W05 cloud and
                // recreate the same giant chord one vertex downstream.
                // If the alleged official head lies mostly behind the authored downstream
                // direction, the head-node label is clearly not a usable source anchor. Keep
                // only a short source guard in that case; preserving a full kilometre would
                // itself freeze an artificial straight chord (the Yoshii/Nagara failure mode).
                double protectedLength = relocation.headingChangeDegrees() >= 120.0D
                        ? 512.0D
                        : Math.min(1024.0D,
                        Math.max(256.0D, relocation.proposedShiftBlocks() * 0.50D));
                double along = 0.0D;
                adjusted.add(oldSource);
                for (int i = 1; i < current.size() && along <= protectedLength; i++) {
                    along += distance(current.get(i - 1), current.get(i));
                    adjusted.add(current.get(i));
                }
            }
            activeProtected = List.copyOf(adjusted);
        }

        List<Point> corrected = new ArrayList<>(current.size());
        Point source = relocation == null ? current.get(0) : relocation.acceptedSource();
        corrected.add(source);
        for (int i = 1; i < current.size() - 1; i++) {
            Point p = current.get(i);
            if (nearProtected(p, activeProtected, 72.0D)) {
                corrected.add(p);
                continue;
            }
            Point q = nearestPoint(p, cloud);
            double d = distance(p, q);
            double weight;
            double maxShift;
            if (mode == Mode.REBUILD) {
                weight = clamp((d - 24.0D) / 320.0D, 0.34D, 1.00D);
                maxShift = 1800.0D;
            } else {
                weight = clamp((d - 64.0D) / 384.0D, 0.0D, 0.50D);
                maxShift = 500.0D;
            }
            double shift = Math.min(maxShift, d * weight);
            if (d <= 1.0E-9D || shift <= 1.0E-9D) corrected.add(p);
            else corrected.add(new Point(p.x() + (q.x() - p.x()) * (shift / d),
                    p.z() + (q.z() - p.z()) * (shift / d)));
        }
        corrected.add(current.get(current.size() - 1));

        // Route-level rounding is part of geometry reconciliation, not hydraulics. It removes
        // short sharp kinks introduced by nearest-point corridor correction before DropScheduler
        // sees the new centre-line. The W05 corridor is rechecked after each proposed move.
        int passes = mode == Mode.REBUILD ? 7 : 5;
        double maxMove = mode == Mode.REBUILD ? 30.0D : 22.0D;
        List<Point> smoothed = smoothPointCloudCorridor(corrected, cloud, activeProtected,
                passes, maxMove);
        if (mode != Mode.REBUILD) return smoothed;
        List<Point> continuous = limitRawScaffoldChords(smoothed, 192.0D);
        continuous = refitUnsupportedStraightRuns(continuous, current, cloud, activeProtected);
        return limitRawScaffoldChords(continuous, 192.0D);
    }

    private static List<Point> refitUnsupportedStraightRuns(List<Point> input, List<Point> authored,
                                                             List<Point> cloud,
                                                             List<Point> protectedPoints) {
        if (input.size() < 5 || cloud.isEmpty()) return input;
        List<Point> points = new ArrayList<>(input);
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            int runStart = 0;
            double runLength = 0.0D;
            for (int i = 1; i < points.size(); i++) {
                double segment = distance(points.get(i - 1), points.get(i));
                if (i == 1) runLength = segment;
                else {
                    double turn = turnDegrees(points.get(i - 2), points.get(i - 1), points.get(i));
                    if (turn < 2.0D) runLength += segment;
                    else { runStart = i - 1; runLength = segment; }
                }
                boolean closes = i == points.size() - 1
                        || (i + 1 < points.size()
                        && turnDegrees(points.get(i - 1), points.get(i), points.get(i + 1)) >= 2.0D);
                if (!closes || runLength < 768.0D) continue;
                int runEnd = i;
                double sumDeviation = 0.0D;
                int deviationSamples = 0;
                for (int j = runStart; j <= runEnd; j += Math.max(1, (runEnd - runStart) / 8)) {
                    Point p = points.get(j);
                    sumDeviation += distance(p, nearestPoint(p, cloud));
                    deviationSamples++;
                }
                double meanDeviation = sumDeviation / Math.max(1, deviationSamples);
                // A long line that already sits on W05 is legitimate. For an unsupported line,
                // prefer the pre-AR authored scaffold when it contains materially more curvature
                // and is not a worse W05 fit. This is the safest systemic rollback for an AR-created
                // chord: it restores real authored geometry instead of inventing random meanders.
                if (meanDeviation <= 64.0D) continue;
                if (authored.size() == points.size() && runEnd > runStart + 2) {
                    double authoredLength = 0.0D, authoredLongest = 0.0D, authoredRun = 0.0D;
                    double authoredDeviation = 0.0D; int authoredDeviationSamples = 0;
                    for (int j = runStart + 1; j <= runEnd; j++) {
                        double seg = distance(authored.get(j - 1), authored.get(j));
                        authoredLength += seg;
                        if (j == runStart + 1) authoredRun = seg;
                        else {
                            double turn = turnDegrees(authored.get(j - 2), authored.get(j - 1), authored.get(j));
                            authoredRun = turn < 2.0D ? authoredRun + seg : seg;
                        }
                        authoredLongest = Math.max(authoredLongest, authoredRun);
                    }
                    int sampleStride = Math.max(1, (runEnd - runStart) / 8);
                    for (int j = runStart; j <= runEnd; j += sampleStride) {
                        Point p = authored.get(j);
                        authoredDeviation += distance(p, nearestPoint(p, cloud));
                        authoredDeviationSamples++;
                    }
                    authoredDeviation /= Math.max(1, authoredDeviationSamples);
                    if (authoredLongest <= runLength * 0.72D
                            && authoredDeviation <= meanDeviation * 1.25D + 64.0D) {
                        for (int j = Math.max(1, runStart + 1);
                             j < Math.min(points.size() - 1, runEnd); j++) {
                            if (!nearProtected(points.get(j), protectedPoints, 72.0D)) {
                                points.set(j, authored.get(j));
                                changed = true;
                            }
                        }
                        continue;
                    }
                }
                for (int j = Math.max(1, runStart + 1);
                     j < Math.min(points.size() - 1, runEnd); j++) {
                    Point p = points.get(j);
                    if (nearProtected(p, protectedPoints, 72.0D)) continue;
                    Point q = nearestPoint(p, cloud);
                    double d = distance(p, q);
                    if (d < 24.0D || d > 1200.0D) continue;
                    double shift = Math.min(320.0D, d * 0.80D);
                    double t = shift / d;
                    points.set(j, new Point(p.x() + (q.x() - p.x()) * t,
                            p.z() + (q.z() - p.z()) * t));
                    changed = true;
                }
            }
            if (!changed) break;
            points = new ArrayList<>(smoothPointCloudCorridor(points, cloud, protectedPoints,
                    2, 48.0D));
        }
        points.set(0, input.get(0));
        points.set(points.size() - 1, input.get(input.size() - 1));
        return List.copyOf(points);
    }

    private static List<Point> limitRawScaffoldChords(List<Point> input, double maximumChord) {
        if (input.size() < 3) return input;
        List<Point> points = new ArrayList<>(input);
        // Work from both fixed endpoints.  This does not invent curvature; it merely prevents a
        // single corrected scaffold vertex from jumping thousands of blocks while its neighbour
        // remains on the authored route.  Subsequent normal smoothing/densification keeps the W05
        // corridor continuous.
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < points.size() - 1; i++) {
                Point prev = points.get(i - 1), p = points.get(i);
                double d = distance(prev, p);
                if (d > maximumChord) {
                    double t = maximumChord / d;
                    points.set(i, new Point(prev.x() + (p.x() - prev.x()) * t,
                            prev.z() + (p.z() - prev.z()) * t));
                }
            }
            for (int i = points.size() - 2; i > 0; i--) {
                Point next = points.get(i + 1), p = points.get(i);
                double d = distance(p, next);
                if (d > maximumChord) {
                    double t = maximumChord / d;
                    points.set(i, new Point(next.x() + (p.x() - next.x()) * t,
                            next.z() + (p.z() - next.z()) * t));
                }
            }
        }
        points.set(0, input.get(0));
        points.set(points.size() - 1, input.get(input.size() - 1));
        return List.copyOf(points);
    }

    private static List<Point> smoothPointCloudCorridor(List<Point> input, List<Point> cloud,
                                                         List<Point> protectedPoints, int passes,
                                                         double maxMove) {
        List<Point> points = new ArrayList<>(input);
        for (int pass = 0; pass < passes; pass++) {
            List<Point> next = new ArrayList<>(points);
            for (int i = 1; i < points.size() - 1; i++) {
                Point p = points.get(i);
                if (nearProtected(p, protectedPoints, 72.0D)) continue;
                Point a = points.get(i - 1), c = points.get(i + 1);
                Point desired = new Point(0.25D * a.x() + 0.50D * p.x() + 0.25D * c.x(),
                        0.25D * a.z() + 0.50D * p.z() + 0.25D * c.z());
                double move = distance(p, desired);
                if (move > maxMove) {
                    double f = maxMove / move;
                    desired = new Point(p.x() + (desired.x() - p.x()) * f,
                            p.z() + (desired.z() - p.z()) * f);
                }
                double before = distance(p, nearestPoint(p, cloud));
                double after = distance(desired, nearestPoint(desired, cloud));
                // Smoothing may bridge small sample gaps but must not drift away from the W05
                // corridor by hundreds of blocks merely to make a visually round curve.
                double allowed = Math.max(160.0D, before + 72.0D);
                if (after <= allowed) next.set(i, desired);
            }
            points = next;
        }
        points.set(0, input.get(0));
        points.set(points.size() - 1, input.get(input.size() - 1));
        return List.copyOf(points);
    }


    static SourceRelocationDecision sourceRelocationDecision(String river, List<Point> current) {
        if (current.size() < 2) {
            Point p = current.isEmpty() ? new Point(Double.NaN, Double.NaN) : current.get(0);
            return new SourceRelocationDecision(p, p, p, 0.0D, 0.0D, 0.0D, 0.0D,
                    0.0D, false, "NO_SCAFFOLD");
        }
        Point oldSource = current.get(0);
        Point next = current.get(1);
        W05AuthoredReference.Route official = W05AuthoredReference.route(river);
        if (official == null || official.headNodes().isEmpty()) {
            double chord = distance(oldSource, next);
            return new SourceRelocationDecision(oldSource, oldSource, oldSource, 0.0D, 0.0D,
                    chord, chord, 0.0D, false, "NO_W05_HEAD_EVIDENCE");
        }
        Point proposed = nearestPoint(oldSource, project(official.headNodes()));
        double shift = distance(oldSource, proposed);
        double oldChord = distance(oldSource, next);
        double proposedChord = distance(proposed, next);
        double heading = headingDifferenceDegrees(oldSource, next, proposed, next);

        boolean local = shift <= 256.0D;
        boolean reviewRange = shift > 256.0D && shift <= 1024.0D;
        List<Point> cloud = project(official.samples());
        double oldOfficial = cloud.isEmpty() ? Double.POSITIVE_INFINITY
                : distance(oldSource, nearestPoint(oldSource, cloud));
        double proposedOfficial = cloud.isEmpty() ? Double.POSITIVE_INFINITY
                : distance(proposed, nearestPoint(proposed, cloud));
        boolean directContinuity = proposedChord <= Math.max(768.0D, oldChord * 2.25D + 128.0D)
                && heading <= 70.0D;
        boolean materiallyBetter = proposedOfficial + 64.0D < oldOfficial;
        // In the 256..1024 review band the old authored second scaffold can itself be the
        // stale endpoint that AR is repairing.  A direct proposed->point1 heading test therefore
        // rejects legitimate W05 heads (Iruma was the regression canary).  Accept a review-band
        // source only when the head is physically supported by the W05 point cloud and is a
        // material improvement; limitRawScaffoldChords() then proves the rebuilt pre-densify
        // scaffold is continuous before it can reach runtime.  >1024 remains a hard reject.
        boolean officialBridge = reviewRange && proposedOfficial <= 96.0D && materiallyBetter;
        boolean continuity = directContinuity || officialBridge;
        boolean accept = local ? directContinuity : (reviewRange && continuity && materiallyBetter);
        String reason;
        if (shift > 1024.0D) reason = "REJECT_TELEPORT_GT_1024";
        else if (!continuity) reason = "REJECT_SCAFFOLD_DISCONTINUITY";
        else if (reviewRange && !materiallyBetter) reason = "REJECT_NO_OFFICIAL_GAIN";
        else if (accept && local) reason = "ACCEPT_LOCAL_W05_HEAD";
        else if (accept && officialBridge && !directContinuity) reason = "ACCEPT_REVIEW_RANGE_W05_BRIDGE";
        else if (accept) reason = "ACCEPT_REVIEW_RANGE_CONTINUOUS";
        else reason = "KEEP_AUTHORED_SOURCE";
        Point accepted = accept ? proposed : oldSource;
        return new SourceRelocationDecision(oldSource, proposed, accepted, shift,
                distance(oldSource, accepted), oldChord, distance(accepted, next), heading,
                accept, reason);
    }

    private static double headingDifferenceDegrees(Point a0, Point a1, Point b0, Point b1) {
        double ax = a1.x() - a0.x(), az = a1.z() - a0.z();
        double bx = b1.x() - b0.x(), bz = b1.z() - b0.z();
        double al = Math.hypot(ax, az), bl = Math.hypot(bx, bz);
        if (al < 1.0E-9D || bl < 1.0E-9D) return 0.0D;
        double dot = clamp((ax * bx + az * bz) / (al * bl), -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(dot));
    }

    private static Point nearestPoint(Point p, List<Point> cloud) {
        Point best = cloud.get(0);
        double bestD = distance(p, best);
        for (int i = 1; i < cloud.size(); i++) {
            Point q = cloud.get(i);
            double d = distance(p, q);
            if (d < bestD) { bestD = d; best = q; }
        }
        return best;
    }

    static List<Point> refit(List<Point> current, List<Point> official, List<Point> protectedPoints) {
        if (current.size() < 2 || official.size() < 2) return List.copyOf(current);
        List<Point> result = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            Point p = current.get(i);
            if (i == 0 || i == current.size() - 1 || nearProtected(p, protectedPoints, 96.0D)) {
                result.add(p);
                continue;
            }
            Point q = nearestOnPolyline(p, official);
            double d = distance(p, q);
            // REFIT only corrects the locally wrong corridor. Small deviations remain untouched.
            double weight = clamp((d - 96.0D) / 384.0D, 0.0D, 0.72D);
            result.add(new Point(p.x() + (q.x() - p.x()) * weight,
                    p.z() + (q.z() - p.z()) * weight));
        }
        return simplify(result, protectedPoints, 20.0D);
    }

    static List<Point> rebuild(List<Point> official, List<Point> protectedPoints) {
        if (official.size() < 2) return List.copyOf(official);
        // The W05AuthoredReference route is already the topology-preserving logical-course
        // extraction from the W05 graph. At PJ 1:8 scale, simplify only low-curvature runs.
        return simplify(official, protectedPoints, 14.0D);
    }

    private static List<Point> project(List<W05AuthoredReference.LatLon> points) {
        List<Point> result = new ArrayList<>(points.size());
        Point previous = null;
        for (W05AuthoredReference.LatLon p : points) {
            Point world = new Point(TerrainData.worldXFromLongitude(p.longitude()),
                    TerrainData.worldZFromLatitude(p.latitude()));
            if (previous == null || distance(previous, world) >= 1.0D) result.add(world);
            previous = world;
        }
        return List.copyOf(result);
    }

    private static List<Point> simplify(List<Point> points, List<Point> protectedPoints,
                                        double tolerance) {
        if (points.size() <= 2) return List.copyOf(points);
        Set<Integer> keep = new HashSet<>();
        keep.add(0);
        keep.add(points.size() - 1);
        for (int i = 1; i < points.size() - 1; i++) {
            Point a = points.get(i - 1), b = points.get(i), c = points.get(i + 1);
            double turn = turnDegrees(a, b, c);
            if (turn >= 12.0D || nearProtected(b, protectedPoints, 96.0D)) keep.add(i);
        }
        rdp(points, 0, points.size() - 1, tolerance, keep);
        List<Point> result = new ArrayList<>(keep.size());
        for (int i = 0; i < points.size(); i++) if (keep.contains(i)) result.add(points.get(i));
        return List.copyOf(result);
    }

    private static void rdp(List<Point> points, int first, int last, double tolerance,
                            Set<Integer> keep) {
        if (last <= first + 1) return;
        Point a = points.get(first), b = points.get(last);
        double best = -1.0D;
        int bestIndex = -1;
        for (int i = first + 1; i < last; i++) {
            if (keep.contains(i)) {
                rdp(points, first, i, tolerance, keep);
                rdp(points, i, last, tolerance, keep);
                return;
            }
            double d = pointSegmentDistance(points.get(i), a, b);
            if (d > best) { best = d; bestIndex = i; }
        }
        if (bestIndex >= 0 && best > tolerance) {
            keep.add(bestIndex);
            rdp(points, first, bestIndex, tolerance, keep);
            rdp(points, bestIndex, last, tolerance, keep);
        }
    }

    private static boolean nearProtected(Point p, List<Point> protectedPoints, double radius) {
        for (Point q : protectedPoints) if (distance(p, q) <= radius) return true;
        return false;
    }

    private static Point nearestOnPolyline(Point p, List<Point> line) {
        Point best = line.get(0);
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < line.size() - 1; i++) {
            Point q = nearestOnSegment(p, line.get(i), line.get(i + 1));
            double d = distance(p, q);
            if (d < bestD) { bestD = d; best = q; }
        }
        return best;
    }

    private static double distanceToPolyline(Point p, List<Point> line) {
        if (line.isEmpty()) return Double.NaN;
        if (line.size() == 1) return distance(p, line.get(0));
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < line.size() - 1; i++)
            best = Math.min(best, pointSegmentDistance(p, line.get(i), line.get(i + 1)));
        return best;
    }

    private static Point nearestOnSegment(Point p, Point a, Point b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double l2 = dx * dx + dz * dz;
        if (l2 <= 1.0E-9D) return a;
        double t = clamp(((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / l2, 0.0D, 1.0D);
        return new Point(a.x() + dx * t, a.z() + dz * t);
    }

    private static double pointSegmentDistance(Point p, Point a, Point b) {
        return distance(p, nearestOnSegment(p, a, b));
    }

    private static double turnDegrees(Point a, Point b, Point c) {
        double ux = b.x() - a.x(), uz = b.z() - a.z();
        double vx = c.x() - b.x(), vz = c.z() - b.z();
        double ul = Math.hypot(ux, uz), vl = Math.hypot(vx, vz);
        if (ul < 1.0D || vl < 1.0D) return 0.0D;
        double dot = clamp((ux * vx + uz * vz) / (ul * vl), -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(dot));
    }

    private static double distance(Point a, Point b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
