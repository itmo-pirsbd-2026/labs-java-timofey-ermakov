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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Radius query of the public Smile {@link KDTree} on ECEF-3D points vs the same
 * synthetic dataset the in-house index benchmarks use (port parity: same generator,
 * same query set). The arc radius converts to the exact chord radius.
 */
@State(Scope.Benchmark)
public class GeoKdTreeSmileRadiusBenchmarks {

    private static final int OPERATIONS_PER_INVOKE = 128;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 17;

    @Param({})
    int n;

    @Param({})
    int radiusMeters;

    KDTree<Integer> index;
    double[][] queries;
    double chordRadius;
    List<smile.neighbor.Neighbor<double[], Integer>> scratch;
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

        int queryCount = Math.min(points.size(), QUERY_LIMIT);
        queries = new double[queryCount][];
        for (int i = 0; i < queryCount; i++) {
            GeoPoint point = points.get(i);
            double[] q = new double[3];
            BenchEcefMath.toEcef(point.latitude(), point.longitude(), BenchGeoMath.EARTH_RADIUS_METERS, q);
            queries[i] = q;
        }

        chordRadius = BenchEcefMath.chordRadius(radiusMeters, BenchGeoMath.EARTH_RADIUS_METERS);
        scratch = new ArrayList<>();
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        cursor = 0;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOKE)
    public int querySmileRadius() {
        int total = 0;
        for (int i = 0; i < OPERATIONS_PER_INVOKE; i++) {
            double[] query = queries[cursor++ % queries.length];
            scratch.clear();
            index.search(query, chordRadius, scratch);
            total += scratch.size();
        }

        return total;
    }
}
