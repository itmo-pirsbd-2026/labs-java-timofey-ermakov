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
     * Latitude: the nearest point of a latitude circle sits at the query longitude, so the
     * same-longitude crossing distance is the exact bound. Longitude: the nearest point of
     * the meridian great circle is {@code R * asin(|cos(phi) * sin(deltaLng)|)} away — any
     * point beyond the meridian lies on the other side of its plane, so its geodesic crosses
     * the plane and cannot be shorter.
     *
     * <p>Longitude is cyclic, though: points just across the +/-180 seam are geographically
     * near the query even when their value lies beyond the split, and their shortest arc
     * crosses the seam instead of the split meridian. The plane bound is therefore only
     * applied when the far side cannot reach the seam: {@code [splitValue, +180]} requires
     * {@code queryLng >= 0}, {@code [-180, splitValue]} requires {@code queryLng <= 0}.
     */
    static double axisLowerBoundMeters(double queryLat, double queryLng, double splitValue, boolean splitByLatitude) {
        if (splitByLatitude) {
            return haversineMeters(queryLat, queryLng, splitValue, queryLng);
        }

        if (splitValue > queryLng && queryLng < 0) {
            return 0.0;
        }
        if (splitValue < queryLng && queryLng > 0) {
            return 0.0;
        }

        double deltaLng = degreesToRadians(splitValue - queryLng);
        double planeAngle = Math.min(1.0, Math.abs(Math.cos(degreesToRadians(queryLat)) * Math.sin(deltaLng)));
        return EARTH_RADIUS_METERS * Math.asin(planeAngle);
    }

    private static double degreesToRadians(double value) {
        return value * DEGREES_TO_RADIANS;
    }
}
