package org.timermakov.geo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Scapegoat balance guarantees: sorted insertion must keep the tree near-balanced
 * (H6 follow-up) while queries stay correct against the full-scan oracle.
 */
class GeoKdTreeIndexBalanceTests {

    private static final int SORTED_LOAD_POINTS = 20_000;
    private static final double BALANCE_ALPHA = 0.7;
    private static final double ORACLE_RADIUS_METERS = 5_000.0;
    private static final int ORACLE_K = 10;
    private static final double DISTANCE_TOLERANCE_METERS = 1e-6;

    @Test
    void sortedByLatitudeLoadStaysBalanced() {
        GeoKdTreeIndex index = new GeoKdTreeIndex();
        for (GeoPoint point : sortedPoints(false)) {
            index.insert(point);
        }

        assertThat(index.count()).isEqualTo(SORTED_LOAD_POINTS);
        int bound = (int) (Math.log(SORTED_LOAD_POINTS) / Math.log(1.0 / BALANCE_ALPHA)) + 2;
        assertThat(index.maxDepth()).isLessThanOrEqualTo(bound);
    }

    @Test
    void sortedByLongitudeLoadStaysBalanced() {
        GeoKdTreeIndex index = new GeoKdTreeIndex();
        for (GeoPoint point : sortedPoints(true)) {
            index.insert(point);
        }

        int bound = (int) (Math.log(SORTED_LOAD_POINTS) / Math.log(1.0 / BALANCE_ALPHA)) + 2;
        assertThat(index.maxDepth()).isLessThanOrEqualTo(bound);
    }

    @Test
    void sortedLoadQueriesMatchFullScan() {
        List<GeoPoint> points = sortedPoints(false);
        GeoKdTreeIndex index = new GeoKdTreeIndex();
        for (GeoPoint point : points) {
            index.insert(point);
        }

        List<GeoPoint> queries = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            queries.add(points.get(random.nextInt(points.size())));
        }
        queries.add(points.get(0));
        queries.add(points.get(points.size() / 2));
        queries.add(points.get(points.size() - 1));

        for (GeoPoint query : queries) {
            assertKnnMatchesOracle(points, index, query);
            assertRadiusMatchesOracle(points, index, query);
        }
    }

    private static List<GeoPoint> sortedPoints(boolean byLongitude) {
        Random random = new Random(3);
        List<GeoPoint> points = new ArrayList<>(SORTED_LOAD_POINTS);
        for (int i = 0; i < SORTED_LOAD_POINTS; i++) {
            double lat = -89 + 178 * random.nextDouble();
            double lng = -179 + 358 * random.nextDouble();
            points.add(new GeoPoint("s-" + i, lat, lng));
        }
        points.sort(byLongitude
                ? Comparator.comparingDouble(GeoPoint::longitude)
                : Comparator.comparingDouble(GeoPoint::latitude));
        return points;
    }

    private static void assertKnnMatchesOracle(List<GeoPoint> points, GeoKdTreeIndex index, GeoPoint query) {
        List<GeoSearchResult> actual = index.searchKNearest(query.latitude(), query.longitude(), ORACLE_K);
        List<GeoSearchResult> expected = points.stream()
                .map(p -> new GeoSearchResult(p, haversineMeters(query.latitude(), query.longitude(), p.latitude(), p.longitude())))
                .sorted(Comparator.comparingDouble(GeoSearchResult::distanceMeters).thenComparing(r -> r.point().id()))
                .limit(ORACLE_K)
                .toList();

        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).point().id()).isEqualTo(expected.get(i).point().id());
            assertThat(actual.get(i).distanceMeters())
                    .isCloseTo(expected.get(i).distanceMeters(), within(DISTANCE_TOLERANCE_METERS));
        }
    }

    private static void assertRadiusMatchesOracle(List<GeoPoint> points, GeoKdTreeIndex index, GeoPoint query) {
        List<GeoSearchResult> actual = index.searchRadius(query.latitude(), query.longitude(), ORACLE_RADIUS_METERS);
        List<GeoSearchResult> expected = points.stream()
                .map(p -> new GeoSearchResult(p, haversineMeters(query.latitude(), query.longitude(), p.latitude(), p.longitude())))
                .filter(r -> r.distanceMeters() <= ORACLE_RADIUS_METERS)
                .sorted(Comparator.comparingDouble(GeoSearchResult::distanceMeters).thenComparing(r -> r.point().id()))
                .toList();

        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).point().id()).isEqualTo(expected.get(i).point().id());
            assertThat(actual.get(i).distanceMeters())
                    .isCloseTo(expected.get(i).distanceMeters(), within(DISTANCE_TOLERANCE_METERS));
        }
    }

    private static final double ORACLE_EARTH_RADIUS_METERS = 6_371_000.0;

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);
        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double a = sinLat * sinLat + Math.cos(rLat1) * Math.cos(rLat2) * sinLng * sinLng;
        return ORACLE_EARTH_RADIUS_METERS * 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
