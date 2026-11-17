package io.veg.benchmarks;

import io.veg.benchmarks.BaselineBenchmarkRunner.EnvironmentInfo;
import io.veg.engine.GraphSession;
import io.veg.engine.memory.InMemoryGraphEngine;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Stage 2 comparative experiment: evaluates Option A ({@code Connection + Assertions})
 * versus Option B ({@code Edge = Assertion}) from ADR-0001 across varying assertion densities.
 */
public final class AssertionModelComparisonRunner {

    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.80");
    private static final BigDecimal WEIGHT_THRESHOLD = new BigDecimal("0.50");

    public enum AssertionModel {
        CONNECTION_PLUS_ASSERTIONS,
        EDGE_AS_ASSERTION
    }

    public record ExperimentConfig(
            int pairCount,
            long seed,
            List<Integer> assertionsPerPairOptions,
            int warmupRuns,
            int measuredIterations,
            int querySamples
    ) {
        public ExperimentConfig {
            if (pairCount <= 0 || warmupRuns < 0 || measuredIterations <= 0 || querySamples <= 0) {
                throw new IllegalArgumentException("Invalid experiment configuration counts");
            }
            Objects.requireNonNull(assertionsPerPairOptions, "assertionsPerPairOptions must not be null");
            if (assertionsPerPairOptions.isEmpty()) {
                throw new IllegalArgumentException("assertionsPerPairOptions must not be empty");
            }
        }

        public static ExperimentConfig defaultExperiment() {
            return new ExperimentConfig(5_000, 42L, List.of(1, 2, 5), 1, 3, 1_000);
        }
    }

    public record ModelRunResult(
            AssertionModel model,
            int assertionsPerPair,
            int iteration,
            long totalAssertions,
            long entitiesTotal,
            long edgesTotal,
            double entitiesPerAssertion,
            double edgesPerAssertion,
            double writeDurationMs,
            double writeAssertionsPerSec,
            long queryHighConfidenceP50Nanos,
            long queryHighConfidenceP95Nanos,
            long queryHighConfidenceP99Nanos,
            long matchedFilterCount,
            long heapDeltaBytes,
            double approxBytesPerAssertion
    ) {
    }

    public record ExperimentReport(
            ExperimentConfig config,
            EnvironmentInfo environment,
            List<ModelRunResult> results
    ) {
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append("timestamp_utc,engine,java_version,os_name,os_arch,cpus,seed,pair_count,")
              .append("model,assertions_per_pair,iteration,total_assertions,")
              .append("entities_total,edges_total,entities_per_assertion,edges_per_assertion,")
              .append("write_ms,write_assertions_per_sec,")
              .append("query_p50_ns,query_p95_ns,query_p99_ns,matched_filter_count,")
              .append("heap_delta_bytes,approx_bytes_per_assertion\n");

            for (ModelRunResult r : results) {
                sb.append(BenchmarkMetricsSupport.csvEscape(environment.timestampUtc())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.engineName())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.javaVersion())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.osName())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.osArch())).append(',')
                  .append(environment.processors()).append(',')
                  .append(config.seed()).append(',')
                  .append(config.pairCount()).append(',')
                  .append(r.model().name()).append(',')
                  .append(r.assertionsPerPair()).append(',')
                  .append(r.iteration()).append(',')
                  .append(r.totalAssertions()).append(',')
                  .append(r.entitiesTotal()).append(',')
                  .append(r.edgesTotal()).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.entitiesPerAssertion())).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.edgesPerAssertion())).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.writeDurationMs())).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.writeAssertionsPerSec())).append(',')
                  .append(r.queryHighConfidenceP50Nanos()).append(',')
                  .append(r.queryHighConfidenceP95Nanos()).append(',')
                  .append(r.queryHighConfidenceP99Nanos()).append(',')
                  .append(r.matchedFilterCount()).append(',')
                  .append(r.heapDeltaBytes()).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerAssertion()))
                  .append('\n');
            }
            return sb.toString();
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"environment\": {\n");
            sb.append("    \"timestampUtc\": ").append(BenchmarkMetricsSupport.jsonString(environment.timestampUtc())).append(",\n");
            sb.append("    \"engineName\": ").append(BenchmarkMetricsSupport.jsonString(environment.engineName())).append(",\n");
            sb.append("    \"javaVersion\": ").append(BenchmarkMetricsSupport.jsonString(environment.javaVersion())).append(",\n");
            sb.append("    \"osName\": ").append(BenchmarkMetricsSupport.jsonString(environment.osName())).append(",\n");
            sb.append("    \"processors\": ").append(environment.processors()).append("\n");
            sb.append("  },\n");
            sb.append("  \"config\": {\n");
            sb.append("    \"pairCount\": ").append(config.pairCount()).append(",\n");
            sb.append("    \"seed\": ").append(config.seed()).append(",\n");
            sb.append("    \"assertionsPerPairOptions\": ").append(config.assertionsPerPairOptions()).append(",\n");
            sb.append("    \"measuredIterations\": ").append(config.measuredIterations()).append(",\n");
            sb.append("    \"querySamples\": ").append(config.querySamples()).append("\n");
            sb.append("  },\n");
            sb.append("  \"results\": [\n");
            for (int i = 0; i < results.size(); i++) {
                ModelRunResult r = results.get(i);
                sb.append("    {\n");
                sb.append("      \"model\": ").append(BenchmarkMetricsSupport.jsonString(r.model().name())).append(",\n");
                sb.append("      \"assertionsPerPair\": ").append(r.assertionsPerPair()).append(",\n");
                sb.append("      \"iteration\": ").append(r.iteration()).append(",\n");
                sb.append("      \"totalAssertions\": ").append(r.totalAssertions()).append(",\n");
                sb.append("      \"entitiesTotal\": ").append(r.entitiesTotal()).append(",\n");
                sb.append("      \"edgesTotal\": ").append(r.edgesTotal()).append(",\n");
                sb.append("      \"entitiesPerAssertion\": ").append(String.format(Locale.ROOT, "%.2f", r.entitiesPerAssertion())).append(",\n");
                sb.append("      \"edgesPerAssertion\": ").append(String.format(Locale.ROOT, "%.2f", r.edgesPerAssertion())).append(",\n");
                sb.append("      \"writeDurationMs\": ").append(String.format(Locale.ROOT, "%.3f", r.writeDurationMs())).append(",\n");
                sb.append("      \"writeAssertionsPerSec\": ").append(String.format(Locale.ROOT, "%.2f", r.writeAssertionsPerSec())).append(",\n");
                sb.append("      \"queryHighConfidenceP50Nanos\": ").append(r.queryHighConfidenceP50Nanos()).append(",\n");
                sb.append("      \"queryHighConfidenceP95Nanos\": ").append(r.queryHighConfidenceP95Nanos()).append(",\n");
                sb.append("      \"queryHighConfidenceP99Nanos\": ").append(r.queryHighConfidenceP99Nanos()).append(",\n");
                sb.append("      \"matchedFilterCount\": ").append(r.matchedFilterCount()).append(",\n");
                sb.append("      \"heapDeltaBytes\": ").append(r.heapDeltaBytes()).append(",\n");
                sb.append("      \"approxBytesPerAssertion\": ").append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerAssertion())).append("\n");
                sb.append("    }").append(i + 1 < results.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    private volatile Object sink;

    public ExperimentReport runInMemoryComparison(ExperimentConfig config) {
        return run("InMemoryGraphEngine", InMemoryGraphEngine::new, config);
    }

    public ExperimentReport run(String engineName, Supplier<GraphSession> sessionFactory, ExperimentConfig config) {
        Objects.requireNonNull(engineName, "engineName must not be null");
        Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        Objects.requireNonNull(config, "config must not be null");

        EnvironmentInfo env = EnvironmentInfo.capture(engineName);
        for (int w = 0; w < config.warmupRuns(); w++) {
            for (AssertionModel model : AssertionModel.values()) {
                runSingleTrial(sessionFactory.get(), model, Math.min(config.pairCount(), 250), 2, config.seed() + w, 1, Math.min(config.querySamples(), 100));
            }
        }

        List<ModelRunResult> results = new ArrayList<>();
        for (int assertionsPerPair : config.assertionsPerPairOptions()) {
            for (int iter = 1; iter <= config.measuredIterations(); iter++) {
                ModelRunResult optionA = runSingleTrial(sessionFactory.get(), AssertionModel.CONNECTION_PLUS_ASSERTIONS,
                        config.pairCount(), assertionsPerPair, config.seed(), iter, config.querySamples());
                ModelRunResult optionB = runSingleTrial(sessionFactory.get(), AssertionModel.EDGE_AS_ASSERTION,
                        config.pairCount(), assertionsPerPair, config.seed(), iter, config.querySamples());

                if (optionA.matchedFilterCount() != optionB.matchedFilterCount()) {
                    throw new IllegalStateException(
                            "Semantic mismatch between Option A (" + optionA.matchedFilterCount()
                                    + ") and Option B (" + optionB.matchedFilterCount() + ")");
                }
                results.add(optionA);
                results.add(optionB);
            }
        }
        return new ExperimentReport(config, env, List.copyOf(results));
    }

    public static void writeReportCsv(ExperimentReport report, Path outputPath) throws IOException {
        BenchmarkMetricsSupport.writeUtf8File(outputPath, report.toCsv());
    }

    public static void writeReportJson(ExperimentReport report, Path outputPath) throws IOException {
        BenchmarkMetricsSupport.writeUtf8File(outputPath, report.toJson());
    }

    private ModelRunResult runSingleTrial(
            GraphSession session,
            AssertionModel model,
            int pairCount,
            int assertionsPerPair,
            long seed,
            int iteration,
            int querySamples
    ) {
        sink = null;
        long heapBefore = BenchmarkMetricsSupport.settledUsedHeapBytes();

        long startNs = System.nanoTime();
        PopulatedModel populated = switch (model) {
            case CONNECTION_PLUS_ASSERTIONS -> populateOptionA(session, pairCount, assertionsPerPair, seed);
            case EDGE_AS_ASSERTION -> populateOptionB(session, pairCount, assertionsPerPair, seed);
        };
        long writeNs = System.nanoTime() - startNs;

        sink = session;
        long heapAfter = BenchmarkMetricsSupport.settledUsedHeapBytes();
        long heapDelta = Math.max(0L, heapAfter - heapBefore);

        long[] latencies = new long[querySamples];
        SplittableRandom queryRng = new SplittableRandom(seed ^ 0x517CC1B727220A95L);
        long matchedCount = 0;

        for (int q = 0; q < querySamples; q++) {
            EntityId person = populated.persons().get(queryRng.nextInt(populated.persons().size()));
            long t0 = System.nanoTime();
            int matches = countHighConfidenceWorksOnTargets(session, populated, person);
            latencies[q] = System.nanoTime() - t0;
            matchedCount += matches;
        }
        Arrays.sort(latencies);
        sink = null;

        long totalAssertions = (long) pairCount * assertionsPerPair;
        long entitiesTotal = session.entityCount();
        long edgesTotal = session.edgeCount();
        double writeMs = writeNs / 1_000_000.0;
        double assertionsPerSec = totalAssertions / (writeNs / 1_000_000_000.0);

        return new ModelRunResult(
                model,
                assertionsPerPair,
                iteration,
                totalAssertions,
                entitiesTotal,
                edgesTotal,
                (double) entitiesTotal / totalAssertions,
                (double) edgesTotal / totalAssertions,
                writeMs,
                assertionsPerSec,
                BenchmarkMetricsSupport.percentile(latencies, 0.50),
                BenchmarkMetricsSupport.percentile(latencies, 0.95),
                BenchmarkMetricsSupport.percentile(latencies, 0.99),
                matchedCount,
                heapDelta,
                (double) heapDelta / totalAssertions
        );
    }

    private record PopulatedModel(
            AssertionModel model,
            List<EntityId> persons,
            EntityId primaryPredicateOrConnectionEdge,
            EntityId toProjectEdge,
            EntityId hasAssertionEdge,
            EntityId hasPredicateTagEdge,
            EntityId worksOnTagValue,
            EntityId hasWeightEdge,
            EntityId hasConfidenceEdge
    ) {
    }

    private PopulatedModel populateOptionA(GraphSession session, int pairCount, int assertionsPerPair, long seed) {
        EntityId personType = session.defineObjectType("Person");
        EntityId projectType = session.defineObjectType("Project");
        EntityId connectionType = session.defineObjectType("Connection");
        EntityId assertionType = session.defineObjectType("Assertion");
        EntityId docType = session.defineObjectType("SourceDocument");

        EntityId predTagType = session.defineValueType("PredicateTag", ValueType.STRING);
        EntityId weightType = session.defineValueType("Weight", ValueType.DECIMAL);
        EntityId confidenceType = session.defineValueType("Confidence", ValueType.DECIMAL);

        EntityId hasConnection = session.definePredicate("hasConnection", personType, connectionType);
        EntityId toProject = session.definePredicate("toProject", connectionType, projectType, PredicateConstraints.manyToOne());
        EntityId hasAssertion = session.definePredicate("hasAssertion", connectionType, assertionType);
        EntityId hasPredicateTag = session.definePredicate("hasPredicateTag", assertionType, predTagType, PredicateConstraints.manyToOne());
        EntityId hasWeight = session.definePredicate("hasWeight", assertionType, weightType, PredicateConstraints.manyToOne());
        EntityId hasConfidence = session.definePredicate("hasConfidence", assertionType, confidenceType, PredicateConstraints.manyToOne());
        EntityId supportedBy = session.definePredicate("supportedBy", assertionType, docType);

        EntityId worksOnTag = session.createValue(predTagType, Value.of("worksOn"));
        EntityId contributesToTag = session.createValue(predTagType, Value.of("contributesTo"));

        int projectCount = Math.max(10, pairCount / 10);
        int docCount = 50;
        List<EntityId> projects = createObjects(session, projectType, projectCount);
        List<EntityId> docs = createObjects(session, docType, docCount);
        List<EntityId> persons = new ArrayList<>(pairCount);

        SplittableRandom rng = new SplittableRandom(seed);
        for (int i = 0; i < pairCount; i++) {
            EntityId person = session.createObject(personType);
            persons.add(person);
            EntityId project = projects.get(rng.nextInt(projects.size()));

            EntityId conn = session.createObject(connectionType);
            session.connect(person, hasConnection, conn);
            session.connect(conn, toProject, project);

            for (int k = 0; k < assertionsPerPair; k++) {
                EntityId tag = (k % 2 == 0) ? worksOnTag : contributesToTag;
                BigDecimal w = BigDecimal.valueOf(10 + rng.nextInt(91), 2);
                BigDecimal c = BigDecimal.valueOf(10 + rng.nextInt(91), 2);
                EntityId doc = docs.get(rng.nextInt(docs.size()));

                EntityId assertion = session.createObject(assertionType);
                session.connect(conn, hasAssertion, assertion);
                session.connect(assertion, hasPredicateTag, tag);
                session.connect(assertion, hasWeight, session.createValue(weightType, Value.of(w)));
                session.connect(assertion, hasConfidence, session.createValue(confidenceType, Value.of(c)));
                session.connect(assertion, supportedBy, doc);
            }
        }

        return new PopulatedModel(AssertionModel.CONNECTION_PLUS_ASSERTIONS, persons,
                hasConnection, toProject, hasAssertion, hasPredicateTag, worksOnTag, hasWeight, hasConfidence);
    }

    private PopulatedModel populateOptionB(GraphSession session, int pairCount, int assertionsPerPair, long seed) {
        EntityId personType = session.defineObjectType("Person");
        EntityId projectType = session.defineObjectType("Project");
        EntityId docType = session.defineObjectType("SourceDocument");
        EntityId weightType = session.defineValueType("Weight", ValueType.DECIMAL);
        EntityId confidenceType = session.defineValueType("Confidence", ValueType.DECIMAL);

        EntityId worksOn = session.definePredicate("worksOn", personType, projectType);
        EntityId contributesTo = session.definePredicate("contributesTo", personType, projectType);
        EntityId hasWeight = session.definePredicate("hasWeight", BootstrapIds.ANY_EDGE, weightType, PredicateConstraints.manyToOne());
        EntityId hasConfidence = session.definePredicate("hasConfidence", BootstrapIds.ANY_EDGE, confidenceType, PredicateConstraints.manyToOne());
        EntityId supportedBy = session.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);

        int projectCount = Math.max(10, pairCount / 10);
        int docCount = 50;
        List<EntityId> projects = createObjects(session, projectType, projectCount);
        List<EntityId> docs = createObjects(session, docType, docCount);
        List<EntityId> persons = new ArrayList<>(pairCount);

        SplittableRandom rng = new SplittableRandom(seed);
        for (int i = 0; i < pairCount; i++) {
            EntityId person = session.createObject(personType);
            persons.add(person);
            EntityId project = projects.get(rng.nextInt(projects.size()));

            for (int k = 0; k < assertionsPerPair; k++) {
                EntityId pred = (k % 2 == 0) ? worksOn : contributesTo;
                BigDecimal w = BigDecimal.valueOf(10 + rng.nextInt(91), 2);
                BigDecimal c = BigDecimal.valueOf(10 + rng.nextInt(91), 2);
                EntityId doc = docs.get(rng.nextInt(docs.size()));

                EntityId edge = session.connect(person, pred, project);
                session.connect(edge, hasWeight, session.createValue(weightType, Value.of(w)));
                session.connect(edge, hasConfidence, session.createValue(confidenceType, Value.of(c)));
                session.connect(edge, supportedBy, doc);
            }
        }

        return new PopulatedModel(AssertionModel.EDGE_AS_ASSERTION, persons,
                worksOn, null, null, null, null, hasWeight, hasConfidence);
    }

    private int countHighConfidenceWorksOnTargets(GraphSession session, PopulatedModel model, EntityId person) {
        int matches = 0;
        if (model.model() == AssertionModel.CONNECTION_PLUS_ASSERTIONS) {
            for (Edge connEdge : session.findOutgoingEdges(person, model.primaryPredicateOrConnectionEdge())) {
                EntityId connId = connEdge.targetId();
                List<Edge> projectEdges = session.findOutgoingEdges(connId, model.toProjectEdge());
                if (projectEdges.isEmpty()) {
                    continue;
                }
                for (Edge assertEdge : session.findOutgoingEdges(connId, model.hasAssertionEdge())) {
                    EntityId assertionId = assertEdge.targetId();
                    List<Edge> tagEdges = session.findOutgoingEdges(assertionId, model.hasPredicateTagEdge());
                    if (tagEdges.isEmpty() || !tagEdges.getFirst().targetId().equals(model.worksOnTagValue())) {
                        continue;
                    }
                    if (meetsDecimalThresholds(session, assertionId, model.hasConfidenceEdge(), model.hasWeightEdge())) {
                        matches++;
                    }
                }
            }
        } else {
            for (Edge edge : session.findOutgoingEdges(person, model.primaryPredicateOrConnectionEdge())) {
                if (meetsDecimalThresholds(session, edge.id(), model.hasConfidenceEdge(), model.hasWeightEdge())) {
                    matches++;
                }
            }
        }
        return matches;
    }

    private static boolean meetsDecimalThresholds(
            GraphSession session,
            EntityId assertionOrEdgeId,
            EntityId hasConfidencePred,
            EntityId hasWeightPred
    ) {
        BigDecimal confidence = readDecimal(session, assertionOrEdgeId, hasConfidencePred);
        if (confidence == null || confidence.compareTo(CONFIDENCE_THRESHOLD) < 0) {
            return false;
        }
        BigDecimal weight = readDecimal(session, assertionOrEdgeId, hasWeightPred);
        return weight != null && weight.compareTo(WEIGHT_THRESHOLD) >= 0;
    }

    private static BigDecimal readDecimal(GraphSession session, EntityId sourceId, EntityId predId) {
        List<Edge> edges = session.findOutgoingEdges(sourceId, predId);
        if (edges.isEmpty()) {
            return null;
        }
        var node = session.findEntity(edges.getFirst().targetId());
        if (node.isPresent() && node.get().payload().isPresent()
                && node.get().payload().get() instanceof Value.DecimalValue(BigDecimal val)) {
            return val;
        }
        return null;
    }

    private static List<EntityId> createObjects(GraphSession session, EntityId objectType, int count) {
        List<EntityId> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(session.createObject(objectType));
        }
        return list;
    }
}
