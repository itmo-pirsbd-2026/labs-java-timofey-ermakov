package org.timermakov.geo.benchmarks;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TopFieldDocs;
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
import java.util.Random;

/**
 * kNN query of Apache Lucene ({@link LatLonPoint#nearest}) — the industrial
 * document-engine benchmark. Documents carry a single LatLonPoint field, no stored
 * fields; the index is committed and the searcher snapshotted once at setup, outside
 * the measured loop. Coordinates are quantized by Lucene (~centimeter precision).
 */
@State(Scope.Benchmark)
public class GeoLuceneKnnBenchmarks {

    private static final int OPERATIONS_PER_INVOKE = 128;
    private static final int QUERY_LIMIT = 500;
    private static final int GENERATOR_LNG_STRIDE = 23;
    private static final String FIELD = "location";

    @Param({})
    int n;

    @Param({})
    int k;

    ByteBuffersDirectory directory;
    IndexWriter writer;
    DirectoryReader reader;
    IndexSearcher searcher;
    List<GeoPoint> points = List.of();
    GeoPoint[] queries = {};
    Random random;
    int cursor;

    @Setup(Level.Trial)
    public void globalSetup() throws IOException {
        points = BenchGeoMath.buildClusteredPoints(n);
        queries = BenchGeoMath.spreadQueries(points, QUERY_LIMIT);

        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig());
        for (var point : points) {
            var document = new Document();
            document.add(new LatLonPoint(FIELD, point.latitude(), point.longitude()));
            writer.addDocument(document);
        }
        writer.commit();
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
        random = new Random(7);
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
    public int queryLuceneKnn() throws IOException {
        var total = 0;
        for (var i = 0; i < OPERATIONS_PER_INVOKE; i++) {
            var query = queries[cursor++ % queries.length];
            var docs = LatLonPoint.nearest(searcher, FIELD, query.latitude(), query.longitude(), k);
            total += docs.scoreDocs.length;
        }

        return total;
    }
}
