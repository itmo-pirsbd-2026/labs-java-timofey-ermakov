package org.timermakov.geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical in-memory KD-tree index over lat/lng points (ECEF core, successor of the C# port).
 * Nodes store 3D Earth-Centered Earth-Fixed coordinates; the tree topology still splits by
 * alternating raw lat/lng, but all traversal math is euclidean on chords: squared chord distance
 * per node, pruning from half-spaces (a latitude split is the plane z = R·sin φ0, a longitude
 * split a dihedral wedge whose faces are precomputed at insert; the ±180 seam falls out of the
 * raw-degree wedge geometry exactly). No trigonometry runs inside the traversal; exact meters
 * are computed only for radius hits and accepted kNN candidates, so the public contract
 * (lat/lng in, meters out) is unchanged from the geodesic variant
 * ({@link GeoGeodesicKdTreeIndex}, kept for reference).
 *
 * <p>History: baseline port → geodesic optimizations (a-space, seam-aware bounds) →
 * ECEF core with primitive best buffer. Measurements: report/smile-comparison.md.
 */
public final class GeoKdTreeIndex {

    private static final int SPLIT_AXIS_MODULUS = 2;
    private static final double LATITUDE_LIMIT = 90.0;
    private static final double LONGITUDE_LIMIT = 180.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double TWO_EARTH_RADIUS_METERS = 2 * EARTH_RADIUS_METERS;
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    /** Borderline margin for exact-meter tie decisions, in ulps of the chord-squared term. */
    private static final double CHORD_COMPARISON_MARGIN_ULPS = 8.0;

    /** Scapegoat balance factor as numerator/denominator: a child bigger than 0.7 of the
     * subtree forces a rebuild. Bounds depth by ~log_{1/0.7}(n), keeping inserts amortized
     * O(log n) even on sorted input (H6 follow-up). */
    private static final int BALANCE_NUM = 7;
    private static final int BALANCE_DEN = 10;

    /** Testing/benchmarking hook, same contract as in {@link GeoKdTreeIndex}. */
    public static volatile boolean PROFILING_COUNT_NODES = false;

    /** Accumulator for {@link #PROFILING_COUNT_NODES}, reset by the caller between runs. */
    public static long PROFILING_VISITED_NODES = 0;

    private final Map<String, GeoPoint> pointsById = new Object2ObjectOpenHashMap<>();
    private Node root;

    public int count() {
        return pointsById.size();
    }

    /** Deepest root-to-leaf path; testing hook for the balance guarantee. */
    int maxDepth() {
        return root == null ? 0 : maxDepth(root, 1);
    }

    private static int maxDepth(Node node, int depth) {
        int left = node.left == null ? depth : maxDepth(node.left, depth + 1);
        int right = node.right == null ? depth : maxDepth(node.right, depth + 1);
        return Math.max(left, right);
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

        node.size = 1 + size(node.left) + size(node.right);
        if (isUnbalanced(node)) {
            return rebuild(node, depth);
        }

        return node;
    }

    private static int size(Node node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isUnbalanced(Node node) {
        int n = node.size;
        return (node.left != null && node.left.size * BALANCE_DEN > BALANCE_NUM * n)
                || (node.right != null && node.right.size * BALANCE_DEN > BALANCE_NUM * n);
    }

    /**
     * Scapegoat rebuild: collect the subtree points and rebuild it balanced, splitting by
     * the alternating axis at each level (median split). Regions stay non-strict on both
     * sides (left subset of {axis <= split}, right subset of {axis >= split}), which keeps
     * every pruning bound valid. Rebuilds are rare (amortized O(log n) per insert).
     */
    private static Node rebuild(Node node, int depth) {
        List<GeoPoint> points = new ArrayList<>(node.size);
        collect(node, points);
        return buildBalanced(points, 0, points.size(), depth);
    }

    private static void collect(Node node, List<GeoPoint> out) {
        if (node == null) {
            return;
        }
        collect(node.left, out);
        out.add(node.point);
        collect(node.right, out);
    }

    private static Node buildBalanced(List<GeoPoint> points, int lo, int hi, int depth) {
        if (lo >= hi) {
            return null;
        }

        boolean splitByLatitude = depth % SPLIT_AXIS_MODULUS == 0;
        points.subList(lo, hi).sort(splitByLatitude ? LATITUDE_ORDER : LONGITUDE_ORDER);

        int mid = (lo + hi) >>> 1;
        GeoPoint median = points.get(mid);
        while (mid + 1 < hi && sameKey(points.get(mid + 1), median, splitByLatitude)) {
            mid++;
        }

        Node node = new Node(points.get(mid), depth);
        node.left = buildBalanced(points, lo, mid, depth + 1);
        node.right = buildBalanced(points, mid + 1, hi, depth + 1);
        node.size = 1 + size(node.left) + size(node.right);
        return node;
    }

    private static boolean sameKey(GeoPoint a, GeoPoint b, boolean splitByLatitude) {
        return splitByLatitude
                ? a.latitude() == b.latitude()
                : a.longitude() == b.longitude();
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

    private static final Comparator<GeoPoint> LATITUDE_ORDER =
            Comparator.comparingDouble(GeoPoint::latitude);
    private static final Comparator<GeoPoint> LONGITUDE_ORDER =
            Comparator.comparingDouble(GeoPoint::longitude);

    private static final class Node {
        final GeoPoint point;
        final int depth;

        /** Subtree point count, maintained on insert; drives scapegoat balance checks. */
        int size = 1;

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
