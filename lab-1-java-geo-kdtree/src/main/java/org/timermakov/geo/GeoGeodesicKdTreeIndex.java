package org.timermakov.geo;

import java.util.ArrayList;
import java.util.Collections;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Geodesic-core KD-tree over lat/lng points (port of the C# <code>GeoKdTreeIndex</code>; kept as the
 * pre-ECEF reference implementation for experiments — the canonical index since the ECEF
 * migration is {@link GeoKdTreeIndex}).
 * Nodes alternate split axis by depth: even depth splits by latitude, odd by longitude.
 * Search prunes the far branch using a lower bound on the crossing distance
 * (see {@link GeoDistance#axisLowerBoundA}); unlike the C# baseline, longitude
 * splits are pruned too.
 *
 * <p>Comparisons and pruning run in a-space ({@code a = sin^2(d/2R)}, strictly monotone in
 * the distance), so the hot path avoids {@code sqrt}/{@code asin}; exact meters are computed
 * only for radius hits and accepted kNN candidates.
 */
public final class GeoGeodesicKdTreeIndex {

    /** Depth values with depth % SPLIT_AXIS_MODULUS == 0 split by latitude. */
    private static final int SPLIT_AXIS_MODULUS = 2;

    /** Absolute latitude limit, degrees. */
    private static final double LATITUDE_LIMIT = 90.0;

    /** Absolute longitude limit, degrees. */
    private static final double LONGITUDE_LIMIT = 180.0;

    /**
     * a-space comparisons bridge a meters round-trip (a → meters → a), which can drift a
     * few ulps; candidates within this margin of the worst entry fall through to the exact
     * meters comparison instead of being rejected in a-space.
     */
    private static final double A_COMPARISON_MARGIN_ULPS = 8.0;

    /**
     * Testing/benchmarking hook: when enabled, both search cores count every visited node
     * into {@link #PROFILING_VISITED_NODES}. Disabled by default; the hot path cost is a
     * single predictable branch per node.
     */
    public static volatile boolean PROFILING_COUNT_NODES = false;

    /** Accumulator for {@link #PROFILING_COUNT_NODES}, reset by the caller between runs. */
    public static long PROFILING_VISITED_NODES = 0;

    private final Map<String, GeoPoint> pointsById = new Object2ObjectOpenHashMap<>();
    private Node root;

    /** Number of points currently indexed. */
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

    /**
     * All indexed points within {@code radiusMeters} of the query position,
     * ordered by distance then id.
     */
    public List<GeoSearchResult> searchRadius(double latitude, double longitude, double radiusMeters) {
        validateCoordinate(latitude, longitude);
        if (radiusMeters < 0) {
            throw new IllegalArgumentException("Radius must be non-negative.");
        }

        if (root == null) {
            return List.of();
        }

        var radiusThreshold = GeoDistance.aFromMeters(radiusMeters);
        var queryCosLat = Math.cos(Math.toRadians(latitude));
        var results = new ArrayList<GeoSearchResult>();
        searchRadiusCore(root, latitude, longitude, radiusThreshold, queryCosLat, results);
        results.sort(GeoSearchResultComparators.NEAREST_FIRST);
        return results;
    }

    /**
     * The {@code k} nearest indexed points to the query position, ordered by distance then id.
     * Candidates are kept in a best list ordered worst-first; insertion is by binary search.
     */
    public List<GeoSearchResult> searchKNearest(double latitude, double longitude, int k) {
        validateCoordinate(latitude, longitude);
        if (k <= 0) {
            throw new IllegalArgumentException("K must be positive.");
        }

        if (root == null) {
            return List.of();
        }

        var queryCosLat = Math.cos(Math.toRadians(latitude));
        var best = new ArrayList<GeoSearchResult>(k);
        searchKNearestCore(root, latitude, longitude, k, queryCosLat, best);
        var results = new ArrayList<GeoSearchResult>(best);
        results.sort(GeoSearchResultComparators.NEAREST_FIRST);
        return results;
    }

    private static Node insertNode(Node node, GeoPoint point, int depth) {
        if (node == null) {
            return new Node(point, depth);
        }

        var splitByLatitude = depth % SPLIT_AXIS_MODULUS == 0;
        var goLeft = splitByLatitude
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
            double queryLat,
            double queryLng,
            double radiusThreshold,
            double queryCosLat,
            List<GeoSearchResult> results) {
        if (PROFILING_COUNT_NODES) {
            PROFILING_VISITED_NODES++;
        }

        var a = GeoDistance.haversineA(
                queryLat, queryLng, node.point.latitude(), node.point.longitude(), queryCosLat, node.cosLatitude);
        if (a <= radiusThreshold) {
            results.add(new GeoSearchResult(node.point, GeoDistance.metersFromA(a)));
        }

        var splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        var queryAxis = splitByLatitude ? queryLat : queryLng;
        var splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        var nearBranch = queryAxis < splitAxis ? node.left : node.right;
        var farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchRadiusCore(nearBranch, queryLat, queryLng, radiusThreshold, queryCosLat, results);
        }

        if (farBranch == null) {
            return;
        }

        var axisLowerBound = GeoDistance.axisLowerBoundA(queryLat, queryLng, splitAxis, splitByLatitude, queryCosLat);
        if (axisLowerBound <= radiusThreshold) {
            searchRadiusCore(farBranch, queryLat, queryLng, radiusThreshold, queryCosLat, results);
        }
    }

    private static void searchKNearestCore(
            Node node,
            double queryLat,
            double queryLng,
            int k,
            double queryCosLat,
            List<GeoSearchResult> best) {
        if (PROFILING_COUNT_NODES) {
            PROFILING_VISITED_NODES++;
        }

        var a = GeoDistance.haversineA(
                queryLat, queryLng, node.point.latitude(), node.point.longitude(), queryCosLat, node.cosLatitude);
        tryAddBest(best, node.point, a, k);

        var splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        var queryAxis = splitByLatitude ? queryLat : queryLng;
        var splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        var nearBranch = queryAxis < splitAxis ? node.left : node.right;
        var farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchKNearestCore(nearBranch, queryLat, queryLng, k, queryCosLat, best);
        }

        if (farBranch == null) {
            return;
        }

        var axisLowerBound = GeoDistance.axisLowerBoundA(queryLat, queryLng, splitAxis, splitByLatitude, queryCosLat);
        if (best.size() < k || axisLowerBound <= worstA(best)) {
            searchKNearestCore(farBranch, queryLat, queryLng, k, queryCosLat, best);
        }
    }

    /**
     * Accepts a candidate into the best list based on its a-space term. The list stays
     * ordered worst-first by exact meters (insertion via binary search); exact meters are
     * computed only when a candidate is accepted.
     */
    private static void tryAddBest(List<GeoSearchResult> best, GeoPoint point, double a, int k) {
        if (best.size() < k) {
            insertSorted(best, new GeoSearchResult(point, GeoDistance.metersFromA(a)));
            return;
        }

        GeoSearchResult worst = best.getFirst();
        var worstA = GeoDistance.aFromMeters(worst.distanceMeters());
        var margin = Math.ulp(worstA) * A_COMPARISON_MARGIN_ULPS;
        if (a > worstA + margin) {
            return;
        }

        // Borderline candidate: decide exactly, in meters, with the id tie-break.
        GeoSearchResult candidate = new GeoSearchResult(point, GeoDistance.metersFromA(a));
        if (!isStrictlyBetter(candidate, worst)) {
            return;
        }

        best.removeFirst();
        insertSorted(best, candidate);
    }

    /** a-space term of the current worst entry (the list head, ordered worst-first). */
    private static double worstA(List<GeoSearchResult> best) {
        return GeoDistance.aFromMeters(best.getFirst().distanceMeters());
    }

    /**
     * Keeps the best list ordered worst-first by inserting at the binary-search position
     * instead of re-sorting the whole list for every candidate.
     */
    private static void insertSorted(List<GeoSearchResult> best, GeoSearchResult candidate) {
        var index = Collections.binarySearch(best, candidate, GeoSearchResultComparators.WORST_FIRST);
        if (index < 0) {
            index = -index - 1;
        }
        best.add(index, candidate);
    }

    private static boolean isStrictlyBetter(GeoSearchResult candidate, GeoSearchResult currentWorst) {
        var byDistance = Double.compare(candidate.distanceMeters(), currentWorst.distanceMeters());
        if (byDistance != 0) {
            return byDistance < 0;
        }

        return candidate.point().id().compareTo(currentWorst.point().id()) < 0;
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

        /** cos of the point latitude in radians — fixed per node, reused by every search. */
        final double cosLatitude;

        Node left;
        Node right;

        Node(GeoPoint point, int depth) {
            this.point = point;
            this.depth = depth;
            this.cosLatitude = Math.cos(Math.toRadians(point.latitude()));
        }
    }
}
