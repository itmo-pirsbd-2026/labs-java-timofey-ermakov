package org.timermakov.geo.benchmarks;

/**
 * ECEF (Earth-Centered, Earth-Fixed) conversion helpers for benchmarking public
 * spatial libraries: lat/lng degrees go in, 3D Cartesian coordinates on the sphere
 * come out, so an euclidean k-d tree sees the same geometry.
 *
 * <p>The chord distance between two ECEF points is monotone in the great-circle
 * arc distance, so kNN ordering is preserved. A radius query against arc distance
 * {@code r} translates to the exact chord radius {@code 2R sin(r / 2R)}.
 */
final class BenchEcefMath {

    private BenchEcefMath() {
    }

    static void toEcef(double latDeg, double lngDeg, double earthRadiusMeters, double[] out) {
        var lat = Math.toRadians(latDeg);
        var lng = Math.toRadians(lngDeg);
        var cosLat = Math.cos(lat);
        out[0] = earthRadiusMeters * cosLat * Math.cos(lng);
        out[1] = earthRadiusMeters * cosLat * Math.sin(lng);
        out[2] = earthRadiusMeters * Math.sin(lat);
    }

    static double chordRadius(double arcRadiusMeters, double earthRadiusMeters) {
        return 2.0 * earthRadiusMeters * Math.sin(arcRadiusMeters / (2.0 * earthRadiusMeters));
    }
}
