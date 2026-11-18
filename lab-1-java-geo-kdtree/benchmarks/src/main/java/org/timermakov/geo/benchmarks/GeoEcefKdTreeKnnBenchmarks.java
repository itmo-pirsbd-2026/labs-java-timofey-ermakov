package org.timermakov.geo.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.timermakov.geo.GeoEcefKdTreeIndex;
import org.timermakov.geo.GeoPoint;
import org.timermakov.geo.GeoSearchResult;

import java.util.Arrays;
import java.util.List;

/**
 * Experimental ECEF-core kNN benchmark — twin of {@link GeoKdTreeKnnBenchmarks}
 * with the index swapped. Same generator (lng stride 23), query set and batches.
 */
@State(Scope.Benchmark)
public class GeoEcefKdTreeKnnBenchmarks {

    private static final int KD_TREE_OPERATIONS_PER_INVOKE = 128;
    private static final int FULL_SCAN_OPERATIONS_PER_INVOKE = 16;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 23;

    @Param({})
    int n;

    @Param({})
    int k;

    GeoEcefKdTreeIndex index;
    List<GeoPoint> points = List.of();
    GeoPoint[] queries = {};
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() {
        points = BenchGeoMath.buildPoints(n, GENERATOR_LNG_STRIDE);
        queries = Arrays.copyOf(points.toArray(GeoPoint[]::new), Math.min(points.size(), QUERY_LIMIT));
        index = new GeoEcefKdTreeIndex();
        for (GeoPoint point : points) {
            index.insert(point);
        }
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        cursor = 0;
    }

    @Benchmark
    @OperationsPerInvocation(KD_TREE_OPERATIONS_PER_INVOKE)
    public double queryEcefKdTreeKnn() {
        double total = 0;
        for (int i = 0; i < KD_TREE_OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            List<GeoSearchResult> nearest = index.searchKNearest(query.latitude(), query.longitude(), k);
            total += nearest.isEmpty() ? 0 : nearest.getFirst().distanceMeters();
        }

        return total;
    }

    @Benchmark
    @OperationsPerInvocation(FULL_SCAN_OPERATIONS_PER_INVOKE)
    public double queryFullScanKnn() {
        double total = 0;
        for (int i = 0; i < FULL_SCAN_OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            total += fullScanKnn(query.latitude(), query.longitude(), k);
        }

        return total;
    }

    private double fullScanKnn(double lat, double lng, int k) {
        double[] nearest = new double[k];
        int found = 0;
        for (int i = 0; i < points.size(); i++) {
            GeoPoint point = points.get(i);
            double distance = BenchGeoMath.haversineMeters(lat, lng, point.latitude(), point.longitude());
            if (found < k) {
                nearest[found++] = distance;
            } else {
                int worst = 0;
                for (int j = 1; j < k; j++) {
                    if (nearest[j] > nearest[worst]) {
                        worst = j;
                    }
                }
                if (distance < nearest[worst]) {
                    nearest[worst] = distance;
                }
            }
        }

        double sum = 0;
        for (int i = 0; i < found; i++) {
            sum += nearest[i];
        }

        return sum;
    }
}
