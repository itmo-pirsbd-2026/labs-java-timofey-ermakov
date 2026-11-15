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
     * same-longitude crossing distance is the exact bound.
     *
     * <p>Longitude: the far branch is a contiguous longitude arc whose edges are the split
     * meridian and the +/-180 seam. Every geodesic into the arc crosses its nearest edge —
     * the meridian with the smaller separation from the query — so the distance to that
     * edge meridian, {@code R * asin(|cos(phi) * sin(s)|)} with {@code s} the edge
     * separation, bounds all points in the arc. Taking the seam as a candidate edge makes
     * the bound valid across the +/-180 wrap, where points beyond the split in value can
     * still be geographically near the query.
     */
    static double axisLowerBoundMeters(double queryLat, double queryLng, double splitValue, boolean splitByLatitude) {
        if (splitByLatitude) {
            return haversineMeters(queryLat, queryLng, splitValue, queryLng);
        }

        double qLat = degreesToRadians(queryLat);
        double qLng = degreesToRadians(queryLng);
        double splitDelta = degreesToRadians(splitValue) - qLng;
        double splitSeparation = Math.min(Math.abs(splitDelta), 2 * Math.PI - Math.abs(splitDelta));
        double seamSeparation = Math.PI - Math.abs(qLng);
        double entrySeparation = Math.min(splitSeparation, seamSeparation);

        double planeAngle = Math.min(1.0, Math.abs(Math.cos(qLat)) * Math.sin(entrySeparation));
        return EARTH_RADIUS_METERS * Math.asin(planeAngle);
    }

    private static double degreesToRadians(double value) {
        return value * DEGREES_TO_RADIANS;
    }
}
