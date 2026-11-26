package org.timermakov.geo;

/**
 * Geographic point with an arbitrary unique identifier.
 *
 * @param id        caller-defined unique identifier
 * @param latitude  latitude in degrees, range [-90, 90]
 * @param longitude longitude in degrees, range [-180, 180]
 */
public record GeoPoint(String id, double latitude, double longitude) {
}
