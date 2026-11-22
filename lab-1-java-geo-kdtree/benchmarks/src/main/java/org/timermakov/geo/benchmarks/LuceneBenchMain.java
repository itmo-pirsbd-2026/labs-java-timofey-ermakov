package org.timermakov.geo.benchmarks;

import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * JMH entry point for the Apache Lucene benchmarks: same settings loader and run
 * parameters as {@link S2BenchMain}, separate result files.
 */
public final class LuceneBenchMain {

    private static final String LUCENE_KNN_RESULT_FILE = "public-lucene-knn.txt";
    private static final String LUCENE_RADIUS_RESULT_FILE = "public-lucene-radius.txt";

    public static void main(String[] args) throws RunnerException {
        BenchSettings settings = BenchSettings.load();

        runClass(GeoLuceneKnnBenchmarks.class.getSimpleName(), settings, LUCENE_KNN_RESULT_FILE, builder -> {
            for (var n : settings.nValues()) {
                builder.param("n", Integer.toString(n));
            }
            for (var k : settings.kValues()) {
                builder.param("k", Integer.toString(k));
            }
        });

        runClass(GeoLuceneRadiusBenchmarks.class.getSimpleName(), settings, LUCENE_RADIUS_RESULT_FILE, builder -> {
            for (var n : settings.nValues()) {
                builder.param("n", Integer.toString(n));
            }
            for (var radius : settings.radiusValues()) {
                builder.param("radiusMeters", Integer.toString(radius));
            }
        });
    }

    private static void runClass(
            String className,
            BenchSettings settings,
            String resultFile,
            Consumer<ChainedOptionsBuilder> params) throws RunnerException {
        Path resultDir = Path.of(settings.resultDir());
        try {
            Files.createDirectories(resultDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create JMH result dir: " + resultDir, e);
        }

        TimeValue iterationTime = TimeValue.milliseconds(settings.iterationTimeMs());
        ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(className)
                .forks(settings.launchCount())
                .warmupIterations(settings.warmupCount())
                .warmupTime(iterationTime)
                .measurementIterations(settings.iterationCount())
                .measurementTime(iterationTime)
                .jvmArgs("-Xmx6g")
                .resultFormat(ResultFormatType.TEXT)
                .result(resultDir.resolve(resultFile).toString());
        if (settings.gcProfiler()) {
            builder.addProfiler(GCProfiler.class);
        }
        params.accept(builder);

        new Runner(builder.build()).run();
    }
}
