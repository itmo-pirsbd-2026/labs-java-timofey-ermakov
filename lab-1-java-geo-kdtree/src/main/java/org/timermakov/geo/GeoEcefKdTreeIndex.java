package org.timermakov.geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Experimental ECEF-core variant of {@link GeoKdTreeIndex}: same public contract, same
 * tree topology (alternating raw lat/lng splits), but nodes store 3D Earth-Centered
 * Earth-Fixed coordinates and all traversal math is euclidean on chords.
 *
 * <p>Per visited node: squared chord distance (3 subtractions, 3 multiplications, 2 additions)
 * instead of the geodesic a-space metric, and pruning bounds from half-spaces: a latitude
 * split is the plane {@code z = R·sin φ0}, a longitude split is a dihedral wedge whose faces
 * are precomputed at insert time. The ±180 seam falls out of the raw-degree wedge geometry
 * exactly; no trigonometry runs inside the traversal. Exact meters are computed only for
 * radius hits and accepted kNN candidates ({@code 2R·asin(chord/2R)}), so the output
 * contract (lat/lng in, meters out) is unchanged.
 *
 * <p>Measurement instrument for the metric-vs-structure decomposition; see
 * report/smile-comparison.md.
 */
public final class GeoEcefKdTreeIndex {

    private static final int SPLIT_AXIS_MODULUS = 2;
    private static final double LATITUDE_LIMIT = 90.0;
    private static final double LONGITUDE_LIMIT = 180.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double TWO_EARTH_RADIUS_METERS = 2 * EARTH_RADIUS_METERS;
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    /** Borderline margin for exact-meter tie decisions, in ulps of the chord-squared term. */
    private static final double CHORD_COMPARISON_MARGIN_ULPS = 8.0;

    /** Testing/benchmarking hook, same contract as in {@link GeoKdTreeIndex}. */
    public static volatile boolean PROFILING_COUNT_NODES = false;

    /** Accumulator for {@link #PROFILING_COUNT_NODES}, reset by the caller between runs. */
    public static long PROFILING_VISITED_NODES = 0;

    private final Map<String, GeoPoint> pointsById = new HashMap<>();
    private Node root;

    public int count() {
        return pointsById.size();
    }

    public void insert(GeoPoint point) {
        Objects.requireNonNull(point, "point");
        if (point.id() == null || point.id().isBlank()) {
            throw new IllegalArgumentException("Point id must not be null or blank.");
        }
        validateCoordinate(point.latitude(), point.longitude());
        if (pointsById.containsKey(point.id())) {
            throw new IllegalStateException("Point '" + point.id() + "' already exists.");
        }

        root = insertNode(root, point, 0);
        pointsById.put(point.id(), point);
    }

    /** All indexed points within {@code radiusMeters}, ordered by distance then id. */
    public List<GeoSearchResult> searchRadius(double latitude, double longitude, double radiusMeters) {
        validateCoordinate(latitude, longitude);
        if (radiusMeters < 0) {
            throw new IllegalArgumentException("Radius must be non-negative.");
        }

        if (root == null) {
            return List.of();
        }

        double[] q = new double[3];
        toEcef(latitude, longitude, q);
        double chordSqThreshold = chordSqFromMeters(radiusMeters);
        List<GeoSearchResult> results = new ArrayList<>();
        searchRadiusCore(root, q, latitude, longitude, chordSqThreshold, results);
        results.sort(GeoSearchResultComparators.NEAREST_FIRST);
        return results;
    }

    /** The {@code k} nearest indexed points, ordered by distance then id. */
    public List<GeoSearchResult> searchKNearest(double latitude, double longitude, int k) {
        validateCoordinate(latitude, longitude);
        if (k <= 0) {
            throw new IllegalArgumentException("K must be positive.");
        }

        if (root == null) {
            return List.of();
        }

        double[] q = new double[3];
        toEcef(latitude, longitude, q);
        BestBuffer best = new BestBuffer(k);
        searchKNearestCore(root, q, latitude, longitude, k, best);

        List<GeoSearchResult> results = new ArrayList<>(best.size);
        for (int i = 0; i < best.size; i++) {
            results.add(new GeoSearchResult(best.points[i], metersFromChordSq(best.chordSq[i])));
        }
        results.sort(GeoSearchResultComparators.NEAREST_FIRST);
        return results;
    }

    private static Node insertNode(Node node, GeoPoint point, int depth) {
        if (node == null) {
            return new Node(point, depth);
        }

        boolean splitByLatitude = depth % SPLIT_AXIS_MODULUS == 0;
        boolean goLeft = splitByLatitude
                ? point.latitude() < node.point.latitude()
                : point.longitude() < node.point.longitude();

        if (goLeft) {
            node.left = insertNode(node.left, point, depth + 1);
        } else {
            node.right = insertNode(node.right, point, depth + 1);
        }

        return node;
    }

    private static void searchRadiusCore(
            Node node,
            double[] q,
            double queryLat,
            double queryLng,
            double chordSqThreshold,
            List<GeoSearchResult> results) {
        if (PROFILING_COUNT_NODES) {
            PROFILING_VISITED_NODES++;
        }

        double chordSq = chordSq(q, node);
        if (chordSq <= chordSqThreshold) {
            results.add(new GeoSearchResult(node.point, metersFromChordSq(chordSq)));
        }

        boolean splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        double queryAxis = splitByLatitude ? queryLat : queryLng;
        double splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        Node nearBranch = queryAxis < splitAxis ? node.left : node.right;
        Node farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchRadiusCore(nearBranch, q, queryLat, queryLng, chordSqThreshold, results);
        }

        if (farBranch == null) {
            return;
        }

        double bound = axisLowerBoundChordSq(node, splitByLatitude, q, queryLat, queryLng);
        if (bound <= chordSqThreshold) {
            searchRadiusCore(farBranch, q, queryLat, queryLng, chordSqThreshold, results);
        }
    }

    private static void searchKNearestCore(
            Node node,
            double[] q,
            double queryLat,
            double queryLng,
            int k,
            BestBuffer best) {
        if (PROFILING_COUNT_NODES) {
            PROFILING_VISITED_NODES++;
        }

        double chordSq = chordSq(q, node);
        tryAddBest(best, node.point, chordSq, k);

        boolean splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        double queryAxis = splitByLatitude ? queryLat : queryLng;
        double splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        Node nearBranch = queryAxis < splitAxis ? node.left : node.right;
        Node farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchKNearestCore(nearBranch, q, queryLat, queryLng, k, best);
        }

        if (farBranch == null) {
            return;
        }

        double bound = axisLowerBoundChordSq(node, splitByLatitude, q, queryLat, queryLng);
        if (best.size < k || bound <= best.worstChordSq()) {
            searchKNearestCore(farBranch, q, queryLat, queryLng, k, best);
        }
    }

    /**
     * Lower bound of the squared chord distance from the query to the far side of this
     * node's split, sign-aware: the far region depends on which side the query is on.
     * Latitude far side is a half-space z ≥ R·sin φ0 or z ≤ R·sin φ0 — exact distance.
     * Longitude far side is the raw-degree wedge on one side of the split meridian;
     * for split ≥ 0 the wedge is an intersection of two half-planes (bound = max of
     * the two face distances), for split < 0 a union (bound = min) — exact, with the
     * anti-meridian face carrying the ±180 wrap. The mirror cases flip both violation
     * signs and the union/intersection flag.
     */
    private static double axisLowerBoundChordSq(Node node, boolean splitByLatitude, double[] q,
                                                double queryLat, double queryLng) {
        if (splitByLatitude) {
            double gap = queryLat < node.point.latitude()
                    ? node.latPlaneZ - q[2]
                    : q[2] - node.latPlaneZ;
            return gap > 0.0 ? gap * gap : 0.0;
        }

        double v1 = node.lngPlaneNx * q[0] + node.lngPlaneNy * q[1];
        double splitLng = node.point.longitude();
        boolean farRight = queryLng < splitLng;
        boolean union = farRight ? splitLng < 0.0 : splitLng > 0.0;

        double viol1;
        double viol2;
        if (farRight) {
            viol1 = v1 < 0.0 ? -v1 : 0.0;
            viol2 = q[1] < 0.0 ? -q[1] : 0.0;
        } else {
            viol1 = v1 > 0.0 ? v1 : 0.0;
            viol2 = q[1] > 0.0 ? q[1] : 0.0;
        }

        double chosen = union ? Math.min(viol1, viol2) : Math.max(viol1, viol2);
        return chosen * chosen;
    }

    /**
     * Primitive best buffer, unsorted: candidates land at a free slot or overwrite the
     * current worst slot; the worst slot is found by an O(k) scan only on replacement.
     * No shifts, no per-candidate allocations (H3, iteration 2 — the sorted-array
     * variant won in k=1 but its double arraycopy per accept regressed k=20).
     */
    private static final class BestBuffer {
        final double[] chordSq;
        final GeoPoint[] points;
        final int capacity;
        int size;
        int maxIndex;

        BestBuffer(int k) {
            this.chordSq = new double[k];
            this.points = new GeoPoint[k];
            this.capacity = k;
        }

        double worstChordSq() {
            return chordSq[maxIndex];
        }

        GeoPoint worstPoint() {
            return points[maxIndex];
        }

        void consider(GeoPoint point, double value) {
            if (size < capacity) {
                chordSq[size] = value;
                points[size] = point;
                if (size == 0 || value > chordSq[maxIndex]) {
                    maxIndex = size;
                }
                size++;
                return;
            }

            chordSq[maxIndex] = value;
            points[maxIndex] = point;
            rescanMax();
        }

        private void rescanMax() {
            int max = 0;
            for (int i = 1; i < size; i++) {
                if (chordSq[i] > chordSq[max]) {
                    max = i;
                }
            }
            maxIndex = max;
        }
    }

    /** Accepts a candidate into the primitive best buffer (unsorted, worst-slot rewrite). */
    private static void tryAddBest(BestBuffer best, GeoPoint point, double chordSq, int k) {
        if (best.size < k) {
            best.consider(point, chordSq);
            return;
        }

        double worstChordSq = best.worstChordSq();
        double margin = Math.ulp(worstChordSq) * CHORD_COMPARISON_MARGIN_ULPS;
        if (chordSq > worstChordSq + margin) {
            return;
        }

        // Borderline: decide exactly, in meters, with the id tie-break.
        double candidateMeters = metersFromChordSq(chordSq);
        double worstMeters = metersFromChordSq(worstChordSq);
        int byDistance = Double.compare(candidateMeters, worstMeters);
        if (byDistance == 0 && point.id().compareTo(best.worstPoint().id()) >= 0) {
            return;
        }
        if (byDistance > 0) {
            return;
        }

        best.consider(point, chordSq);
    }

    private static double chordSq(double[] q, Node node) {
        double dx = q[0] - node.x;
        double dy = q[1] - node.y;
        double dz = q[2] - node.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Squared chord radius for a spherical distance in meters: (2R·sin(m/2R))². */
    static double chordSqFromMeters(double meters) {
        double chord = TWO_EARTH_RADIUS_METERS * Math.sin(meters / TWO_EARTH_RADIUS_METERS);
        return chord * chord;
    }

    /** Exact spherical distance in meters for a squared chord. */
    static double metersFromChordSq(double chordSq) {
        double chord = Math.sqrt(chordSq);
        double ratio = Math.min(1.0, chord / TWO_EARTH_RADIUS_METERS);
        return TWO_EARTH_RADIUS_METERS * Math.asin(ratio);
    }

    /** Converts lat/lng degrees to ECEF coordinates on the library's sphere. */
    public static void toEcef(double latitude, double longitude, double[] out) {
        double lat = latitude * DEGREES_TO_RADIANS;
        double lng = longitude * DEGREES_TO_RADIANS;
        double cosLat = Math.cos(lat);
        out[0] = EARTH_RADIUS_METERS * cosLat * Math.cos(lng);
        out[1] = EARTH_RADIUS_METERS * cosLat * Math.sin(lng);
        out[2] = EARTH_RADIUS_METERS * Math.sin(lat);
    }

    private static void validateCoordinate(double latitude, double longitude) {
        if (latitude < -LATITUDE_LIMIT || latitude > LATITUDE_LIMIT) {
            throw new IllegalArgumentException("Latitude must be in range [-90, 90].");
        }

        if (longitude < -LONGITUDE_LIMIT || longitude > LONGITUDE_LIMIT) {
            throw new IllegalArgumentException("Longitude must be in range [-180, 180].");
        }
    }

    private static final class Node {
        final GeoPoint point;
        final int depth;

        final double x;
        final double y;
        final double z;

        /** Latitude splits: z level of the bounding plane R·sin φ0. */
        final double latPlaneZ;

        /** Longitude splits: normal of the split face plane (horizontal, unit length). */
        final double lngPlaneNx;
        final double lngPlaneNy;

        Node left;
        Node right;

        Node(GeoPoint point, int depth) {
            this.point = point;
            this.depth = depth;

            double[] ecef = new double[3];
            toEcef(point.latitude(), point.longitude(), ecef);
            this.x = ecef[0];
            this.y = ecef[1];
            this.z = ecef[2];

            boolean splitByLatitude = depth % SPLIT_AXIS_MODULUS == 0;
            this.latPlaneZ = splitByLatitude
                    ? EARTH_RADIUS_METERS * Math.sin(point.latitude() * DEGREES_TO_RADIANS)
                    : 0.0;

            double splitLngRadians = point.longitude() * DEGREES_TO_RADIANS;            this.lngPlaneNx = splitByLatitude ? 0.0 : -Math.sin(splitLngRadians);
            this.lngPlaneNy = splitByLatitude ? 0.0 : Math.cos(splitLngRadians);
        }
    }
}
