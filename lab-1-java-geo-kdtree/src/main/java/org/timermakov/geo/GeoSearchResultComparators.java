package org.timermakov.geo;

import java.util.Comparator;

/**
 * Result orderings shared by the index: nearest-first for output,
 * worst-first for kNN candidate pruning (both tie-break by id).
 */
final class GeoSearchResultComparators {

    /** Ascending by distance, ties resolved by id in ordinal ascending order. */
    static final Comparator<GeoSearchResult> NEAREST_FIRST =
            Comparator.comparingDouble(GeoSearchResult::distanceMeters)
                    .thenComparing(result -> result.point().id());

    /** Descending by distance, ties resolved by id in ordinal descending order. */
    static final Comparator<GeoSearchResult> WORST_FIRST =
            Comparator.comparingDouble(GeoSearchResult::distanceMeters)
                    .reversed()
                    .thenComparing(result -> result.point().id(), Comparator.reverseOrder());

    private GeoSearchResultComparators() {
    }
}
