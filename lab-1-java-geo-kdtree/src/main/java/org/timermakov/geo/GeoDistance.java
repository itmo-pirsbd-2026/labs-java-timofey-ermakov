package org.timermakov.geo;

/**
 * Shared geo math helpers. Package-private for parity with the C# internal <code>GeoDistance</code>.
 */
final class GeoDistance {

    /** Mean Earth radius in meters used by the haversine metric. */
    static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    private GeoDistance() {
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
     * Lower bound (meters) of how far the query is from the region beyond the split axis.
     * Only latitude crossings are bounded; longitude crossings are approximated by 0.0 —
     * mirrors the C# baseline, which never prunes on the longitude axis.
     */
    static double axisLowerBoundMeters(double queryLat, double queryLng, double splitValue, boolean splitByLatitude) {
        if (splitByLatitude) {
            return haversineMeters(queryLat, queryLng, splitValue, queryLng);
        }

        return 0.0;
    }

    private static double degreesToRadians(double value) {
        return value * DEGREES_TO_RADIANS;
    }
}
