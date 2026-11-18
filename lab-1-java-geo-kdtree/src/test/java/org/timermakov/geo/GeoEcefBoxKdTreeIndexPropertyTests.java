package org.timermakov.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Property tests for the experimental {@link GeoEcefBoxKdTreeIndex}: the chord-metric with bounding boxes
 * variant must match the same independent full-scan haversine oracle as
 * {@link GeoKdTreeIndexPropertyTests}. Deterministic, mirrors the C# FsCheck suite.
 */
class GeoEcefBoxKdTreeIndexPropertyTests {

    private static final int PROPERTY_CASES = 40;
    private static final long PROPERTY_BASE_SEED = 42L;
    private static final int MAX_RAW_VALUES_LENGTH = 1_200;

    private static final int RADIUS_PROPERTY_MAX_POINTS = 300;
    private static final int KNN_PROPERTY_MAX_POINTS = 350;

    private static final int BASE_RADIUS_METERS = 5_000;
    private static final int RADIUS_MULTIPLIER = 97;
    private static final int RADIUS_MODULUS = 150_000;

    private static final int K_FRACTION = 20;
    private static final int K_LIMIT = 50;

    private static final double DISTANCE_TOLERANCE_METERS = 1e-6;

    private static final double NORM_LAT_MIN = -89.9;
    private static final double NORM_LAT_MAX = 89.9;
    private static final double NORM_LNG_MIN = -179.9;
    private static final double NORM_LNG_MAX = 179.9;

    private static final double INT_VALUE_SPAN = 4294967295.0;

    private static final double MOSCOW_LATITUDE = 55.7558;
    private static final double MOSCOW_LONGITUDE = 37.6176;
    private static final double SAINT_PETERSBURG_LATITUDE = 59.9343;
    private static final double SAINT_PETERSBURG_LONGITUDE = 30.3351;
    private static final double SAMPLE_RADIUS_METERS = 1_000;
    private static final int SAMPLE_K = 10;

    private static final Comparator<GeoSearchResult> EXPECTED_ORDER =
            Comparator.comparingDouble(GeoSearchResult::distanceMeters)
                    .thenComparing(result -> result.point().id());

    static Stream<int[]> randomRawValues() {
        return IntStream.rangeClosed(1, PROPERTY_CASES)
                .mapToObj(caseIndex -> {
                    Random random = new Random(PROPERTY_BASE_SEED + caseIndex);
                    int length = random.nextInt(MAX_RAW_VALUES_LENGTH + 1);
                    int[] values = new int[length];
                    for (int i = 0; i < length; i++) {
                        values[i] = random.nextInt();
                    }
                    return values;
                });
    }

    @ParameterizedTest
    @MethodSource("randomRawValues")
    void radiusQueryMatchesFullScan(int[] rawValues) {
        List<GeoPoint> points = buildPoints(rawValues, RADIUS_PROPERTY_MAX_POINTS);
        if (points.isEmpty()) {
            return;
        }

        GeoEcefBoxKdTreeIndex index = buildIndex(points);
        GeoPoint query = points.get(points.size() / 2);
        double radiusMeters = BASE_RADIUS_METERS + ((points.size() * RADIUS_MULTIPLIER) % RADIUS_MODULUS);

        List<GeoSearchResult> actual = index.searchRadius(query.latitude(), query.longitude(), radiusMeters);
        List<GeoSearchResult> expected = fullScanRadius(points, query.latitude(), query.longitude(), radiusMeters);
        assertEquivalent(expected, actual);
    }

    @ParameterizedTest
    @MethodSource("randomRawValues")
    void knnQueryMatchesFullScan(int[] rawValues) {
        List<GeoPoint> points = buildPoints(rawValues, KNN_PROPERTY_MAX_POINTS);
        if (points.isEmpty()) {
            return;
        }

        GeoEcefBoxKdTreeIndex index = buildIndex(points);
        GeoPoint query = points.get(points.size() / 3);
        int k = Math.clamp(points.size() / K_FRACTION + 1, 1, Math.min(K_LIMIT, points.size()));

        List<GeoSearchResult> actual = index.searchKNearest(query.latitude(), query.longitude(), k);
        List<GeoSearchResult> expected = fullScanKnn(points, query.latitude(), query.longitude(), k);
        assertEquivalent(expected, actual);
    }

    @Test
    void insertDuplicateIdThrows() {
        GeoEcefBoxKdTreeIndex index = new GeoEcefBoxKdTreeIndex();
        index.insert(new GeoPoint("dup", MOSCOW_LATITUDE, MOSCOW_LONGITUDE));

        assertThatThrownBy(() -> index.insert(
                new GeoPoint("dup", SAINT_PETERSBURG_LATITUDE, SAINT_PETERSBURG_LONGITUDE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyIndexReturnsEmptyResults() {
        GeoEcefBoxKdTreeIndex index = new GeoEcefBoxKdTreeIndex();

        assertThat(index.searchRadius(MOSCOW_LATITUDE, MOSCOW_LONGITUDE, SAMPLE_RADIUS_METERS)).isEmpty();
        assertThat(index.searchKNearest(MOSCOW_LATITUDE, MOSCOW_LONGITUDE, SAMPLE_K)).isEmpty();
    }

    private static GeoEcefBoxKdTreeIndex buildIndex(List<GeoPoint> points) {
        GeoEcefBoxKdTreeIndex index = new GeoEcefBoxKdTreeIndex();
        for (GeoPoint point : points) {
            index.insert(point);
        }

        return index;
    }

    private static List<GeoPoint> buildPoints(int[] rawValues, int maxCount) {
        int limit = Math.min(maxCount, rawValues.length / 2);
        List<GeoPoint> points = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int latRaw = rawValues[i * 2];
            int lngRaw = rawValues[i * 2 + 1];
            double lat = normalize(latRaw, NORM_LAT_MIN, NORM_LAT_MAX);
            double lng = normalize(lngRaw, NORM_LNG_MIN, NORM_LNG_MAX);
            points.add(new GeoPoint("p-" + i, lat, lng));
        }

        return points;
    }

    private static List<GeoSearchResult> fullScanRadius(
            List<GeoPoint> points,
            double queryLat,
            double queryLng,
            double radiusMeters) {
        return points.stream()
                .map(point -> new GeoSearchResult(
                        point, haversineMeters(queryLat, queryLng, point.latitude(), point.longitude())))
                .filter(result -> result.distanceMeters() <= radiusMeters)
                .sorted(EXPECTED_ORDER)
                .toList();
    }

    private static List<GeoSearchResult> fullScanKnn(
            List<GeoPoint> points,
            double queryLat,
            double queryLng,
            int k) {
        return points.stream()
                .map(point -> new GeoSearchResult(
                        point, haversineMeters(queryLat, queryLng, point.latitude(), point.longitude())))
                .sorted(EXPECTED_ORDER)
                .limit(k)
                .toList();
    }

    private static void assertEquivalent(List<GeoSearchResult> expected, List<GeoSearchResult> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).point().id()).isEqualTo(expected.get(i).point().id());
            assertThat(actual.get(i).distanceMeters())
                    .isCloseTo(expected.get(i).distanceMeters(), within(DISTANCE_TOLERANCE_METERS));
        }
    }

    private static double normalize(int value, double min, double max) {
        double unit = (value - (double) Integer.MIN_VALUE) / INT_VALUE_SPAN;
        return min + (max - min) * unit;
    }

    private static final double ORACLE_EARTH_RADIUS_METERS = 6_371_000.0;

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = degreesToRadians(lat2 - lat1);
        double dLng = degreesToRadians(lng2 - lng1);
        double rLat1 = degreesToRadians(lat1);
        double rLat2 = degreesToRadians(lat2);

        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double a = sinLat * sinLat + Math.cos(rLat1) * Math.cos(rLat2) * sinLng * sinLng;
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return ORACLE_EARTH_RADIUS_METERS * c;
    }

    private static double degreesToRadians(double value) {
        return value * (Math.PI / 180.0);
    }
}
