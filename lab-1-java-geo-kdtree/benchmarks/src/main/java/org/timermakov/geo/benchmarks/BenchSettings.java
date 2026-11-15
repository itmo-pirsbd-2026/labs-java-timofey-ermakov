package org.timermakov.geo.benchmarks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Benchmark run parameters. All run knobs live in {@code config/bench.settings.json} —
 * never in code. Search order: {@code HW1_BENCH_CONFIG} env path, then
 * {@code config/bench.settings.json} relative to the working directory (or its parent,
 * when Gradle runs the task from the {@code benchmarks} module), then built-in defaults.
 * Defaults mirror the C# {@code StableBenchmarkConfig} and {@code [Params]} values.
 *
 * <p>{@code HW1_BENCH_SMOKE=1} replaces the settings with a single dry case to verify
 * the harness starts.
 */
public final class BenchSettings {

    private static final String CONFIG_ENV_VAR = "HW1_BENCH_CONFIG";
    private static final String SMOKE_ENV_VAR = "HW1_BENCH_SMOKE";
    private static final String SMOKE_ENV_VALUE = "1";
    private static final String CONFIG_FILE_NAME = "bench.settings.json";

    private static final int[] DEFAULT_N_VALUES = {10_000, 21_544, 35_938};
    private static final int[] DEFAULT_K_VALUES = {1, 20};
    private static final int[] DEFAULT_RADIUS_VALUES = {100, 5_000};
    private static final int DEFAULT_LAUNCH_COUNT = 1;
    private static final int DEFAULT_WARMUP_COUNT = 15;
    private static final int DEFAULT_ITERATION_COUNT = 40;
    private static final boolean DEFAULT_GC_PROFILER = true;
    private static final String DEFAULT_RESULT_DIR = "build/jmh-results";

    private static final int[] SMOKE_N_VALUES = {512};
    private static final int[] SMOKE_K_VALUES = {1};
    private static final int[] SMOKE_RADIUS_VALUES = {100};
    private static final int SMOKE_WARMUP_COUNT = 0;
    private static final int SMOKE_ITERATION_COUNT = 1;

    private final int[] nValues;
    private final int[] kValues;
    private final int[] radiusValues;
    private final int launchCount;
    private final int warmupCount;
    private final int iterationCount;
    private final boolean gcProfiler;
    private final String resultDir;

    private BenchSettings(
            int[] nValues,
            int[] kValues,
            int[] radiusValues,
            int launchCount,
            int warmupCount,
            int iterationCount,
            boolean gcProfiler,
            String resultDir) {
        this.nValues = nValues;
        this.kValues = kValues;
        this.radiusValues = radiusValues;
        this.launchCount = launchCount;
        this.warmupCount = warmupCount;
        this.iterationCount = iterationCount;
        this.gcProfiler = gcProfiler;
        this.resultDir = resultDir;
    }

    public static BenchSettings load() {
        Path path = resolveConfigPath();
        JsonHolder holder = path == null ? new JsonHolder() : readHolder(path);
        BenchSettings settings = fromHolder(holder);
        return isSmokeRequested() ? smoke(settings.resultDir) : settings;
    }

    public int[] nValues() {
        return nValues.clone();
    }

    public int[] kValues() {
        return kValues.clone();
    }

    public int[] radiusValues() {
        return radiusValues.clone();
    }

    public int launchCount() {
        return launchCount;
    }

    public int warmupCount() {
        return warmupCount;
    }

    public int iterationCount() {
        return iterationCount;
    }

    public boolean gcProfiler() {
        return gcProfiler;
    }

    public String resultDir() {
        return resultDir;
    }

    private static Path resolveConfigPath() {
        String envPath = System.getenv(CONFIG_ENV_VAR);
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }

        Path local = Path.of("config", CONFIG_FILE_NAME);
        if (Files.exists(local)) {
            return local;
        }

        Path parent = Path.of("..", "config", CONFIG_FILE_NAME);
        if (Files.exists(parent)) {
            return parent;
        }

        return null;
    }

    private static JsonHolder readHolder(Path path) {
        Gson gson = new GsonBuilder().setStrictness(Strictness.LENIENT).create();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            JsonHolder loaded = gson.fromJson(reader, JsonHolder.class);
            if (loaded == null) {
                throw new IllegalStateException("Benchmark settings file is empty: " + path);
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to read benchmark settings from " + path, e);
        }
    }

    private static BenchSettings fromHolder(JsonHolder holder) {
        if (holder.nValues == null || holder.nValues.length == 0) {
            throw new IllegalArgumentException("nValues must not be empty.");
        }
        if (holder.kValues == null || holder.kValues.length == 0) {
            throw new IllegalArgumentException("kValues must not be empty.");
        }
        if (holder.radiusValues == null || holder.radiusValues.length == 0) {
            throw new IllegalArgumentException("radiusValues must not be empty.");
        }
        if (holder.launchCount < 1) {
            throw new IllegalArgumentException("launchCount must be positive.");
        }
        if (holder.warmupCount < 0 || holder.iterationCount < 1) {
            throw new IllegalArgumentException("warmupCount must be >= 0 and iterationCount must be positive.");
        }
        if (holder.resultDir == null || holder.resultDir.isBlank()) {
            throw new IllegalArgumentException("resultDir must not be blank.");
        }

        return new BenchSettings(
                holder.nValues.clone(),
                holder.kValues.clone(),
                holder.radiusValues.clone(),
                holder.launchCount,
                holder.warmupCount,
                holder.iterationCount,
                holder.gcProfiler,
                holder.resultDir);
    }

    private static boolean isSmokeRequested() {
        return SMOKE_ENV_VALUE.equals(System.getenv(SMOKE_ENV_VAR));
    }

    private static BenchSettings smoke(String resultDir) {
        return new BenchSettings(
                SMOKE_N_VALUES.clone(),
                SMOKE_K_VALUES.clone(),
                SMOKE_RADIUS_VALUES.clone(),
                DEFAULT_LAUNCH_COUNT,
                SMOKE_WARMUP_COUNT,
                SMOKE_ITERATION_COUNT,
                DEFAULT_GC_PROFILER,
                resultDir);
    }

    /** Mutable Gson target; absent fields keep their defaults. */
    private static final class JsonHolder {
        int[] nValues = DEFAULT_N_VALUES.clone();
        int[] kValues = DEFAULT_K_VALUES.clone();
        int[] radiusValues = DEFAULT_RADIUS_VALUES.clone();
        int launchCount = DEFAULT_LAUNCH_COUNT;
        int warmupCount = DEFAULT_WARMUP_COUNT;
        int iterationCount = DEFAULT_ITERATION_COUNT;
        boolean gcProfiler = DEFAULT_GC_PROFILER;
        String resultDir = DEFAULT_RESULT_DIR;
    }

    @Override
    public String toString() {
        return "BenchSettings{nValues=" + Arrays.toString(nValues)
                + ", kValues=" + Arrays.toString(kValues)
                + ", radiusValues=" + Arrays.toString(radiusValues)
                + ", launchCount=" + launchCount
                + ", warmupCount=" + warmupCount
                + ", iterationCount=" + iterationCount
                + ", gcProfiler=" + gcProfiler
                + ", resultDir='" + resultDir + "'}";
    }
}
