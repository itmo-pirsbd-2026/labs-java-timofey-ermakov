package org.timermakov.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory KD-tree index over lat/lng points (port of the C# <code>GeoKdTreeIndex</code>).
 * Nodes alternate split axis by depth: even depth splits by latitude, odd by longitude.
 * Search prunes the far branch using a lower bound on the crossing distance
 * (see {@link GeoDistance#axisLowerBoundA}); unlike the C# baseline, longitude
 * splits are pruned too.
 *
 * <p>Comparisons and pruning run in a-space ({@code a = sin^2(d/2R)}, strictly monotone in
 * the distance), so the hot path avoids {@code sqrt}/{@code asin}; exact meters are computed
 * only for radius hits and accepted kNN candidates.
 */
public final class GeoKdTreeIndex {

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

    private final Map<String, GeoPoint> pointsById = new HashMap<>();
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

        double radiusThreshold = GeoDistance.aFromMeters(radiusMeters);
        List<GeoSearchResult> results = new ArrayList<>();
        searchRadiusCore(root, latitude, longitude, radiusThreshold, results);
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

        List<GeoSearchResult> best = new ArrayList<>(k);
        searchKNearestCore(root, latitude, longitude, k, best);
        List<GeoSearchResult> results = new ArrayList<>(best);
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
            double queryLat,
            double queryLng,
            double radiusThreshold,
            List<GeoSearchResult> results) {
        double a = GeoDistance.haversineA(queryLat, queryLng, node.point.latitude(), node.point.longitude());
        if (a <= radiusThreshold) {
            results.add(new GeoSearchResult(node.point, GeoDistance.metersFromA(a)));
        }

        boolean splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        double queryAxis = splitByLatitude ? queryLat : queryLng;
        double splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        Node nearBranch = queryAxis < splitAxis ? node.left : node.right;
        Node farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchRadiusCore(nearBranch, queryLat, queryLng, radiusThreshold, results);
        }

        if (farBranch == null) {
            return;
        }

        double axisLowerBound = GeoDistance.axisLowerBoundA(queryLat, queryLng, splitAxis, splitByLatitude);
        if (axisLowerBound <= radiusThreshold) {
            searchRadiusCore(farBranch, queryLat, queryLng, radiusThreshold, results);
        }
    }

    private static void searchKNearestCore(
            Node node,
            double queryLat,
            double queryLng,
            int k,
            List<GeoSearchResult> best) {
        double a = GeoDistance.haversineA(queryLat, queryLng, node.point.latitude(), node.point.longitude());
        tryAddBest(best, node.point, a, k);

        boolean splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        double queryAxis = splitByLatitude ? queryLat : queryLng;
        double splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        Node nearBranch = queryAxis < splitAxis ? node.left : node.right;
        Node farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchKNearestCore(nearBranch, queryLat, queryLng, k, best);
        }

        if (farBranch == null) {
            return;
        }

        double axisLowerBound = GeoDistance.axisLowerBoundA(queryLat, queryLng, splitAxis, splitByLatitude);
        if (best.size() < k || axisLowerBound <= worstA(best)) {
            searchKNearestCore(farBranch, queryLat, queryLng, k, best);
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
        double worstA = GeoDistance.aFromMeters(worst.distanceMeters());
        double margin = Math.ulp(worstA) * A_COMPARISON_MARGIN_ULPS;
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
        int index = Collections.binarySearch(best, candidate, GeoSearchResultComparators.WORST_FIRST);
        if (index < 0) {
            index = -index - 1;
        }
        best.add(index, candidate);
    }

    private static boolean isStrictlyBetter(GeoSearchResult candidate, GeoSearchResult currentWorst) {
        int byDistance = Double.compare(candidate.distanceMeters(), currentWorst.distanceMeters());
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
        Node left;
        Node right;

        Node(GeoPoint point, int depth) {
            this.point = point;
            this.depth = depth;
        }
    }
}
