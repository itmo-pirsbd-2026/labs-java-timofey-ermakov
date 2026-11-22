package org.timermakov.geo.profile;

import org.timermakov.geo.GeoKdTreeIndex;
import org.timermakov.geo.GeoPoint;
import org.timermakov.geo.GeoSearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tight CPU loop over {@link GeoKdTreeIndex} queries without JMH waits — gives a clean
 * hotpath profile for CPU/flamegraph tooling (port of the C# Hw2.ProfileRunner).
 *
 * <p>Usage: {@code gradlew :profile-runner:run --args="--mode radius --seconds 120 --n 100000 --radius 1000 --k 20"}.
 */
public final class ProfileRunner {

    private static final String MODE_RADIUS = "radius";
    private static final String MODE_KNN = "knn";
    private static final String DEFAULT_MODE = MODE_RADIUS;
    private static final int DEFAULT_SECONDS = 120;
    private static final int DEFAULT_N = 100_000;
    private static final int DEFAULT_RADIUS_METERS = 1_000;
    private static final int DEFAULT_K = 20;

    private static final int QUERY_LIMIT = 2_048;
    private static final int GENERATOR_LNG_STRIDE = 17;
    private static final double LAT_BASE = -89.0;
    private static final double LAT_SPAN = 178.0;
    private static final double LNG_BASE = -179.0;
    private static final double LNG_SPAN = 358.0;
    private static final int LAT_MODULUS = 8_388_608;
    private static final double LAT_DENOM = 8_388_607.0;
    private static final int LNG_MODULUS = 16_777_216;
    private static final double LNG_DENOM = 16_777_215.0;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private ProfileRunner() {
    }

    public static void main(String[] args) {
        var parsedArgs = parseArgs(args);
        var mode = getString(parsedArgs, "mode", DEFAULT_MODE);
        var seconds = getInt(parsedArgs, "seconds", DEFAULT_SECONDS);
        var n = getInt(parsedArgs, "n", DEFAULT_N);
        var radiusMeters = getInt(parsedArgs, "radius", DEFAULT_RADIUS_METERS);
        var k = getInt(parsedArgs, "k", DEFAULT_K);

        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds must be positive.");
        }
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive.");
        }
        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("radius must be positive.");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive.");
        }

        System.out.printf("Mode=%s Seconds=%d N=%d Radius=%d K=%d%n", mode, seconds, n, radiusMeters, k);
        var points = buildPoints(n);
        GeoKdTreeIndex index = new GeoKdTreeIndex();
        for (var point : points) {
            index.insert(point);
        }

        var queries = points.subList(0, Math.min(QUERY_LIMIT, points.size()));
        if (queries.isEmpty()) {
            throw new IllegalStateException("No queries generated.");
        }

        var startNanos = System.nanoTime();
        var deadlineNanos = startNanos + seconds * NANOS_PER_SECOND;
        var opCount = 0;
        var checksum = 0;
        var q = 0;
        while (System.nanoTime() < deadlineNanos) {
            var point = queries.get(q++ % queries.size());
            if (MODE_RADIUS.equalsIgnoreCase(mode)) {
                checksum += index.searchRadius(point.latitude(), point.longitude(), radiusMeters).size();
            } else if (MODE_KNN.equalsIgnoreCase(mode)) {
                var nearest = index.searchKNearest(point.latitude(), point.longitude(), k);
                checksum += nearest.isEmpty() ? 0 : Math.round(nearest.getFirst().distanceMeters());
            } else {
                throw new IllegalArgumentException("mode must be 'radius' or 'knn'.");
            }

            opCount++;
        }

        var elapsedNanos = System.nanoTime() - startNanos;
        var elapsedSeconds = elapsedNanos / (double) NANOS_PER_SECOND;
        System.out.printf("Ops=%d ElapsedMs=%.2f ThroughputOpsPerSec=%.2f%n",
                opCount, elapsedNanos / 1_000_000.0, opCount / elapsedSeconds);
        System.out.printf("Checksum=%d%n", checksum);
    }

    private static List<GeoPoint> buildPoints(int count) {
        var points = new ArrayList<GeoPoint>(count);
        for (var i = 0; i < count; i++) {
            var lat = LAT_BASE + (LAT_SPAN * (i % LAT_MODULUS) / LAT_DENOM);
            var lng = LNG_BASE + (LNG_SPAN * ((i * GENERATOR_LNG_STRIDE) % LNG_MODULUS) / LNG_DENOM);
            points.add(new GeoPoint("p-" + i, lat, lng));
        }

        return points;
    }

    private static Map<String, String> parseArgs(String[] args) {
        var result = new HashMap<String, String>();
        for (var i = 0; i < args.length; i++) {
            var token = args[i];
            if (!token.startsWith("--")) {
                continue;
            }

            var key = token.substring(2);
            String value;
            if (i + 1 < args.length && args[i + 1].startsWith("--")) {
                value = "true";
            } else if (i + 1 < args.length) {
                value = args[i + 1];
                i++;
            } else {
                value = "true";
            }

            result.put(key.toLowerCase(Locale.ROOT), value);
        }

        return result;
    }

    private static String getString(Map<String, String> parsedArgs, String key, String fallback) {
        var value = parsedArgs.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int getInt(Map<String, String> parsedArgs, String key, int fallback) {
        var value = parsedArgs.get(key);
        if (value == null) {
            return fallback;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
