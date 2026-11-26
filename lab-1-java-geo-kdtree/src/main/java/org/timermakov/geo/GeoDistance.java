package org.timermakov.geo;

/**
 * Shared geo math helpers. Package-private for parity with the C# internal <code>GeoDistance</code>.
 *
 * <p>The haversine metric is expressed through the squared term
 * {@code a = sin^2(dLat/2) + cos(lat1)*cos(lat2)*sin^2(dLng/2)}: the distance
 * {@code 2R*asin(sqrt(a))} is a strictly monotone function of {@code a}, so search
 * comparisons and pruning run in a-space without {@code sqrt}/{@code asin}; exact meters
 * are computed only for hits and accepted candidates.
 */
final class GeoDistance {

    /** Mean Earth radius in meters used by the haversine metric. */
    static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private static final double TWO_EARTH_RADIUS_METERS = 2 * EARTH_RADIUS_METERS;

    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    private GeoDistance() {
    }

    /**
     * Squared haversine term of the central angle between two points, clamped to [0, 1].
     * Strictly monotone in the distance: comparing {@code a} values is equivalent to
     * comparing distances in meters. The cosine factors come precomputed — the query
     * latitude cosine is computed once per search, the node latitude cosine is cached in
     * the node — which removes two {@code cos} calls per visited node.
     */
    static double haversineA(double lat1, double lng1, double lat2, double lng2, double cosLat1, double cosLat2) {
        var dLat = degreesToRadians(lat2 - lat1);
        var dLng = degreesToRadians(lng2 - lng1);

        var sinLat = Math.sin(dLat / 2);
        var sinLng = Math.sin(dLng / 2);
        var a = sinLat * sinLat + cosLat1 * cosLat2 * sinLng * sinLng;
        return Math.min(1.0, a);
    }

    /** Exact haversine distance in meters for an a-space term. */
    static double metersFromA(double a) {
        return TWO_EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    /** a-space term of a distance in meters: {@code sin^2(meters / (2R))}. */
    static double aFromMeters(double meters) {
        var s = Math.sin(meters / TWO_EARTH_RADIUS_METERS);
        return s * s;
    }

    /**
     * Lower bound in a-space of how far the query is from the region beyond the split axis.
     * Latitude: the nearest point of a latitude circle sits at the query longitude, so the
     * bound is {@code sin^2(dPhi/2)} — the exact same-latitude-crossing term. Longitude: the
     * far branch is a contiguous longitude arc whose edges are the split meridian and the
     * +/-180 seam; every geodesic into the arc crosses its nearest edge — the meridian with
     * the smaller separation {@code s} from the query — so the meters bound
     * {@code R * asin(|cos(phi)| * sin(s))} converts to a-space as
     * {@code (1 - sqrt(1 - x^2)) / 2} with {@code x = |cos(phi)| * sin(s)}. Taking the seam
     * as a candidate edge keeps the bound valid across the +/-180 wrap, where points beyond
     * the split in value can still be geographically near the query.
     */
    static double axisLowerBoundA(
            double queryLat,
            double queryLng,
            double splitValue,
            boolean splitByLatitude,
            double queryCosLat) {
        if (splitByLatitude) {
            var dPhi = degreesToRadians(splitValue - queryLat);
            var s = Math.sin(dPhi / 2);
            return s * s;
        }

        var qLng = degreesToRadians(queryLng);
        var splitDelta = degreesToRadians(splitValue) - qLng;
        var splitSeparation = Math.min(Math.abs(splitDelta), 2 * Math.PI - Math.abs(splitDelta));
        var seamSeparation = Math.PI - Math.abs(qLng);
        var entrySeparation = Math.min(splitSeparation, seamSeparation);

        var x = Math.abs(queryCosLat) * Math.sin(entrySeparation);
        return (1 - Math.sqrt(1 - x * x)) / 2;
    }

    private static double degreesToRadians(double value) {
        return value * DEGREES_TO_RADIANS;
    }
}
