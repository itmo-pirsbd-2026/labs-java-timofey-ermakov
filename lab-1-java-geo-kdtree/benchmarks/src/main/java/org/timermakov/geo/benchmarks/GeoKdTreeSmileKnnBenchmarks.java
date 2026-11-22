package org.timermakov.geo.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.timermakov.geo.GeoPoint;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

import java.util.List;

/**
 * kNN query of the public Smile {@link KDTree} on ECEF-3D points. Query keys are
 * standalone arrays (not shared with stored keys), so Smile's reference-equality
 * self-exclusion does not fire and the neighborhood matches the in-house index
 * semantics (the query point itself competes like any other point).
 */
@State(Scope.Benchmark)
public class GeoKdTreeSmileKnnBenchmarks {

    private static final int OPERATIONS_PER_INVOKE = 128;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 23;

    @Param({})
    int n;

    @Param({})
    int k;

    KDTree<Integer> index;
    double[][] queries;
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() {
        List<GeoPoint> points = BenchGeoMath.buildPoints(n, GENERATOR_LNG_STRIDE);
        double[][] keys = new double[points.size()][];
        Integer[] data = new Integer[points.size()];
        double[] ecef = new double[3];
        for (int i = 0; i < points.size(); i++) {
            GeoPoint point = points.get(i);
            BenchEcefMath.toEcef(point.latitude(), point.longitude(), BenchGeoMath.EARTH_RADIUS_METERS, ecef);
            keys[i] = new double[] {ecef[0], ecef[1], ecef[2]};
            data[i] = i;
        }

        index = new KDTree<>(keys, data);

        GeoPoint[] queryPoints = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);
        queries = new double[queryPoints.length][];
        for (int i = 0; i < queryPoints.length; i++) {
            GeoPoint point = queryPoints[i];
            double[] q = new double[3];
            BenchEcefMath.toEcef(point.latitude(), point.longitude(), BenchGeoMath.EARTH_RADIUS_METERS, q);
            queries[i] = q;
        }
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        cursor = 0;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOKE)
    public int querySmileKnn() {
        int total = 0;
        for (int i = 0; i < OPERATIONS_PER_INVOKE; i++) {
            double[] query = queries[cursor++ % queries.length];
            Neighbor<double[], Integer>[] neighbors = index.search(query, k);
            total += neighbors.length;
        }

        return total;
    }
}
