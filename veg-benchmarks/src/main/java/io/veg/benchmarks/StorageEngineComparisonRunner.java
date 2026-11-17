package io.veg.benchmarks;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.engine.memory.CompactVersionedDatabase;
import io.veg.engine.memory.InMemoryVersionedDatabase;
import io.veg.engine.memory.PersistentTreeVersionedDatabase;
import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Permission;
import io.veg.model.PropagationDirection;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Reproducible Stage 7 benchmark suite comparing storage engines across Workloads {@code W1}–{@code W7}:
 * <ul>
 *   <li>{@code ENGINE_A_HASHMAP}: Baseline {@link InMemoryVersionedDatabase}</li>
 *   <li>{@code ENGINE_B1_COMPACT_CSR}: {@link CompactVersionedDatabase} with CSR snapshot every commit ({@code interval=1})</li>
 *   <li>{@code ENGINE_B2_JOURNAL_CKPT10}: {@link CompactVersionedDatabase} journal + CSR checkpoint every 10 commits</li>
 *   <li>{@code ENGINE_C_PERSISTENT_TREE}: Structurally shared {@link PersistentTreeVersionedDatabase}</li>
 * </ul>
 */
public final class StorageEngineComparisonRunner {

    public record ComparisonConfig(
            int entityCount,
            int commitCount,
            int branchCount,
            long seed,
            int warmupRuns,
            int sampleQueries
    ) {
        public ComparisonConfig {
            if (entityCount < 20 || commitCount < 2 || branchCount < 1 || warmupRuns < 0 || sampleQueries < 10) {
                throw new IllegalArgumentException("Invalid StorageEngineComparison Config");
            }
        }

        public static ComparisonConfig standard() {
            return new ComparisonConfig(2_000, 50, 500, 42L, 1, 400);
        }
    }

    public record EngineResult(
            String engineName,
            long entitiesAfterBase,
            long edgesAfterBase,
            long totalCommits,
            long totalBranches,
            long retainedHeapBytes,
            double approxBytesPerEntity,
            double w2CommitThroughputPerSec,
            long w2CommitP50Ns,
            long w2CommitP99Ns,
            long w1aOperativeMixP50Ns,
            long w1aOperativeMixP99Ns,
            long w1bHistoryHeavyP50Ns,
            long w1bHistoryHeavyP99Ns,
            long w3Traversal1HopP50Ns,
            long w3Traversal3HopP50Ns,
            long w3Traversal5HopAclP50Ns,
            double w4BranchesMs,
            long w4BranchHeapDeltaBytes,
            double w5MergeMs,
            double w6SmallCommitsMs,
            double w6LargeCommitsMs,
            long w7HistoricalAsOfP50Ns,
            long w7HistoricalAsOfP99Ns,
            double w7FullReplayMs
    ) {
    }

    public record ComparisonReport(
            ComparisonConfig config,
            BaselineBenchmarkRunner.EnvironmentInfo environment,
            List<EngineResult> results
    ) {
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append("timestamp_utc,java_version,jvm_name,os_name,os_arch,cpus,max_heap_bytes,")
              .append("seed,entity_count,commit_count,branch_count,engine,")
              .append("entities_base,edges_base,total_commits,total_branches,retained_heap_bytes,approx_bytes_per_entity,")
              .append("w2_commits_per_sec,w2_commit_p50_ns,w2_commit_p99_ns,")
              .append("w1a_mix_p50_ns,w1a_mix_p99_ns,w1b_hist_p50_ns,w1b_hist_p99_ns,")
              .append("w3_1hop_p50_ns,w3_3hop_p50_ns,w3_5hop_acl_p50_ns,")
              .append("w4_branches_ms,w4_branch_heap_bytes,w5_merge_ms,")
              .append("w6_small_commits_ms,w6_large_commits_ms,")
              .append("w7_asof_p50_ns,w7_asof_p99_ns,w7_full_replay_ms\n");

            for (EngineResult r : results) {
                sb.append(BenchmarkMetricsSupport.csvEscape(environment.timestampUtc())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.javaVersion())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.jvmName())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.osName())).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(environment.osArch())).append(',')
                  .append(environment.processors()).append(',')
                  .append(environment.maxHeapBytes()).append(',')
                  .append(config.seed()).append(',')
                  .append(config.entityCount()).append(',')
                  .append(config.commitCount()).append(',')
                  .append(config.branchCount()).append(',')
                  .append(BenchmarkMetricsSupport.csvEscape(r.engineName())).append(',')
                  .append(r.entitiesAfterBase()).append(',')
                  .append(r.edgesAfterBase()).append(',')
                  .append(r.totalCommits()).append(',')
                  .append(r.totalBranches()).append(',')
                  .append(r.retainedHeapBytes()).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerEntity())).append(',')
                  .append(String.format(Locale.ROOT, "%.2f", r.w2CommitThroughputPerSec())).append(',')
                  .append(r.w2CommitP50Ns()).append(',')
                  .append(r.w2CommitP99Ns()).append(',')
                  .append(r.w1aOperativeMixP50Ns()).append(',')
                  .append(r.w1aOperativeMixP99Ns()).append(',')
                  .append(r.w1bHistoryHeavyP50Ns()).append(',')
                  .append(r.w1bHistoryHeavyP99Ns()).append(',')
                  .append(r.w3Traversal1HopP50Ns()).append(',')
                  .append(r.w3Traversal3HopP50Ns()).append(',')
                  .append(r.w3Traversal5HopAclP50Ns()).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.w4BranchesMs())).append(',')
                  .append(r.w4BranchHeapDeltaBytes()).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.w5MergeMs())).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.w6SmallCommitsMs())).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.w6LargeCommitsMs())).append(',')
                  .append(r.w7HistoricalAsOfP50Ns()).append(',')
                  .append(r.w7HistoricalAsOfP99Ns()).append(',')
                  .append(String.format(Locale.ROOT, "%.3f", r.w7FullReplayMs()))
                  .append('\n');
            }
            return sb.toString();
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"environment\": {\n");
            sb.append("    \"timestampUtc\": ").append(BenchmarkMetricsSupport.jsonString(environment.timestampUtc())).append(",\n");
            sb.append("    \"javaVersion\": ").append(BenchmarkMetricsSupport.jsonString(environment.javaVersion())).append(",\n");
            sb.append("    \"jvmName\": ").append(BenchmarkMetricsSupport.jsonString(environment.jvmName())).append(",\n");
            sb.append("    \"osName\": ").append(BenchmarkMetricsSupport.jsonString(environment.osName())).append(",\n");
            sb.append("    \"osArch\": ").append(BenchmarkMetricsSupport.jsonString(environment.osArch())).append(",\n");
            sb.append("    \"processors\": ").append(environment.processors()).append(",\n");
            sb.append("    \"maxHeapBytes\": ").append(environment.maxHeapBytes()).append("\n");
            sb.append("  },\n");
            sb.append("  \"config\": {\n");
            sb.append("    \"entityCount\": ").append(config.entityCount()).append(",\n");
            sb.append("    \"commitCount\": ").append(config.commitCount()).append(",\n");
            sb.append("    \"branchCount\": ").append(config.branchCount()).append(",\n");
            sb.append("    \"seed\": ").append(config.seed()).append(",\n");
            sb.append("    \"warmupRuns\": ").append(config.warmupRuns()).append(",\n");
            sb.append("    \"sampleQueries\": ").append(config.sampleQueries()).append("\n");
            sb.append("  },\n");
            sb.append("  \"results\": [\n");
            for (int i = 0; i < results.size(); i++) {
                EngineResult r = results.get(i);
                sb.append("    {\n");
                sb.append("      \"engineName\": ").append(BenchmarkMetricsSupport.jsonString(r.engineName())).append(",\n");
                sb.append("      \"entitiesAfterBase\": ").append(r.entitiesAfterBase()).append(",\n");
                sb.append("      \"edgesAfterBase\": ").append(r.edgesAfterBase()).append(",\n");
                sb.append("      \"totalCommits\": ").append(r.totalCommits()).append(",\n");
                sb.append("      \"totalBranches\": ").append(r.totalBranches()).append(",\n");
                sb.append("      \"retainedHeapBytes\": ").append(r.retainedHeapBytes()).append(",\n");
                sb.append("      \"approxBytesPerEntity\": ").append(String.format(Locale.ROOT, "%.2f", r.approxBytesPerEntity())).append(",\n");
                sb.append("      \"w2CommitThroughputPerSec\": ").append(String.format(Locale.ROOT, "%.2f", r.w2CommitThroughputPerSec())).append(",\n");
                sb.append("      \"w2CommitP50Ns\": ").append(r.w2CommitP50Ns()).append(",\n");
                sb.append("      \"w2CommitP99Ns\": ").append(r.w2CommitP99Ns()).append(",\n");
                sb.append("      \"w1aOperativeMixP50Ns\": ").append(r.w1aOperativeMixP50Ns()).append(",\n");
                sb.append("      \"w1aOperativeMixP99Ns\": ").append(r.w1aOperativeMixP99Ns()).append(",\n");
                sb.append("      \"w1bHistoryHeavyP50Ns\": ").append(r.w1bHistoryHeavyP50Ns()).append(",\n");
                sb.append("      \"w1bHistoryHeavyP99Ns\": ").append(r.w1bHistoryHeavyP99Ns()).append(",\n");
                sb.append("      \"w3Traversal1HopP50Ns\": ").append(r.w3Traversal1HopP50Ns()).append(",\n");
                sb.append("      \"w3Traversal3HopP50Ns\": ").append(r.w3Traversal3HopP50Ns()).append(",\n");
                sb.append("      \"w3Traversal5HopAclP50Ns\": ").append(r.w3Traversal5HopAclP50Ns()).append(",\n");
                sb.append("      \"w4BranchesMs\": ").append(String.format(Locale.ROOT, "%.3f", r.w4BranchesMs())).append(",\n");
                sb.append("      \"w4BranchHeapDeltaBytes\": ").append(r.w4BranchHeapDeltaBytes()).append(",\n");
                sb.append("      \"w5MergeMs\": ").append(String.format(Locale.ROOT, "%.3f", r.w5MergeMs())).append(",\n");
                sb.append("      \"w6SmallCommitsMs\": ").append(String.format(Locale.ROOT, "%.3f", r.w6SmallCommitsMs())).append(",\n");
                sb.append("      \"w6LargeCommitsMs\": ").append(String.format(Locale.ROOT, "%.3f", r.w6LargeCommitsMs())).append(",\n");
                sb.append("      \"w7HistoricalAsOfP50Ns\": ").append(r.w7HistoricalAsOfP50Ns()).append(",\n");
                sb.append("      \"w7HistoricalAsOfP99Ns\": ").append(r.w7HistoricalAsOfP99Ns()).append(",\n");
                sb.append("      \"w7FullReplayMs\": ").append(String.format(Locale.ROOT, "%.3f", r.w7FullReplayMs())).append("\n");
                sb.append("    }").append(i + 1 < results.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    private record SeededFixture(
            EntityId personType,
            EntityId labelType,
            EntityId hasLabel,
            EntityId linkedTo,
            EntityId superLinked,
            List<EntityId> people,
            List<EntityId> hopRoots,
            List<EntityId> hopLeaves,
            CommitId baseCommit
    ) {
    }

    private volatile Object sink;

    public ComparisonReport runAllEngines(ComparisonConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        BaselineBenchmarkRunner.EnvironmentInfo env = BaselineBenchmarkRunner.EnvironmentInfo.capture("MultiEngineComparison");

        record EngineSpec(String name, Supplier<VersionedGraphDatabase> factory) {
        }
        List<EngineSpec> specs = List.of(
                new EngineSpec("ENGINE_A_HASHMAP", InMemoryVersionedDatabase::new),
                new EngineSpec("ENGINE_B1_COMPACT_CSR", () -> new CompactVersionedDatabase(1)),
                new EngineSpec("ENGINE_B2_JOURNAL_CKPT10", () -> new CompactVersionedDatabase(10)),
                new EngineSpec("ENGINE_C_PERSISTENT_TREE", PersistentTreeVersionedDatabase::new)
        );

        for (int w = 0; w < config.warmupRuns(); w++) {
            ComparisonConfig warmupCfg = new ComparisonConfig(
                    Math.min(config.entityCount(), 100),
                    Math.min(config.commitCount(), 12),
                    Math.min(config.branchCount(), 20),
                    config.seed() + w,
                    0,
                    Math.min(config.sampleQueries(), 40)
            );
            for (EngineSpec spec : specs) {
                sink = measureEngine(spec.name(), spec.factory(), warmupCfg);
            }
        }

        List<EngineResult> results = new ArrayList<>(specs.size());
        for (EngineSpec spec : specs) {
            results.add(measureEngine(spec.name(), spec.factory(), config));
        }
        return new ComparisonReport(config, env, List.copyOf(results));
    }

    public static void writeReportCsv(ComparisonReport report, Path outputPath) throws IOException {
        BenchmarkMetricsSupport.writeUtf8File(outputPath, report.toCsv());
    }

    public static void writeReportJson(ComparisonReport report, Path outputPath) throws IOException {
        BenchmarkMetricsSupport.writeUtf8File(outputPath, report.toJson());
    }

    private EngineResult measureEngine(String engineName, Supplier<VersionedGraphDatabase> factory, ComparisonConfig config) {
        sink = null;
        long heapBefore = BenchmarkMetricsSupport.settledUsedHeapBytes();

        VersionedGraphDatabase db = factory.get();
        SeededFixture seeded = populateBaseGraph(db, config.entityCount(), config.seed());
        long entitiesAfterBase = db.currentSnapshot().entityCount();
        long edgesAfterBase = db.currentSnapshot().edgeCount();

        // W2: sequential small audit commits
        List<CommitId> w2Commits = new ArrayList<>(config.commitCount());
        long[] commitLatencies = new long[config.commitCount()];
        Actor writer = Actor.user("w2-writer");
        long w2Start = System.nanoTime();
        for (int i = 0; i < config.commitCount(); i++) {
            long cStart = System.nanoTime();
            try (GraphTransaction tx = db.beginTransaction(writer, Reason.of("audit-" + i))) {
                EntityId p = tx.createObject(seeded.personType());
                EntityId lbl = tx.createValue(seeded.labelType(), Value.of("Audit_" + (i % 10)));
                tx.connect(p, seeded.hasLabel(), lbl);
                tx.connect(p, seeded.linkedTo(), seeded.people().get(i % seeded.people().size()));
                w2Commits.add(tx.commit());
            }
            commitLatencies[i] = System.nanoTime() - cStart;
        }
        long w2TotalNs = System.nanoTime() - w2Start;
        Arrays.sort(commitLatencies);
        double w2Throughput = config.commitCount() / Math.max(1e-9, w2TotalNs / 1_000_000_000.0);

        sink = db;
        long heapAfterCommits = BenchmarkMetricsSupport.settledUsedHeapBytes();
        long retainedHeapBytes = Math.max(0L, heapAfterCommits - heapBefore);
        double approxBytesPerEntity = entitiesAfterBase > 0 ? (double) retainedHeapBytes / entitiesAfterBase : 0.0;

        // W1a (90/9/1 operative mix) & W1b (50/10/40 historical-heavy mix)
        long[] w1aLatencies = runMixedWorkload(db, seeded, w2Commits, config.sampleQueries(), config.seed() + 101, 90, 9);
        long[] w1bLatencies = runMixedWorkload(db, seeded, w2Commits, config.sampleQueries(), config.seed() + 202, 50, 10);

        // W3: 1-hop, 3-hop, and 5-hop + ACL + Derived inference traversal
        long[] w3OneHop = measure1Hop(db.currentSnapshot(), seeded, config.sampleQueries(), config.seed() + 301);
        long[] w3ThreeHop = measureNHop(db.currentSnapshot(), seeded, 3, config.sampleQueries(), config.seed() + 302);
        long[] w3FiveHopAcl = measure5HopWithAclAndDerived(db, seeded, config.sampleQueries(), config.seed() + 303);

        // W7: historical asOf latencies & full replay
        long[] w7AsOf = measureHistoricalAsOfReads(db, seeded, w2Commits, config.sampleQueries(), config.seed() + 404);
        CommitId replayTarget = w2Commits.getLast();
        long rStart = System.nanoTime();
        GraphReader replayed = db.replayAsOf(replayTarget);
        long rElapsed = System.nanoTime() - rStart;
        sink = replayed.entityCount();

        // W4: Parallel branches (structural sharing & small branch-local deltas)
        long heapBeforeBranches = BenchmarkMetricsSupport.settledUsedHeapBytes();
        long bStart = System.nanoTime();
        for (int b = 0; b < config.branchCount(); b++) {
            String branchName = "feat/b-" + b;
            db.createBranch(branchName, seeded.baseCommit());
            if (b < Math.min(config.branchCount(), 50)) {
                try (GraphTransaction tx = db.beginTransaction(branchName, writer, Reason.of("branch-edit-" + b))) {
                    EntityId extra = tx.createObject(seeded.personType());
                    tx.connect(extra, seeded.hasLabel(), tx.createValue(seeded.labelType(), Value.of("Branch_" + b)));
                    tx.commit();
                }
            }
        }
        long bElapsed = System.nanoTime() - bStart;
        sink = db;
        long heapAfterBranches = BenchmarkMetricsSupport.settledUsedHeapBytes();
        long branchHeapDelta = Math.max(0L, heapAfterBranches - heapBeforeBranches);

        // W5: Three-way merge timing across divergent branches
        double w5MergeMs = measureThreeWayMerge(factory, config);

        // W6: Many small commits vs. few large commits (equal total ops)
        double w6SmallMs = measureBatchGranularity(factory, config, true);
        double w6LargeMs = measureBatchGranularity(factory, config, false);

        return new EngineResult(
                engineName,
                entitiesAfterBase,
                edgesAfterBase,
                db.commitHistory().size(),
                db.listBranches().size(),
                retainedHeapBytes,
                approxBytesPerEntity,
                w2Throughput,
                BenchmarkMetricsSupport.percentile(commitLatencies, 0.50),
                BenchmarkMetricsSupport.percentile(commitLatencies, 0.99),
                BenchmarkMetricsSupport.percentile(w1aLatencies, 0.50),
                BenchmarkMetricsSupport.percentile(w1aLatencies, 0.99),
                BenchmarkMetricsSupport.percentile(w1bLatencies, 0.50),
                BenchmarkMetricsSupport.percentile(w1bLatencies, 0.99),
                BenchmarkMetricsSupport.percentile(w3OneHop, 0.50),
                BenchmarkMetricsSupport.percentile(w3ThreeHop, 0.50),
                BenchmarkMetricsSupport.percentile(w3FiveHopAcl, 0.50),
                bElapsed / 1_000_000.0,
                branchHeapDelta,
                w5MergeMs,
                w6SmallMs,
                w6LargeMs,
                BenchmarkMetricsSupport.percentile(w7AsOf, 0.50),
                BenchmarkMetricsSupport.percentile(w7AsOf, 0.99),
                rElapsed / 1_000_000.0
        );
    }

    private SeededFixture populateBaseGraph(VersionedGraphDatabase db, int entityCount, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("Base dataset"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId labelType = tx.defineValueType("Label", ValueType.STRING);
            EntityId hasLabel = tx.definePredicate("hasLabel", personType, labelType);
            EntityId linkedTo = tx.definePredicate("linkedTo", personType, personType);
            EntityId superLinked = tx.definePredicate("superLinked", personType, personType);
            tx.defineSubPredicate(linkedTo, superLinked);

            EntityId alice = tx.definePrincipal("alice");
            tx.definePermissionPropagation(linkedTo, PropagationDirection.OUTGOING, Permission.READ);

            int personCount = Math.max(12, entityCount / 4);
            List<EntityId> people = new ArrayList<>(personCount);
            for (int i = 0; i < personCount; i++) {
                EntityId p = tx.createObject(personType);
                EntityId v = tx.createValue(labelType, Value.of("Person_" + (i % 50)));
                tx.connect(p, hasLabel, v);
                people.add(p);
            }

            // Build deterministic 5-hop chains for W3 + ACL propagation
            List<EntityId> hopRoots = new ArrayList<>();
            List<EntityId> hopLeaves = new ArrayList<>();
            int chains = Math.min(20, personCount / 6);
            for (int c = 0; c < chains; c++) {
                int baseIdx = c * 6;
                EntityId root = people.get(baseIdx);
                tx.grantPermission(alice, Permission.READ, root);
                hopRoots.add(root);
                for (int hop = 0; hop < 5; hop++) {
                    tx.connect(people.get(baseIdx + hop), linkedTo, people.get(baseIdx + hop + 1));
                }
                hopLeaves.add(people.get(baseIdx + 5));
            }

            // Extra random edges across population
            for (int i = 0; i < personCount; i++) {
                EntityId s = people.get(i);
                EntityId t = people.get(rng.nextInt(personCount));
                tx.connect(s, linkedTo, t);
            }

            CommitId c1 = tx.commit();
            return new SeededFixture(personType, labelType, hasLabel, linkedTo, superLinked, people, hopRoots, hopLeaves, c1);
        }
    }

    private long[] runMixedWorkload(
            VersionedGraphDatabase db,
            SeededFixture seeded,
            List<CommitId> history,
            int ops,
            long seed,
            int currentReadPct,
            int writePct
    ) {
        SplittableRandom rng = new SplittableRandom(seed);
        long[] latencies = new long[ops];
        Actor actor = Actor.user("mix-actor");
        long checksum = 0;

        for (int i = 0; i < ops; i++) {
            int roll = rng.nextInt(100);
            long t0 = System.nanoTime();
            if (roll < currentReadPct) {
                EntityId p = seeded.people().get(rng.nextInt(seeded.people().size()));
                List<Edge> edges = db.currentSnapshot().findOutgoingEdges(p, seeded.hasLabel());
                checksum += edges.size();
            } else if (roll < currentReadPct + writePct) {
                try (GraphTransaction tx = db.beginTransaction(actor, Reason.of("mix-write-" + i))) {
                    EntityId np = tx.createObject(seeded.personType());
                    tx.connect(np, seeded.hasLabel(), tx.createValue(seeded.labelType(), Value.of("Mix_" + i)));
                    tx.commit();
                }
            } else {
                CommitId histId = history.get(rng.nextInt(history.size()));
                EntityId p = seeded.people().get(rng.nextInt(seeded.people().size()));
                checksum += db.asOf(histId).findOutgoingEdges(p).size();
            }
            latencies[i] = System.nanoTime() - t0;
        }
        sink = checksum;
        Arrays.sort(latencies);
        return latencies;
    }

    private long[] measure1Hop(GraphReader reader, SeededFixture seeded, int queries, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        long[] latencies = new long[queries];
        long sum = 0;
        for (int i = 0; i < queries; i++) {
            EntityId p = seeded.people().get(rng.nextInt(seeded.people().size()));
            long t0 = System.nanoTime();
            List<Edge> out = reader.findOutgoingEdges(p, seeded.hasLabel());
            if (!out.isEmpty()) {
                sum += reader.findEntity(out.getFirst().targetId()).map(n -> n.id().value()).orElse(0L);
            }
            latencies[i] = System.nanoTime() - t0;
        }
        sink = sum;
        Arrays.sort(latencies);
        return latencies;
    }

    private long[] measureNHop(GraphReader reader, SeededFixture seeded, int hops, int queries, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        long[] latencies = new long[queries];
        long sum = 0;
        for (int i = 0; i < queries; i++) {
            EntityId cursor = seeded.hopRoots().get(rng.nextInt(seeded.hopRoots().size()));
            long t0 = System.nanoTime();
            for (int h = 0; h < hops; h++) {
                List<Edge> out = reader.findOutgoingEdges(cursor, seeded.linkedTo());
                if (out.isEmpty()) {
                    break;
                }
                cursor = out.getFirst().targetId();
            }
            sum += cursor.value();
            latencies[i] = System.nanoTime() - t0;
        }
        sink = sum;
        Arrays.sort(latencies);
        return latencies;
    }

    private long[] measure5HopWithAclAndDerived(
            VersionedGraphDatabase db,
            SeededFixture seeded,
            int queries,
            long seed
    ) {
        SplittableRandom rng = new SplittableRandom(seed);
        int n = Math.min(queries, 150);
        long[] latencies = new long[n];
        AuthorizedGraphReader alice = db.authorizedCurrentSnapshot("alice");
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int idx = rng.nextInt(seeded.hopRoots().size());
            EntityId cursor = seeded.hopRoots().get(idx);
            EntityId leaf = seeded.hopLeaves().get(idx);
            long t0 = System.nanoTime();
            for (int h = 0; h < 5; h++) {
                List<Edge> out = alice.findOutgoingEdges(cursor, seeded.linkedTo());
                if (out.isEmpty()) {
                    break;
                }
                cursor = out.getFirst().targetId();
            }
            var decision = alice.explainAccess(Permission.READ, leaf);
            var derived = alice.findAllOutgoingAssertions(seeded.hopRoots().get(idx), seeded.superLinked());
            sum += (decision.allowed() ? 1L : 0L) + derived.size();
            latencies[i] = System.nanoTime() - t0;
        }
        sink = sum;
        Arrays.sort(latencies);
        return latencies;
    }

    private long[] measureHistoricalAsOfReads(
            VersionedGraphDatabase db,
            SeededFixture seeded,
            List<CommitId> commits,
            int queries,
            long seed
    ) {
        SplittableRandom rng = new SplittableRandom(seed);
        int n = Math.min(queries, 200);
        long[] latencies = new long[n];
        long sum = 0;
        for (int i = 0; i < n; i++) {
            CommitId cid = commits.get(rng.nextInt(commits.size()));
            EntityId p = seeded.people().get(rng.nextInt(seeded.people().size()));
            long t0 = System.nanoTime();
            GraphReader snap = db.asOf(cid);
            sum += snap.findOutgoingEdges(p, seeded.hasLabel()).size();
            latencies[i] = System.nanoTime() - t0;
        }
        sink = sum;
        Arrays.sort(latencies);
        return latencies;
    }

    private double measureThreeWayMerge(Supplier<VersionedGraphDatabase> factory, ComparisonConfig config) {
        VersionedGraphDatabase db = factory.get();
        SeededFixture seeded = populateBaseGraph(db, Math.min(config.entityCount(), 500), config.seed() + 555);
        db.createBranch("merge-a", seeded.baseCommit());
        db.createBranch("merge-b", seeded.baseCommit());

        try (GraphTransaction txA = db.beginTransaction("merge-a", Actor.user("a"), Reason.of("branch-a"))) {
            for (int i = 0; i < 15; i++) {
                EntityId o = txA.createObject(seeded.personType());
                txA.connect(o, seeded.hasLabel(), txA.createValue(seeded.labelType(), Value.of("MA-" + i)));
            }
            txA.commit();
        }
        try (GraphTransaction txB = db.beginTransaction("merge-b", Actor.user("b"), Reason.of("branch-b"))) {
            for (int i = 0; i < 15; i++) {
                EntityId o = txB.createObject(seeded.personType());
                txB.connect(o, seeded.hasLabel(), txB.createValue(seeded.labelType(), Value.of("MB-" + i)));
            }
            txB.commit();
        }

        long t0 = System.nanoTime();
        MergeResult m1 = db.merge("merge-a", "main", Actor.user("rel"), Reason.of("merge a"), MergePolicy.developmentDefault());
        MergeResult m2 = db.merge("merge-b", "main", Actor.user("rel"), Reason.of("merge b"), MergePolicy.developmentDefault());
        long elapsed = System.nanoTime() - t0;
        if (!m1.succeeded() || !m2.succeeded()) {
            throw new IllegalStateException("Unexpected merge conflict during W5 benchmark");
        }
        return elapsed / 1_000_000.0;
    }

    private double measureBatchGranularity(
            Supplier<VersionedGraphDatabase> factory,
            ComparisonConfig config,
            boolean manySmallCommits
    ) {
        VersionedGraphDatabase db = factory.get();
        EntityId pType;
        EntityId vType;
        EntityId pred;
        try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("W6 schema"))) {
            pType = tx.defineObjectType("Item");
            vType = tx.defineValueType("Code", ValueType.STRING);
            pred = tx.definePredicate("hasCode", pType, vType);
            tx.commit();
        }

        int totalItems = Math.max(20, Math.min(config.commitCount() * 2, 100));
        int itemsPerCommit = manySmallCommits ? 2 : totalItems / 2;
        int numCommits = totalItems / itemsPerCommit;

        long t0 = System.nanoTime();
        int seq = 0;
        for (int c = 0; c < numCommits; c++) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("w6"), Reason.of("w6-" + c))) {
                for (int j = 0; j < itemsPerCommit; j++) {
                    EntityId item = tx.createObject(pType);
                    tx.connect(item, pred, tx.createValue(vType, Value.of("C_" + (seq++))));
                }
                tx.commit();
            }
        }
        return (System.nanoTime() - t0) / 1_000_000.0;
    }
}
