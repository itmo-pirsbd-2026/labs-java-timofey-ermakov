package org.timermakov.geo.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.timermakov.geo.GeoKdTreeIndex;
import org.timermakov.geo.GeoPoint;
import org.timermakov.geo.GeoSearchResult;

import java.util.Arrays;
import java.util.List;

/**
 * KD-tree kNN query vs full-scan baseline (port of the C# GeoKdTreeKnnBenchmarks).
 * Parameter values come from config/bench.settings.json via BenchMain.
 */
@State(Scope.Benchmark)
public class GeoKdTreeKnnBenchmarks {

    private static final int KD_TREE_OPERATIONS_PER_INVOKE = 128;
    private static final int FULL_SCAN_OPERATIONS_PER_INVOKE = 16;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 23;

    @Param({})
    int n;

    @Param({})
    int k;

    GeoKdTreeIndex index;
    List<GeoPoint> points = List.of();
    GeoPoint[] queries = {};
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() {
        points = BenchGeoMath.buildPoints(n, GENERATOR_LNG_STRIDE);
        queries = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);
        index = new GeoKdTreeIndex();
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
    public double queryKdTreeKnn() {
        double total = 0.0;
        for (int i = 0; i < KD_TREE_OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            List<GeoSearchResult> result = index.searchKNearest(query.latitude(), query.longitude(), k);
            total += result.getFirst().distanceMeters();
        }

        return total;
    }

    @Benchmark
    @OperationsPerInvocation(FULL_SCAN_OPERATIONS_PER_INVOKE)
    public double queryFullScanKnn() {
        double total = 0.0;
        for (int i = 0; i < FULL_SCAN_OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            total += fullScanKnn(query.latitude(), query.longitude(), k);
        }

        return total;
    }

    private double fullScanKnn(double lat, double lng, int kk) {
        double[] distances = new double[points.size()];
        for (int i = 0; i < distances.length; i++) {
            GeoPoint point = points.get(i);
            distances[i] = BenchGeoMath.haversineMeters(lat, lng, point.latitude(), point.longitude());
        }

        Arrays.sort(distances);
        double sum = 0.0;
        for (int i = 0; i < kk; i++) {
            sum += distances[i];
        }

        return sum;
    }
}
