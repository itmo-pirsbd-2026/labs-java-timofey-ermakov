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
        var points = generatePoints(DATASET_SIZE);
        GeoKdTreeIndex index = buildIndex(points);

        var startNanos = System.nanoTime();
        var hits = 0;
        for (var i = 0; i < RADIUS_OPERATIONS; i++) {
            var point = points.get(i % points.size());
            hits += index.searchRadius(point.latitude(), point.longitude(), RADIUS_METERS).size();
        }
        var elapsedNanos = System.nanoTime() - startNanos;

        var throughput = RADIUS_OPERATIONS / (elapsedNanos / (double) NANOS_PER_SECOND);

        assertThat(hits).isGreaterThanOrEqualTo(0);
        assertThat(throughput).isPositive();
    }

    @Test
    void knnQueryLatencyOnLargeDataset() {
        var points = generatePoints(DATASET_SIZE);
        GeoKdTreeIndex index = buildIndex(points);

        var startNanos = System.nanoTime();
        var distanceSum = 0.0;
        for (var i = 0; i < KNN_OPERATIONS; i++) {
            var point = points.get((i * KNN_STRIDE) % points.size());
            var result = index.searchKNearest(point.latitude(), point.longitude(), KNN_K);
            distanceSum += result.getFirst().distanceMeters();
        }
        var elapsedNanos = System.nanoTime() - startNanos;

        var avgLatencyUs = (elapsedNanos / (double) NANOS_PER_SECOND) / KNN_OPERATIONS * MICROS_PER_SECOND;

        assertThat(distanceSum).isGreaterThanOrEqualTo(0);
        assertThat(avgLatencyUs).isPositive();
    }

    private static GeoKdTreeIndex buildIndex(List<GeoPoint> points) {
        GeoKdTreeIndex index = new GeoKdTreeIndex();
        for (var point : points) {
            index.insert(point);
        }

        return index;
    }

    private static List<GeoPoint> generatePoints(int count) {
        var points = new ArrayList<GeoPoint>(count);
        for (var i = 0; i < count; i++) {
            var lat = LAT_BASE + (LAT_SPAN * (i % GENERATOR_LAT_MODULUS) / GENERATOR_LAT_DENOM);
            var lng = LNG_BASE + (LNG_SPAN * ((i * GENERATOR_LNG_STRIDE) % GENERATOR_LNG_MODULUS) / GENERATOR_LNG_DENOM);
            points.add(new GeoPoint("geo-" + i, lat, lng));
        }

        return points;
    }
}
