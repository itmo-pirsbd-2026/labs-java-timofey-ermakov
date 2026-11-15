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
 * (see {@link GeoDistance#axisLowerBoundMeters}); unlike the C# baseline, longitude
 * splits are pruned too.
 */
public final class GeoKdTreeIndex {

    /** Depth values with depth % SPLIT_AXIS_MODULUS == 0 split by latitude. */
    private static final int SPLIT_AXIS_MODULUS = 2;

    /** Absolute latitude limit, degrees. */
    private static final double LATITUDE_LIMIT = 90.0;

    /** Absolute longitude limit, degrees. */
    private static final double LONGITUDE_LIMIT = 180.0;

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
     * ordered by distance then id. Baseline: results are fully sorted.
     */
    public List<GeoSearchResult> searchRadius(double latitude, double longitude, double radiusMeters) {
        validateCoordinate(latitude, longitude);
        if (radiusMeters < 0) {
            throw new IllegalArgumentException("Radius must be non-negative.");
        }

        if (root == null) {
            return List.of();
        }

        List<GeoSearchResult> results = new ArrayList<>();
        searchRadiusCore(root, latitude, longitude, radiusMeters, results);
        results.sort(GeoSearchResultComparators.NEAREST_FIRST);
        return results;
    }

    /**
     * The {@code k} nearest indexed points to the query position, ordered by distance then id.
     * Baseline: candidates are kept in a re-sorted best list (no heap) and results are re-sorted.
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
            double radiusMeters,
            List<GeoSearchResult> results) {
        double distance = GeoDistance.haversineMeters(
                queryLat, queryLng, node.point.latitude(), node.point.longitude());
        if (distance <= radiusMeters) {
            results.add(new GeoSearchResult(node.point, distance));
        }

        boolean splitByLatitude = node.depth % SPLIT_AXIS_MODULUS == 0;
        double queryAxis = splitByLatitude ? queryLat : queryLng;
        double splitAxis = splitByLatitude ? node.point.latitude() : node.point.longitude();
        Node nearBranch = queryAxis < splitAxis ? node.left : node.right;
        Node farBranch = queryAxis < splitAxis ? node.right : node.left;

        if (nearBranch != null) {
            searchRadiusCore(nearBranch, queryLat, queryLng, radiusMeters, results);
        }

        if (farBranch == null) {
            return;
        }

        double axisLowerBound = GeoDistance.axisLowerBoundMeters(queryLat, queryLng, splitAxis, splitByLatitude);
        if (axisLowerBound <= radiusMeters) {
            searchRadiusCore(farBranch, queryLat, queryLng, radiusMeters, results);
        }
    }

    private static void searchKNearestCore(
            Node node,
            double queryLat,
            double queryLng,
            int k,
            List<GeoSearchResult> best) {
        double distance = GeoDistance.haversineMeters(
                queryLat, queryLng, node.point.latitude(), node.point.longitude());
        tryAddBest(best, node.point, distance, k);

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

        double axisLowerBound = GeoDistance.axisLowerBoundMeters(queryLat, queryLng, splitAxis, splitByLatitude);
        if (best.size() < k || axisLowerBound <= currentWorstDistance(best)) {
            searchKNearestCore(farBranch, queryLat, queryLng, k, best);
        }
    }

    private static void tryAddBest(List<GeoSearchResult> best, GeoPoint point, double distanceMeters, int k) {
        GeoSearchResult candidate = new GeoSearchResult(point, distanceMeters);
        if (best.size() < k) {
            insertSorted(best, candidate);
            return;
        }

        GeoSearchResult worst = best.getFirst();
        if (!isStrictlyBetter(candidate, worst)) {
            return;
        }

        best.removeFirst();
        insertSorted(best, candidate);
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

    private static double currentWorstDistance(List<GeoSearchResult> best) {
        return best.isEmpty() ? Double.POSITIVE_INFINITY : best.getFirst().distanceMeters();
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
