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
    static final int LAT_MODULUS = 4_096;
    static final double LAT_DENOM = 4_095.0;
    static final int LNG_MODULUS = 8_192;
    static final double LNG_DENOM = 8_191.0;

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
    static List<GeoPoint> buildPoints(int count, int lngStride) {
        List<GeoPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double lat = LAT_BASE + (LAT_SPAN * (i % LAT_MODULUS) / LAT_DENOM);
            double lng = LNG_BASE + (LNG_SPAN * ((i * lngStride) % LNG_MODULUS) / LNG_DENOM);
            points.add(new GeoPoint("p-" + i, lat, lng));
        }

        return points;
    }

    private static double degreesToRadians(double value) {
        return value * (Math.PI / 180.0);
    }
}
