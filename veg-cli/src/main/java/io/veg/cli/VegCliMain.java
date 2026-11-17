package io.veg.cli;

import io.veg.benchmarks.AssertionModelComparisonRunner;
import io.veg.benchmarks.AssertionModelComparisonRunner.ExperimentConfig;
import io.veg.benchmarks.AssertionModelComparisonRunner.ExperimentReport;
import io.veg.benchmarks.AssertionModelComparisonRunner.ModelRunResult;
import io.veg.benchmarks.BaselineBenchmarkRunner;
import io.veg.benchmarks.BaselineBenchmarkRunner.BenchmarkConfig;
import io.veg.benchmarks.BaselineBenchmarkRunner.BenchmarkReport;
import io.veg.benchmarks.BaselineBenchmarkRunner.IterationResult;
import io.veg.engine.GraphSession;
import io.veg.engine.TypeMismatchException;
import io.veg.engine.memory.InMemoryGraphEngine;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CLI entry point for running Stage 1/2 demonstrations and reproducible benchmark experiments.
 */
public final class VegCliMain {

    private VegCliMain() {
    }

    public static void main(String[] args) throws IOException {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0) {
            printUsage(out);
            return 0;
        }
        return switch (args[0]) {
            case "demo" -> {
                runDemo(out);
                yield 0;
            }
            case "benchmark" -> runBenchmark(args, out, err);
            case "assertion-experiment" -> runAssertionExperiment(args, out, err);
            case "-h", "--help", "help" -> {
                printUsage(out);
                yield 0;
            }
            default -> {
                err.println("Unknown command: " + args[0]);
                printUsage(err);
                yield 1;
            }
        };
    }

    private static void runDemo(PrintStream out) {
        GraphSession graph = new InMemoryGraphEngine();

        EntityId personType = graph.defineObjectType("Person");
        EntityId sourceDocType = graph.defineObjectType("SourceDocument");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId confidenceType = graph.defineValueType("Confidence", ValueType.DECIMAL);

        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);
        EntityId supportedBy = graph.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, sourceDocType);
        EntityId hasConfidence = graph.definePredicate("hasConfidence", BootstrapIds.ANY_EDGE, confidenceType);

        EntityId person1 = graph.createObject(personType);
        EntityId person2 = graph.createObject(personType);
        EntityId sharedThomas = graph.createValue(firstNameType, Value.of("Thomas"));
        EntityId passportDoc = graph.createObject(sourceDocType);
        EntityId confidence = graph.createValue(confidenceType, Value.of(new BigDecimal("0.98")));

        EntityId edge1 = graph.connect(person1, hasFirstName, sharedThomas);
        EntityId edge2 = graph.connect(person2, hasFirstName, sharedThomas);
        EntityId provEdge = graph.connect(edge1, supportedBy, passportDoc);
        EntityId confEdge = graph.connect(edge1, hasConfidence, confidence);

        out.println("=== VEG Stage 1 Self-Describing Graph Demo ===");
        out.println("Schema in graph: Person=" + personType + ", FirstName=" + firstNameType + ", hasFirstName=" + hasFirstName);
        out.println("T01: " + person1 + " --hasFirstName(" + edge1 + ")--> " + sharedThomas
                + " " + graph.findEntity(sharedThomas).orElseThrow().payload().orElseThrow());

        try {
            graph.createValue(firstNameType, Value.of(42L));
        } catch (TypeMismatchException expected) {
            out.println("T02: Rejected invalid scalar value cleanly -> " + expected.getMessage());
        }

        out.println("T03: Shared value entity " + sharedThomas + " referenced by edges " + edge1 + " and " + edge2);
        Edge loadedProv = graph.findEdge(provEdge).orElseThrow();
        Edge loadedConf = graph.findEdge(confEdge).orElseThrow();
        out.println("T04: Edge " + edge1 + " annotated via supportedBy(" + loadedProv.id() + ") -> " + loadedProv.targetId()
                + " and hasConfidence(" + loadedConf.id() + ") -> " + confidence);
        out.println("Graph totals: entities=" + graph.entityCount() + ", edges=" + graph.edgeCount());
    }

    private static int runBenchmark(String[] args, PrintStream out, PrintStream err) throws IOException {
        int entities = 10_000;
        long seed = 42L;
        double sharedRatio = 0.50;
        double provenanceRatio = 0.25;
        int warmup = 2;
        int iterations = 5;
        int queries = 2_000;
        Path csvOutput = null;
        Path jsonOutput = null;

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + flag);
                return 1;
            }
            String val = args[++i];
            switch (flag) {
                case "--entities" -> entities = Integer.parseInt(val);
                case "--seed" -> seed = Long.parseLong(val);
                case "--shared-ratio" -> sharedRatio = Double.parseDouble(val);
                case "--provenance-ratio" -> provenanceRatio = Double.parseDouble(val);
                case "--warmup" -> warmup = Integer.parseInt(val);
                case "--iterations" -> iterations = Integer.parseInt(val);
                case "--queries" -> queries = Integer.parseInt(val);
                case "--output" -> csvOutput = Path.of(val);
                case "--json" -> jsonOutput = Path.of(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }

        BenchmarkConfig config = new BenchmarkConfig(entities, seed, sharedRatio, provenanceRatio, warmup, iterations, queries);
        BaselineBenchmarkRunner runner = new BaselineBenchmarkRunner();
        BenchmarkReport report = runner.runInMemoryBaseline(config);

        out.printf(Locale.ROOT,
                "Engine=%s | Java=%s | Persons=%d | Seed=%d | Warmup=%d | Iterations=%d%n",
                report.environment().engineName(),
                report.environment().javaVersion(),
                config.personCount(),
                config.seed(),
                config.warmupIterations(),
                config.measuredIterations());

        for (IterationResult r : report.iterations()) {
            out.printf(Locale.ROOT,
                    "Iter %d: entities=%d edges=%d write=%.2f ms (%.0f ops/s) 1-hop p50/p99=%d/%d ns 2-hop p50/p99=%d/%d ns heapDelta=%.2f MB (~%.1f B/entity)%n",
                    r.iteration(),
                    r.entitiesTotal(),
                    r.edgesTotal(),
                    r.writeDurationMs(),
                    r.writeOpsPerSec(),
                    r.read1HopP50Nanos(),
                    r.read1HopP99Nanos(),
                    r.read2HopP50Nanos(),
                    r.read2HopP99Nanos(),
                    r.heapDeltaBytes() / (1024.0 * 1024.0),
                    r.approxBytesPerEntity());
        }

        if (csvOutput != null) {
            BaselineBenchmarkRunner.writeReportCsv(report, csvOutput);
            out.println("Wrote CSV report to " + csvOutput);
        }
        if (jsonOutput != null) {
            BaselineBenchmarkRunner.writeReportJson(report, jsonOutput);
            out.println("Wrote JSON report to " + jsonOutput);
        }
        return 0;
    }

    private static int runAssertionExperiment(String[] args, PrintStream out, PrintStream err) throws IOException {
        int pairs = 5_000;
        long seed = 42L;
        List<Integer> densities = List.of(1, 2, 5);
        int warmup = 1;
        int iterations = 3;
        int queries = 1_000;
        Path csvOutput = null;
        Path jsonOutput = null;

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + flag);
                return 1;
            }
            String val = args[++i];
            switch (flag) {
                case "--pairs" -> pairs = Integer.parseInt(val);
                case "--seed" -> seed = Long.parseLong(val);
                case "--densities" -> densities = Arrays.stream(val.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .toList();
                case "--warmup" -> warmup = Integer.parseInt(val);
                case "--iterations" -> iterations = Integer.parseInt(val);
                case "--queries" -> queries = Integer.parseInt(val);
                case "--output" -> csvOutput = Path.of(val);
                case "--json" -> jsonOutput = Path.of(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }

        ExperimentConfig config = new ExperimentConfig(pairs, seed, densities, warmup, iterations, queries);
        AssertionModelComparisonRunner runner = new AssertionModelComparisonRunner();
        ExperimentReport report = runner.runInMemoryComparison(config);

        out.printf(Locale.ROOT,
                "Assertion Experiment | Engine=%s | Java=%s | Pairs=%d | Seed=%d%n",
                report.environment().engineName(),
                report.environment().javaVersion(),
                config.pairCount(),
                config.seed());

        for (ModelRunResult r : report.results()) {
            out.printf(Locale.ROOT,
                    "%s (k=%d, iter=%d): entities/assert=%.2f edges/assert=%.2f write=%.2f ms (%.0f assert/s) query p50/p99=%d/%d ns heap/assert=%.1f B matches=%d%n",
                    r.model(),
                    r.assertionsPerPair(),
                    r.iteration(),
                    r.entitiesPerAssertion(),
                    r.edgesPerAssertion(),
                    r.writeDurationMs(),
                    r.writeAssertionsPerSec(),
                    r.queryHighConfidenceP50Nanos(),
                    r.queryHighConfidenceP99Nanos(),
                    r.approxBytesPerAssertion(),
                    r.matchedFilterCount());
        }

        if (csvOutput != null) {
            AssertionModelComparisonRunner.writeReportCsv(report, csvOutput);
            out.println("Wrote CSV report to " + csvOutput);
        }
        if (jsonOutput != null) {
            AssertionModelComparisonRunner.writeReportJson(report, jsonOutput);
            out.println("Wrote JSON report to " + jsonOutput);
        }
        return 0;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  veg-cli demo");
        out.println("  veg-cli benchmark [--entities N] [--seed S] [--shared-ratio R] [--provenance-ratio P] [--warmup W] [--iterations I] [--queries Q] [--output results.csv] [--json results.json]");
        out.println("  veg-cli assertion-experiment [--pairs N] [--seed S] [--densities 1,2,5] [--warmup W] [--iterations I] [--queries Q] [--output results.csv] [--json results.json]");
    }
}
