package org.timermakov.geo.benchmarks;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TotalHitCountCollector;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.timermakov.geo.GeoPoint;

import java.io.IOException;
import java.util.List;

/**
 * Radius query of Apache Lucene ({@link LatLonPoint#newDistanceQuery}) — twin of the
 * kNN benchmark. Hits are counted with TotalHitCountCollector; the geodesic predicate
 * is Lucene's haversine, distances in meters.
 */
@State(Scope.Benchmark)
public class GeoLuceneRadiusBenchmarks {

    private static final int OPERATIONS_PER_INVOKE = 128;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 17;
    private static final String FIELD = "location";

    @Param({})
    int n;

    @Param({})
    int radiusMeters;

    ByteBuffersDirectory directory;
    IndexWriter writer;
    DirectoryReader reader;
    IndexSearcher searcher;
    List<GeoPoint> points = List.of();
    GeoPoint[] queries = {};
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() throws IOException {
        points = BenchGeoMath.buildPoints(n, GENERATOR_LNG_STRIDE);
        queries = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);

        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig());
        for (GeoPoint point : points) {
            Document document = new Document();
            document.add(new LatLonPoint(FIELD, point.latitude(), point.longitude()));
            writer.addDocument(document);
        }
        writer.commit();
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        reader.close();
        writer.close();
        directory.close();
    }

    @Setup(Level.Iteration)
    public void iterationSetup() {
        cursor = 0;
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_INVOKE)
    public int queryLuceneRadius() throws IOException {
        int total = 0;
        for (int i = 0; i < OPERATIONS_PER_INVOKE; i++) {
            GeoPoint query = queries[cursor++ % queries.length];
            Query distanceQuery = LatLonPoint.newDistanceQuery(
                    FIELD, query.latitude(), query.longitude(), radiusMeters);
            TotalHitCountCollector collector = new TotalHitCountCollector();
            searcher.search(distanceQuery, collector);
            total += collector.getTotalHits();
        }

        return total;
    }
}
