package org.timermakov.geo.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.timermakov.geo.GeoGeodesicKdTreeIndex;
import org.timermakov.geo.GeoPoint;

import java.util.Arrays;
import java.util.List;

/**
 * KD-tree radius query vs full-scan baseline (port of the C# GeoKdTreeRadiusBenchmarks).
 * Parameter values come from config/bench.settings.json via BenchMain.
 */
@State(Scope.Benchmark)
public class GeoGeodesicRadiusBenchmarks {

    private static final int KD_TREE_OPERATIONS_PER_INVOKE = 128;
    private static final int FULL_SCAN_OPERATIONS_PER_INVOKE = 32;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 17;

    @Param({})
    int n;

    @Param({})
    int radiusMeters;

    GeoGeodesicKdTreeIndex index;
    List<GeoPoint> points = List.of();
    GeoPoint[] queries = {};
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() {
        points = BenchGeoMath.buildClusteredPoints(n);
        queries = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);
        index = new GeoGeodesicKdTreeIndex();
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
    public int queryGeodesicRadius() {
        int total = 0;
        for (int i = 0; i < KD_TREE_OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            total += index.searchRadius(query.latitude(), query.longitude(), radiusMeters).size();
        }

        return total;
    }

    @Benchmark
    @OperationsPerInvocation(FULL_SCAN_OPERATIONS_PER_INVOKE)
    public int queryFullScanRadius() {
        int total = 0;
        for (int i = 0; i < FULL_SCAN_OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            total += fullScanRadius(query.latitude(), query.longitude(), radiusMeters);
        }

        return total;
    }

    private int fullScanRadius(double lat, double lng, double radius) {
        int hits = 0;
        for (int i = 0; i < points.size(); i++) {
            GeoPoint point = points.get(i);
            if (BenchGeoMath.haversineMeters(lat, lng, point.latitude(), point.longitude()) <= radius) {
                hits++;
            }
        }

        return hits;
    }
}
