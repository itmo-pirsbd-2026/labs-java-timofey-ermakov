package org.timermakov.geo;

/**
 * Single search hit: matched point and its haversine distance from the query position, in meters.
 */
public record GeoSearchResult(GeoPoint point, double distanceMeters) {
}
