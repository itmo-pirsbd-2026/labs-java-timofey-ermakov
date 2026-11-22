package org.timermakov.geo.benchmarks;

import com.google.common.geometry.S2ClosestPointQuery;
import com.google.common.geometry.S2Point;
import com.google.common.geometry.S2PointIndex;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.timermakov.geo.GeoPoint;

import java.util.List;

/**
 * kNN query of Google S2 ({@link S2PointIndex} + {@link S2ClosestPointQuery} with
 * {@code setMaxPoints(k)}) — twin of the Smile kNN benchmark on the same data.
 */
@State(Scope.Benchmark)
public class GeoS2KnnBenchmarks {

    private static final int OPERATIONS_PER_INVOKE = 128;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 23;

    @Param({})
    int n;

    @Param({})
    int k;

    S2PointIndex<GeoPoint> index;
    S2Point[] queries;
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() {
        List<GeoPoint> points = BenchGeoMath.buildPoints(n, GENERATOR_LNG_STRIDE);
        index = new S2PointIndex<>();
        for (GeoPoint point : points) {
            index.add(GeoS2RadiusBenchmarks.toS2(point.latitude(), point.longitude()), point);
        }

        GeoPoint[] queryPoints = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);
        queries = new S2Point[queryPoints.length];
        for (int i = 0; i < queryPoints.length; i++) {
            GeoPoint point = queryPoints[i];
            queries[i] = GeoS2RadiusBenchmarks.toS2(point.latitude(), point.longitude());
        }
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        cursor = 0;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOKE)
    public int queryS2Knn() {
        S2ClosestPointQuery<GeoPoint> query = new S2ClosestPointQuery<>(index);
        query.setMaxPoints(k);
        int total = 0;
        for (int i = 0; i < OPERATIONS_PER_INVOKE; i++) {
            S2Point target = queries[cursor++ % queries.length];
            total += query.findClosestPoints(target).size();
        }

        return total;
    }
}
