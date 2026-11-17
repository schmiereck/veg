package io.veg.benchmarks;

import io.veg.engine.GraphSession;
import io.veg.engine.memory.InMemoryGraphEngine;
import io.veg.fixtures.DeterministicGraphGenerator;
import io.veg.fixtures.DeterministicGraphGenerator.GeneratedDataset;
import io.veg.model.Edge;
import io.veg.model.EntityId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Reproducible baseline benchmark harness for VEG engines.
 */
public final class BaselineBenchmarkRunner {

    public record BenchmarkConfig(
            int personCount,
            long seed,
            double sharedValueRatio,
            double provenanceRatio,
            int warmupIterations,
            int measuredIterations,
            int readSampleQueries
    ) {
        public BenchmarkConfig {
            if (personCount <= 0) {
                throw new IllegalArgumentException("personCount must be > 0");
            }
            if (warmupIterations < 0 || measuredIterations <= 0 || readSampleQueries <= 0) {
                throw new IllegalArgumentException("Invalid iteration or query count");
            }
        }

        public static BenchmarkConfig defaultBaseline() {
            return new BenchmarkConfig(10_000, 42L, 0.50, 0.25, 2, 5, 2_000);
        }
    }

    public record EnvironmentInfo(
            String timestampUtc,
            String engineName,
            String javaVersion,
            String jvmName,
            String osName,
            String osArch,
            int processors,
            long maxHeapBytes
    ) {
        public static EnvironmentInfo capture(String engineName) {
            return new EnvironmentInfo(
                    Instant.now().toString(),
                    engineName,
                    System.getProperty("java.version", "unknown"),
                    System.getProperty("java.vm.name", "unknown"),
                    System.getProperty("os.name", "unknown"),
                    System.getProperty("os.arch", "unknown"),
                    Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory()
            );
        }
    }

    public record IterationResult(
            int iteration,
            long entitiesTotal,
            long edgesTotal,
            long domainOperations,
            double writeDurationMs,
            double writeOpsPerSec,
            long read1HopP50Nanos,
            long read1HopP95Nanos,
            long read1HopP99Nanos,
            long read2HopP50Nanos,
            long read2HopP95Nanos,
            long read2HopP99Nanos,
            long heapDeltaBytes,
            double approxBytesPerEntity,
            double approxBytesPerEdge
    ) {
    }

    public record BenchmarkReport(
            BenchmarkConfig config,
            EnvironmentInfo environment,
            List<IterationResult> iterations
    ) {
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append("timestamp_utc,engine,java_version,jvm_name,os_name,os_arch,cpus,max_heap_bytes,")
              .append("seed,person_count,shared_value_ratio,provenance_ratio,iteration,")
              .append("entities_total,edges_total,domain_ops,write_ms,write_ops_per_sec,")
              .append("read_1hop_p50_ns,read_1hop_p95_ns,read_1hop_p99_ns,")
              .append("read_2hop_p50_ns,read_2hop_p95_ns,read_2hop_p99_ns,")
              .append("heap_delta_bytes,approx_bytes_per_entity,approx_bytes_per_edge\n");

            for (IterationResult r : iterations) {
                sb.append(csvEscape(environment.timestampUtc())).append(',')
                  .append(csvEscape(environment.engineName())).append(',')
                  .append(csvEscape(environment.javaVersion())).append(',')
                  .append(csvEscape(environment.jvmName())).append(',')
                  .append(csvEscape(environment.osName())).append(',')
                  .append(csvEscape(environment.osArch())).append(',')
                  .append(environment.processors()).append(',')
                  .append(environment.maxHeapBytes()).append(',')
                  .append(config.seed()).append(',')
                  .append(config.personCount()).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", config.sharedValueRatio())).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", config.provenanceRatio())).append(',')
                  .append(r.iteration()).append(',')
                  .append(r.entitiesTotal()).append(',')
                  .append(r.edgesTotal()).append(',')
                  .append(r.domainOperations()).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.writeDurationMs())).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.writeOpsPerSec())).append(',')
                  .append(r.read1HopP50Nanos()).append(',')
                  .append(r.read1HopP95Nanos()).append(',')
                  .append(r.read1HopP99Nanos()).append(',')
                  .append(r.read2HopP50Nanos()).append(',')
                  .append(r.read2HopP95Nanos()).append(',')
                  .append(r.read2HopP99Nanos()).append(',')
                  .append(r.heapDeltaBytes()).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerEntity())).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerEdge()))
                  .append('\n');
            }
            return sb.toString();
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"environment\": {\n");
            sb.append("    \"timestampUtc\": ").append(jsonString(environment.timestampUtc())).append(",\n");
            sb.append("    \"engineName\": ").append(jsonString(environment.engineName())).append(",\n");
            sb.append("    \"javaVersion\": ").append(jsonString(environment.javaVersion())).append(",\n");
            sb.append("    \"jvmName\": ").append(jsonString(environment.jvmName())).append(",\n");
            sb.append("    \"osName\": ").append(jsonString(environment.osName())).append(",\n");
            sb.append("    \"osArch\": ").append(jsonString(environment.osArch())).append(",\n");
            sb.append("    \"processors\": ").append(environment.processors()).append(",\n");
            sb.append("    \"maxHeapBytes\": ").append(environment.maxHeapBytes()).append("\n");
            sb.append("  },\n");
            sb.append("  \"config\": {\n");
            sb.append("    \"personCount\": ").append(config.personCount()).append(",\n");
            sb.append("    \"seed\": ").append(config.seed()).append(",\n");
            sb.append("    \"sharedValueRatio\": ").append(String.format(Locale.ROOT, "%.2f", config.sharedValueRatio())).append(",\n");
            sb.append("    \"provenanceRatio\": ").append(String.format(Locale.ROOT, "%.2f", config.provenanceRatio())).append(",\n");
            sb.append("    \"warmupIterations\": ").append(config.warmupIterations()).append(",\n");
            sb.append("    \"measuredIterations\": ").append(config.measuredIterations()).append(",\n");
            sb.append("    \"readSampleQueries\": ").append(config.readSampleQueries()).append("\n");
            sb.append("  },\n");
            sb.append("  \"iterations\": [\n");
            for (int i = 0; i < iterations.size(); i++) {
                IterationResult r = iterations.get(i);
                sb.append("    {\n");
                sb.append("      \"iteration\": ").append(r.iteration()).append(",\n");
                sb.append("      \"entitiesTotal\": ").append(r.entitiesTotal()).append(",\n");
                sb.append("      \"edgesTotal\": ").append(r.edgesTotal()).append(",\n");
                sb.append("      \"domainOperations\": ").append(r.domainOperations()).append(",\n");
                sb.append("      \"writeDurationMs\": ").append(String.format(Locale.ROOT, "%.3f", r.writeDurationMs())).append(",\n");
                sb.append("      \"writeOpsPerSec\": ").append(String.format(Locale.ROOT, "%.2f", r.writeOpsPerSec())).append(",\n");
                sb.append("      \"read1HopP50Nanos\": ").append(r.read1HopP50Nanos()).append(",\n");
                sb.append("      \"read1HopP95Nanos\": ").append(r.read1HopP95Nanos()).append(",\n");
                sb.append("      \"read1HopP99Nanos\": ").append(r.read1HopP99Nanos()).append(",\n");
                sb.append("      \"read2HopP50Nanos\": ").append(r.read2HopP50Nanos()).append(",\n");
                sb.append("      \"read2HopP95Nanos\": ").append(r.read2HopP95Nanos()).append(",\n");
                sb.append("      \"read2HopP99Nanos\": ").append(r.read2HopP99Nanos()).append(",\n");
                sb.append("      \"heapDeltaBytes\": ").append(r.heapDeltaBytes()).append(",\n");
                sb.append("      \"approxBytesPerEntity\": ").append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerEntity())).append(",\n");
                sb.append("      \"approxBytesPerEdge\": ").append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerEdge())).append("\n");
                sb.append("    }").append(i + 1 < iterations.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    // Keep reference to prevent dead-code elimination during memory measurement
    private volatile Object sink;

    public BenchmarkReport runInMemoryBaseline(BenchmarkConfig config) {
        return run("InMemoryGraphEngine", InMemoryGraphEngine::new, config);
    }

    public BenchmarkReport run(String engineName, Supplier<GraphSession> sessionFactory, BenchmarkConfig config) {
        Objects.requireNonNull(engineName, "engineName must not be null");
        Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        Objects.requireNonNull(config, "config must not be null");

        DeterministicGraphGenerator generator = new DeterministicGraphGenerator();
        EnvironmentInfo env = EnvironmentInfo.capture(engineName);

        for (int w = 0; w < config.warmupIterations(); w++) {
            GraphSession warmupSession = sessionFactory.get();
            GeneratedDataset warmupData = generator.generate(
                    warmupSession,
                    config.seed() + w,
                    Math.min(config.personCount(), 1_000),
                    config.sharedValueRatio(),
                    config.provenanceRatio()
            );
            sink = executeReadQueries(warmupSession, warmupData, config.seed(), Math.min(config.readSampleQueries(), 200));
        }

        List<IterationResult> results = new ArrayList<>(config.measuredIterations());
        for (int iter = 1; iter <= config.measuredIterations(); iter++) {
            long heapBefore = settledUsedHeapBytes();

            GraphSession session = sessionFactory.get();
            long startNs = System.nanoTime();
            GeneratedDataset dataset = generator.generate(
                    session,
                    config.seed(),
                    config.personCount(),
                    config.sharedValueRatio(),
                    config.provenanceRatio()
            );
            long writeElapsedNs = System.nanoTime() - startNs;

            sink = session;
            long heapAfter = settledUsedHeapBytes();
            long heapDelta = Math.max(0L, heapAfter - heapBefore);

            long[] oneHopLatencies = measureOneHopReads(session, dataset, config.seed() + iter, config.readSampleQueries());
            long[] twoHopLatencies = measureTwoHopReads(session, dataset, config.seed() + iter, config.readSampleQueries());

            double writeMs = writeElapsedNs / 1_000_000.0;
            double writeOpsPerSec = dataset.domainOperationsCount() / (writeElapsedNs / 1_000_000_000.0);

            long entitiesTotal = session.entityCount();
            long edgesTotal = session.edgeCount();

            double bytesPerEntity = entitiesTotal > 0 ? (double) heapDelta / entitiesTotal : 0.0;
            double bytesPerEdge = edgesTotal > 0 ? (double) heapDelta / edgesTotal : 0.0;

            results.add(new IterationResult(
                    iter,
                    entitiesTotal,
                    edgesTotal,
                    dataset.domainOperationsCount(),
                    writeMs,
                    writeOpsPerSec,
                    percentile(oneHopLatencies, 0.50),
                    percentile(oneHopLatencies, 0.95),
                    percentile(oneHopLatencies, 0.99),
                    percentile(twoHopLatencies, 0.50),
                    percentile(twoHopLatencies, 0.95),
                    percentile(twoHopLatencies, 0.99),
                    heapDelta,
                    bytesPerEntity,
                    bytesPerEdge
            ));
        }

        return new BenchmarkReport(config, env, List.copyOf(results));
    }

    public static void writeReportCsv(BenchmarkReport report, Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, report.toCsv(), StandardCharsets.UTF_8);
    }

    public static void writeReportJson(BenchmarkReport report, Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, report.toJson(), StandardCharsets.UTF_8);
    }

    private long executeReadQueries(GraphSession session, GeneratedDataset dataset, long seed, int count) {
        long[] oneHop = measureOneHopReads(session, dataset, seed, count);
        long[] twoHop = measureTwoHopReads(session, dataset, seed, count);
        return oneHop.length + twoHop.length;
    }

    private long[] measureOneHopReads(GraphSession session, GeneratedDataset dataset, long seed, int sampleCount) {
        List<EntityId> persons = dataset.personIds();
        EntityId hasFirstName = dataset.schema().hasFirstName();
        SplittableRandom random = new SplittableRandom(seed);
        long[] latencies = new long[sampleCount];

        long checksum = 0;
        for (int i = 0; i < sampleCount; i++) {
            EntityId person = persons.get(random.nextInt(persons.size()));
            long t0 = System.nanoTime();
            List<Edge> edges = session.findOutgoingEdges(person, hasFirstName);
            if (!edges.isEmpty()) {
                var valueNode = session.findEntity(edges.getFirst().targetId());
                if (valueNode.isPresent()) {
                    checksum += valueNode.get().id().value();
                }
            }
            latencies[i] = System.nanoTime() - t0;
        }
        sink = checksum;
        Arrays.sort(latencies);
        return latencies;
    }

    private long[] measureTwoHopReads(GraphSession session, GeneratedDataset dataset, long seed, int sampleCount) {
        List<EntityId> annotatedEdges = dataset.annotatedEdgeIds();
        if (annotatedEdges.isEmpty()) {
            return new long[sampleCount];
        }
        EntityId supportedBy = dataset.schema().supportedBy();
        EntityId hasTitle = dataset.schema().hasTitle();
        SplittableRandom random = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        long[] latencies = new long[sampleCount];

        long checksum = 0;
        for (int i = 0; i < sampleCount; i++) {
            EntityId statementEdge = annotatedEdges.get(random.nextInt(annotatedEdges.size()));
            long t0 = System.nanoTime();
            List<Edge> provEdges = session.findOutgoingEdges(statementEdge, supportedBy);
            if (!provEdges.isEmpty()) {
                EntityId docId = provEdges.getFirst().targetId();
                List<Edge> titleEdges = session.findOutgoingEdges(docId, hasTitle);
                if (!titleEdges.isEmpty()) {
                    var titleVal = session.findEntity(titleEdges.getFirst().targetId());
                    if (titleVal.isPresent()) {
                        checksum += titleVal.get().id().value();
                    }
                }
            }
            latencies[i] = System.nanoTime() - t0;
        }
        sink = checksum;
        Arrays.sort(latencies);
        return latencies;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(p * sorted.length) - 1;
        int clamped = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[clamped];
    }

    private static long settledUsedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 2; i++) {
            System.gc();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String csvEscape(String raw) {
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return '"' + raw.replace("\"", "\"\"") + '"';
        }
        return raw;
    }

    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
