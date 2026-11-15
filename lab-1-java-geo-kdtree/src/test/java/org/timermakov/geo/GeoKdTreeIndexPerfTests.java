package org.timermakov.geo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throughput/latency smoke tests on a large dataset (port of the C# perf tests).
 * They only assert positive measurements — real numbers come from the JMH benchmarks.
 */
@Tag("perf")
class GeoKdTreeIndexPerfTests {

    private static final int DATASET_SIZE = 100_000;
    private static final int RADIUS_OPERATIONS = 10_000;
    private static final int KNN_OPERATIONS = 8_000;
    private static final double RADIUS_METERS = 2_000;
    private static final int KNN_K = 10;

    /** kNN query stride over the point list (mirrors the C# (i * 17) % count). */
    private static final int KNN_STRIDE = 17;

    /** Point generator longitude stride (mirrors the C# (i * 13) % 8192). */
    private static final int GENERATOR_LNG_STRIDE = 13;

    private static final double LAT_BASE = -89.0;
    private static final double LAT_SPAN = 178.0;
    private static final double LNG_BASE = -179.0;
    private static final double LNG_SPAN = 358.0;
    private static final int GENERATOR_LAT_MODULUS = 4_096;
    private static final double GENERATOR_LAT_DENOM = 4_095.0;
    private static final int GENERATOR_LNG_MODULUS = 8_192;
    private static final double GENERATOR_LNG_DENOM = 8_191.0;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;

    @Test
    void radiusQueryThroughputOnLargeDataset() {
        List<GeoPoint> points = generatePoints(DATASET_SIZE);
        GeoKdTreeIndex index = buildIndex(points);

        long startNanos = System.nanoTime();
        long hits = 0;
        for (int i = 0; i < RADIUS_OPERATIONS; i++) {
            GeoPoint point = points.get(i % points.size());
            hits += index.searchRadius(point.latitude(), point.longitude(), RADIUS_METERS).size();
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double throughput = RADIUS_OPERATIONS / (elapsedNanos / (double) NANOS_PER_SECOND);

        assertThat(hits).isGreaterThanOrEqualTo(0);
        assertThat(throughput).isPositive();
    }

    @Test
    void knnQueryLatencyOnLargeDataset() {
        List<GeoPoint> points = generatePoints(DATASET_SIZE);
        GeoKdTreeIndex index = buildIndex(points);

        long startNanos = System.nanoTime();
        double distanceSum = 0.0;
        for (int i = 0; i < KNN_OPERATIONS; i++) {
            GeoPoint point = points.get((i * KNN_STRIDE) % points.size());
            List<GeoSearchResult> result = index.searchKNearest(point.latitude(), point.longitude(), KNN_K);
            distanceSum += result.getFirst().distanceMeters();
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double avgLatencyUs = (elapsedNanos / (double) NANOS_PER_SECOND) / KNN_OPERATIONS * MICROS_PER_SECOND;

        assertThat(distanceSum).isGreaterThanOrEqualTo(0);
        assertThat(avgLatencyUs).isPositive();
    }

    private static GeoKdTreeIndex buildIndex(List<GeoPoint> points) {
        GeoKdTreeIndex index = new GeoKdTreeIndex();
        for (GeoPoint point : points) {
            index.insert(point);
        }

        return index;
    }

    private static List<GeoPoint> generatePoints(int count) {
        List<GeoPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double lat = LAT_BASE + (LAT_SPAN * (i % GENERATOR_LAT_MODULUS) / GENERATOR_LAT_DENOM);
            double lng = LNG_BASE + (LNG_SPAN * ((i * GENERATOR_LNG_STRIDE) % GENERATOR_LNG_MODULUS) / GENERATOR_LNG_DENOM);
            points.add(new GeoPoint("geo-" + i, lat, lng));
        }

        return points;
    }
}
