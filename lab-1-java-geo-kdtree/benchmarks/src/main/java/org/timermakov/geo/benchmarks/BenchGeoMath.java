package org.timermakov.geo.benchmarks;

import org.timermakov.geo.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark-local geo helpers. The library's {@code GeoDistance} is package-private
 * (parity with the C# internal class), so the benchmark module ships its own copy.
 */
final class BenchGeoMath {

    static final double EARTH_RADIUS_METERS = 6_371_000.0;

    static final double LAT_BASE = -89.0;
    static final double LAT_SPAN = 178.0;
    static final double LNG_BASE = -179.0;
    static final double LNG_SPAN = 358.0;
    // Large moduli keep every generated point unique for N up to 16M: latitude cycles
    // with period 2^23, and one latitude period shifts the (i * 17) % 2^24 longitude
    // phase by 17 * 2^23 mod 2^24 = 2^23 != 0, so no (lat, lng) pair repeats.
    static final int LAT_MODULUS = 8_388_608;
    static final double LAT_DENOM = 8_388_607.0;
    static final int LNG_MODULUS = 16_777_216;
    static final double LNG_DENOM = 16_777_215.0;

    /**
     * Uniformly random off-grid query points with a fixed seed. Dataset points are NOT
     * used: a self-query would make kNN return the query point itself and radius always
     * collect at least one hit, measuring a degenerate workload instead of a search.
     * Queries are reproducible across implementations and runs.
     */
    static final double QUERY_JITTER_DEGREES = 0.005;

    /**
     * Queries at random dataset points jittered by ~0.5 km: inside a cluster like a real
     * user location, but never exactly on an indexed point (no self-hit).
     */
    static GeoPoint[] spreadQueries(List<GeoPoint> points, int limit) {
        int count = Math.min(limit, points.size());
        GeoPoint[] queries = new GeoPoint[count];
        java.util.Random random = new java.util.Random(42L);
        for (int i = 0; i < count; i++) {
            GeoPoint base = points.get(random.nextInt(points.size()));
            double lat = clamp(base.latitude() + QUERY_JITTER_DEGREES * random.nextGaussian(), LAT_BASE, -LAT_BASE);
            double lng = wrap(base.longitude() + QUERY_JITTER_DEGREES * random.nextGaussian());
            queries[i] = new GeoPoint("q-" + i, lat, lng);
        }

        return queries;
    }

    private BenchGeoMath() {
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = degreesToRadians(lat2 - lat1);
        double dLng = degreesToRadians(lng2 - lng1);
        double rLat1 = degreesToRadians(lat1);
        double rLat2 = degreesToRadians(lat2);

        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double a = sinLat * sinLat + Math.cos(rLat1) * Math.cos(rLat2) * sinLng * sinLng;
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Deterministic grid-like dataset: latitude cycles over the modulus,
     * longitude strides by {@code lngStride} per index.
     */
    /** Cluster sigma, degrees (~33 km): city-scale spread. */
    static final double CLUSTER_SIGMA_DEGREES = 0.3;
    /** Cluster population: hit expectations are N-independent (realistic scaling). */
    static final int CLUSTER_SIZE = 5_000;

    static List<GeoPoint> buildPoints(int count, int lngStride) {
        List<GeoPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double lat = LAT_BASE + (LAT_SPAN * (i % LAT_MODULUS) / LAT_DENOM);
            double lng = LNG_BASE + (LNG_SPAN * ((i * lngStride) % LNG_MODULUS) / LNG_DENOM);
            points.add(new GeoPoint("p-" + i, lat, lng));
        }

        return points;
    }

    /**
     * City-like clustered data: K = count / {@link #CLUSTER_SIZE} cluster centers placed
     * uniformly on the sphere, each cluster a 2D Gaussian with sigma
     * {@link #CLUSTER_SIGMA_DEGREES}. Queries aimed at a point behave like real local
     * searches: hit counts are set by the cluster population, not by the grid size.
     */
    static List<GeoPoint> buildClusteredPoints(int count) {
        java.util.Random random = new java.util.Random(13L);
        int clusters = Math.max(1, count / CLUSTER_SIZE);
        List<GeoPoint> points = new ArrayList<>(count);
        for (int c = 0; c < clusters; c++) {
            double centerLat = LAT_BASE + LAT_SPAN * random.nextDouble();
            double centerLng = LNG_BASE + LNG_SPAN * random.nextDouble();
            for (int i = 0; i < CLUSTER_SIZE && points.size() < count; i++) {
                double lat = clamp(centerLat + CLUSTER_SIGMA_DEGREES * random.nextGaussian(), LAT_BASE, -LAT_BASE);
                double lng = wrap(centerLng + CLUSTER_SIGMA_DEGREES * random.nextGaussian());
                points.add(new GeoPoint("c" + c + "-" + i, lat, lng));
            }
        }

        return points;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static double wrap(double lng) {
        double w = ((lng - LNG_BASE) % LNG_SPAN + LNG_SPAN) % LNG_SPAN + LNG_BASE;
        return w;
    }

    private static double degreesToRadians(double value) {
        return value * (Math.PI / 180.0);
    }
}
