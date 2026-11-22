package org.timermakov.geo.benchmarks;

import com.google.common.geometry.S1Angle;
import com.google.common.geometry.S2ClosestPointQuery;
import com.google.common.geometry.S2LatLng;
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
 * Radius query of Google S2 ({@link S2PointIndex} + {@link S2ClosestPointQuery}) on the
 * same synthetic dataset and query set as the in-house indexes. S2 is spherical-native:
 * distances are geodesic angles, the radius translates as {@code S1Angle.radians(r / R)}.
 * The query point itself competes in the results exactly like in our indexes.
 */
@State(Scope.Benchmark)
public class GeoS2RadiusBenchmarks {

    private static final int OPERATIONS_PER_INVOKE = 128;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 17;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    @Param({})
    int n;

    @Param({})
    int radiusMeters;

    S2PointIndex<GeoPoint> index;
    S2Point[] queries;
    S1Angle maxDistance;
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() {
        List<GeoPoint> points = BenchGeoMath.buildPoints(n, GENERATOR_LNG_STRIDE);
        index = new S2PointIndex<>();
        for (GeoPoint point : points) {
            index.add(toS2(point.latitude(), point.longitude()), point);
        }

        GeoPoint[] queryPoints = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);
        queries = new S2Point[queryPoints.length];
        for (int i = 0; i < queryPoints.length; i++) {
            GeoPoint point = queryPoints[i];
            queries[i] = toS2(point.latitude(), point.longitude());
        }

        maxDistance = S1Angle.radians(radiusMeters / EARTH_RADIUS_METERS);
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        cursor = 0;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOKE)
    public int queryS2Radius() {
        S2ClosestPointQuery<GeoPoint> query = new S2ClosestPointQuery<>(index);
        query.setMaxDistance(maxDistance);
        int total = 0;
        for (int i = 0; i < OPERATIONS_PER_INVOKE; i++) {
            S2Point target = queries[cursor++ % queries.length];
            total += query.findClosestPoints(target).size();
        }

        return total;
    }

    static S2Point toS2(double latitude, double longitude) {
        return S2LatLng.fromDegrees(latitude, longitude).toPoint();
    }
}
