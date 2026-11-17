package io.veg.cli;

import io.veg.benchmarks.AssertionModelComparisonRunner;
import io.veg.benchmarks.AssertionModelComparisonRunner.ExperimentConfig;
import io.veg.benchmarks.AssertionModelComparisonRunner.ExperimentReport;
import io.veg.benchmarks.AssertionModelComparisonRunner.ModelRunResult;
import io.veg.benchmarks.BaselineBenchmarkRunner;
import io.veg.benchmarks.BaselineBenchmarkRunner.BenchmarkConfig;
import io.veg.benchmarks.BaselineBenchmarkRunner.BenchmarkReport;
import io.veg.benchmarks.BaselineBenchmarkRunner.IterationResult;
import io.veg.benchmarks.StorageEngineComparisonRunner;
import io.veg.benchmarks.StorageEngineComparisonRunner.ComparisonConfig;
import io.veg.benchmarks.StorageEngineComparisonRunner.ComparisonReport;
import io.veg.benchmarks.StorageEngineComparisonRunner.EngineResult;
import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphSession;
import io.veg.engine.GraphTransaction;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.TypeMismatchException;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.engine.memory.CompactVersionedDatabase;
import io.veg.engine.memory.InMemoryGraphEngine;
import io.veg.engine.memory.InMemoryVersionedDatabase;
import io.veg.engine.memory.PersistentTreeVersionedDatabase;
import io.veg.engine.persistent.CrashPoint;
import io.veg.engine.persistent.DurabilityMode;
import io.veg.engine.persistent.PersistentDiskVersionedDatabase;
import io.veg.engine.persistent.RecoveryReport;
import io.veg.engine.persistent.SimulatedCrashException;
import io.veg.fixtures.RdfBenchmarkDatasets;
import io.veg.model.AccessDecision;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.GraphDiff;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Permission;
import io.veg.model.PropagationDirection;
import io.veg.model.QueryRow;
import io.veg.model.RdfExportOptions;
import io.veg.model.RdfExportResult;
import io.veg.model.RdfFormat;
import io.veg.model.RdfImportResult;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.SavepointId;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

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
            case "import-rdf" -> runImportRdf(args, out, err);
            case "benchmark" -> runBenchmark(args, out, err);
            case "assertion-experiment" -> runAssertionExperiment(args, out, err);
            case "storage-experiment" -> runStorageExperiment(args, out, err);
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

        runStage3Demo(out);
    }

    private static void runStage3Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 3 Versioned Transactions, Savepoints, Diff & Revert Demo ===");
        VersionedGraphDatabase db = new InMemoryVersionedDatabase();
        EntityId personType;
        EntityId firstNameType;
        EntityId hasFirstName;
        EntityId person1;
        CommitId c1;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u123"), Reason.of("Initial person"), "CRM-1842")) {
            personType = tx.defineObjectType("Person");
            firstNameType = tx.defineValueType("FirstName", ValueType.STRING);
            hasFirstName = tx.definePredicate("hasFirstName", personType, firstNameType);
            person1 = tx.createObject(personType);
            tx.connect(person1, hasFirstName, tx.createValue(firstNameType, Value.of("Thomas")));
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u456"), Reason.of("Rename schema & savepoint test"))) {
            tx.renameSchemaElement(personType, "Customer");
            SavepointId sp = tx.createSavepoint("after-rename");
            tx.createObject(personType); // will be rolled back to savepoint
            tx.rollbackToSavepoint(sp);
            c2 = tx.commit();
        }

        GraphDiff diff = db.diff(c1, c2);
        CommitId c3 = db.revert(c2, Actor.user("admin"), Reason.of("Revert schema rename"));
        GraphReader replayed = db.replayAsOf(c3);

        out.println("Commits: " + c1 + " -> " + c2 + " -> " + c3);
        out.println("asOf(" + c1 + ") Person type name = " + db.asOf(c1).resolveName(personType).orElse("?"));
        out.println("asOf(" + c2 + ") Person type name = " + db.asOf(c2).resolveName(personType).orElse("?"));
        out.println("diff(" + c1 + ", " + c2 + "): updatedValues=" + diff.updatedValues().size());
        out.println("After revert(" + c2 + ") at " + c3 + ": diff(" + c1 + ", " + c3 + ").isEmpty()=" + db.diff(c1, c3).isEmpty()
                + ", replay matches live=" + replayed.allEntities().equals(db.currentSnapshot().allEntities()));

        runStage4Demo(out);
    }

    private static void runStage4Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 4 Branches, Selective Staging & 3-Way Merge Demo ===");
        VersionedGraphDatabase db = new InMemoryVersionedDatabase();
        EntityId customerType;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base"))) {
            customerType = tx.defineObjectType("Customer");
            base = tx.commit();
        }

        db.createBranch("feature/crm", base);
        StagingWorkspace ws = db.createStagingWorkspace("feature/crm");
        EntityId regionType = ws.defineObjectType("Region");
        EntityId euWest = ws.createObject(regionType);
        EntityId unstagedDraft = ws.createObject(customerType);
        ws.stageEntity(regionType);
        ws.stageEntity(euWest);
        ws.commitStaged(Actor.user("dev"), Reason.of("Stage schema & reference Region only"));

        EntityId opCustomer;
        try (GraphTransaction tx = db.beginTransaction(
                "feature/crm", Actor.user("svc"), Reason.of("Operational signup"), null, DataCategory.OPERATIONAL)) {
            opCustomer = tx.createObject(customerType);
            tx.commit();
        }

        MergeResult merged = db.merge("feature/crm", "main",
                Actor.user("release"), Reason.of("Promote feature/crm"), MergePolicy.developmentDefault());
        GraphReader mainSnap = db.branchSnapshot("main");
        out.println("Merge succeeded=" + merged.succeeded()
                + ", main has Region=" + mainSnap.findTypeByName("Region").isPresent()
                + ", excluded operational customer=" + mainSnap.findEntity(opCustomer).isEmpty()
                + ", unstaged draft kept separate=" + mainSnap.findEntity(unstagedDraft).isEmpty());

        runStage5Demo(out);
    }

    private static void runStage5Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 5 Access Control (Deny Wins), Time-Travel Security & Derived Assertions Demo ===");
        VersionedGraphDatabase db = new InMemoryVersionedDatabase();
        Actor admin = Actor.user("admin");
        EntityId project;
        EntityId doc;
        EntityId bob;
        EntityId salaryVal;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(admin, Reason.of("Stage 5 ACL and semantic rules setup"))) {
            EntityId projectType = tx.defineObjectType("Project");
            EntityId docType = tx.defineObjectType("Document");
            EntityId salaryType = tx.defineValueType("Salary", ValueType.INT);
            EntityId containsDoc = tx.definePredicate("containsDocument", projectType, docType);
            EntityId containedIn = tx.definePredicate("containedIn", docType, projectType);
            EntityId hasBudget = tx.definePredicate("hasBudget", projectType, salaryType);

            tx.defineInversePredicate(containsDoc, containedIn);
            tx.definePermissionPropagation(containsDoc, PropagationDirection.OUTGOING, Permission.READ);

            project = tx.createObject(projectType);
            doc = tx.createObject(docType);
            salaryVal = tx.createValue(salaryType, Value.of(500_000L));
            tx.connect(project, containsDoc, doc);
            tx.connect(project, hasBudget, salaryVal);

            EntityId alice = tx.definePrincipal("alice");
            bob = tx.definePrincipal("bob");
            tx.grantPermission(alice, Permission.READ, project);
            tx.grantPermission(bob, Permission.READ, doc);
            tx.denyPermission(alice, Permission.READ, hasBudget);
            c1 = tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction(admin, Reason.of("Revoke Bob read via Deny Wins"))) {
            tx.denyPermission(bob, Permission.READ, doc);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot("alice");
        AccessDecision aliceReadDoc = aliceReader.explainAccess(Permission.READ, doc);
        AccessDecision aliceWriteDoc = aliceReader.explainAccess(Permission.WRITE, doc);
        AccessDecision bobTimeTravel = db.authorizedAsOf(c1, "bob").explainAccess(Permission.READ, doc);
        List<DerivedAssertion> derived = db.currentSnapshot().findDerivedOutgoingAssertions(doc);

        out.println("Alice read Doc via propagation=" + aliceReadDoc.allowed()
                + " (" + aliceReadDoc.reason() + ", writeAllowed=" + aliceWriteDoc.allowed() + ")");
        out.println("Alice salaryVal hidden=" + aliceReader.findEntity(salaryVal).isEmpty()
                + ", Bob time-travel after revoke allowed=" + bobTimeTravel.allowed()
                + " (" + bobTimeTravel.reason() + ")");
        out.println("Doc explicit outgoing=" + db.currentSnapshot().findOutgoingEdges(doc).size()
                + ", derived inverse count=" + derived.size()
                + " (" + derived.getFirst().origin() + ")");

        runStage6Demo(out);
    }

    private static void runStage6Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 6 Storage-Independent Query DSL & RDF (LUBM/BSBM) Demo ===");

        VersionedGraphDatabase lubmDb = new InMemoryVersionedDatabase();
        RdfImportResult lubmImport;
        try (GraphTransaction tx = lubmDb.beginTransaction(Actor.user("admin"), Reason.of("Import LUBM Turtle"))) {
            lubmImport = tx.importTurtle(RdfBenchmarkDatasets.lubmUniversityTurtle());
            tx.commit();
        }
        List<QueryRow> lehighProfs = lubmDb.currentSnapshot().executeQuery("""
                USING DERIVED
                MATCH (p:Professor)
                JOIN (p)-[memberOf]->(u:University)
                WHERE u.name CONTAINS "Lehigh"
                SELECT p.name, p.tenureYears
                ORDER BY p.tenureYears DESC
                """);
        out.println("LUBM imported objects=" + lubmImport.importedObjectCount()
                + ", Lehigh professors inferred via USING DERIVED memberOf=" + lehighProfs.size()
                + " (top=" + lehighProfs.getFirst().stringValue("p.name").orElse("?") + ")");

        VersionedGraphDatabase bsbmDb = new InMemoryVersionedDatabase();
        RdfImportResult bsbmImport;
        try (GraphTransaction tx = bsbmDb.beginTransaction(Actor.user("admin"), Reason.of("Import BSBM Turtle"))) {
            bsbmImport = tx.importTurtle(RdfBenchmarkDatasets.bsbmEcommerceTurtle());
            tx.commit();
        }
        List<QueryRow> bestOffers = bsbmDb.currentSnapshot().executeQuery("""
                MATCH (o:Offer)
                JOIN (o)-[product]->(p:Product)
                WHERE o.inStock = true AND o.price <= 500.00
                SELECT p.productName, o.price
                ORDER BY o.price ASC
                LIMIT 2
                """);
        RdfExportResult ttlExport = bsbmDb.currentSnapshot().exportTurtle();
        out.println("BSBM imported objects=" + bsbmImport.importedObjectCount()
                + ", top in-stock offer=" + bestOffers.getFirst().stringValue("p.productName").orElse("?")
                + " @ " + bestOffers.getFirst().decimalValue("o.price").orElse(BigDecimal.ZERO));
        out.println("T18 Turtle export triples=" + ttlExport.exportedTripleCount()
                + ", reifiedAnnotations=" + ttlExport.reifiedAnnotationCount()
                + ", warnings=" + ttlExport.warnings().size());

        runStage7Demo(out);
    }

    private static void runStage7Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 7 Multi-Engine Equivalence & Persistent Structural Sharing Demo ===");

        VersionedGraphDatabase engineA = new InMemoryVersionedDatabase();
        CompactVersionedDatabase engineB = new CompactVersionedDatabase(3);
        PersistentTreeVersionedDatabase engineC = new PersistentTreeVersionedDatabase();

        List<VersionedGraphDatabase> engines = List.of(engineA, engineB, engineC);
        for (VersionedGraphDatabase db : engines) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("admin"), Reason.of("seed LUBM"))) {
                tx.importTurtle(RdfBenchmarkDatasets.lubmUniversityTurtle());
                tx.commit();
            }
            for (int i = 0; i < 6; i++) {
                try (GraphTransaction tx = db.beginTransaction(Actor.user("writer"), Reason.of("inc-" + i))) {
                    EntityId profType = tx.findTypeByName("Professor").orElseThrow();
                    EntityId strType = tx.findTypeByName("rdf.literal.STRING").orElseThrow();
                    EntityId namePred = tx.findPredicateByName("name").orElseThrow();
                    EntityId p = tx.createObject(profType);
                    tx.connect(p, namePred, tx.createValue(strType, Value.of("Visiting Prof " + i)));
                    tx.commit();
                }
            }
        }

        String dsl = "USING DERIVED MATCH (p:Professor) JOIN (p)-[memberOf]->(u:University) SELECT p.name";
        int countA = engineA.currentSnapshot().executeQuery(dsl).size();
        int countB = engineB.currentSnapshot().executeQuery(dsl).size();
        int countC = engineC.currentSnapshot().executeQuery(dsl).size();

        out.println("Same LUBM+inc query across EngineA/B/C rows=" + countA + "/" + countB + "/" + countC);
        out.println("Engine B checkpoints stored=" + engineB.storedCheckpointCount()
                + " across " + engineB.commitHistory().size() + " commits (internedValues=" + engineB.internedDistinctValueCount() + ")");
        out.println("Engine C unique persistent radix-32 trie nodes across "
                + engineC.commitHistory().size() + " commits=" + engineC.countTotalUniqueTrieNodesAcrossAllCommits());

        runStage8Demo(out);
    }

    private static void runStage8Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 8 Persistent WAL, Checkpoints, Cold Archive & Crash Recovery Demo ===");
        Path tempRoot = null;
        try {
            tempRoot = Files.createTempDirectory("veg-stage8-demo");
            CommitId headBeforeCrash;
            EntityId sensorType;
            try (PersistentDiskVersionedDatabase diskDb = PersistentDiskVersionedDatabase.open(
                    tempRoot, DurabilityMode.FLUSH_OS_BUFFER, 3)) {
                try (GraphTransaction tx = diskDb.beginTransaction(Actor.user("ops"), Reason.of("schema"))) {
                    sensorType = tx.defineObjectType("Sensor");
                    tx.commit();
                }
                for (int i = 0; i < 4; i++) {
                    try (GraphTransaction tx = diskDb.beginTransaction(Actor.user("ops"), Reason.of("reading-" + i))) {
                        tx.createObject(sensorType);
                        tx.commit();
                    }
                }
                headBeforeCrash = diskDb.headCommitId();
                diskDb.archiveHistoryBefore(new CommitId(3));

                diskDb.setCrashPoint(CrashPoint.MID_WAL_FRAME_WRITE);
                try (GraphTransaction tx = diskDb.beginTransaction(Actor.user("ops"), Reason.of("torn-commit"))) {
                    tx.createObject(sensorType);
                    tx.commit();
                } catch (SimulatedCrashException expected) {
                    out.println("T17 simulated crash mid-WAL -> " + expected.crashPoint());
                }
            }

            try (PersistentDiskVersionedDatabase recovered = PersistentDiskVersionedDatabase.open(
                    tempRoot, DurabilityMode.FLUSH_OS_BUFFER, 3)) {
                RecoveryReport report = recovered.lastRecoveryReport();
                out.println("Recovered from checkpoint=" + report.loadedCheckpointId().map(CommitId::toString).orElse("none")
                        + ", replayedWalCommits=" + report.replayedWalCommits()
                        + ", truncatedTailFrames=" + report.truncatedTailFrames()
                        + ", coldArchiveSegments=" + recovered.coldArchiveSegmentCount()
                        + ", headMatchesPreCrash=" + recovered.headCommitId().equals(headBeforeCrash));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Stage 8 demo failed", ex);
        } finally {
            if (tempRoot != null) {
                try (Stream<Path> walk = Files.walk(tempRoot)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
            } catch (IOException ignored) {
                    // Best-effort temp cleanup.
                }
            }
        }

        runStage10Demo(out);
    }

    private static void runStage10Demo(PrintStream out) {
        out.println();
        out.println("=== VEG Stage 10 MVCC, Auto-Indexing, Bitemporality, Cherry-Pick & GDPR Redaction Demo ===");
        VersionedGraphDatabase db = new PersistentTreeVersionedDatabase();
        Instant q1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant q2 = Instant.parse("2026-04-01T00:00:00Z");

        EntityId empType;
        EntityId ssnType;
        EntityId hasSsn;
        EntityId emp;
        EntityId ssnVal;
        CommitId base;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("hr"), Reason.of("onboard"), "HR-100")) {
            empType = tx.defineObjectType("Contractor");
            ssnType = tx.defineValueType("TaxId", ValueType.STRING);
            hasSsn = tx.definePredicate("hasTaxId", empType, ssnType);
            emp = tx.createObject(empType);
            ssnVal = tx.createValue(ssnType, Value.of("DE-999-SECRET"));
            tx.connectWithValidity(emp, hasSsn, ssnVal, q1, q2);
            base = tx.commit();
        }

        CommitId retryFirst = db.executeIdempotent(
                "main", "cli-idem-1", Actor.user("api"), Reason.of("idempotent onboard"), "HR-100",
                tx -> tx.createObject(empType));
        CommitId retrySecond = db.executeIdempotent(
                "main", "cli-idem-1", Actor.user("api"), Reason.of("idempotent onboard"), "HR-100",
                tx -> tx.createObject(empType));

        db.createBranch("feature/fix", base);
        CommitId cHotfix;
        try (GraphTransaction tx = db.beginTransaction("feature/fix", Actor.user("dev"), Reason.of("patch"))) {
            tx.createObject(empType);
            cHotfix = tx.commit();
        }
        MergeResult cherry = db.cherryPick(cHotfix, "main", Actor.user("release"), Reason.of("cherry-pick"));
        RedactionReceipt receipt = db.redactEntityPayload(ssnVal, Actor.user("dpo"), Reason.of("GDPR Art. 17"));

        out.println("Type-indexed contractors=" + db.currentSnapshot().findEntitiesByType(empType).size()
                + ", bitemporal valid(Feb)=" + db.currentSnapshot().validAt(Instant.parse("2026-02-15T00:00:00Z")).findOutgoingEdges(emp, hasSsn).size()
                + ", valid(Apr)=" + db.currentSnapshot().validAt(q2).findOutgoingEdges(emp, hasSsn).size());
        out.println("Idempotent retry sameCommit=" + retryFirst.equals(retrySecond)
                + ", cherryPickSucceeded=" + cherry.succeeded()
                + ", correlatedHR100=" + db.commitsByCorrelationId("HR-100").size()
                + ", redactedValue=" + db.asOf(base).findEntity(ssnVal).orElseThrow().payload().orElseThrow()
                + ", redactionAffectedCommits=" + receipt.affectedCommitCount());
    }

    private static int runImportRdf(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path inputFile = null;
        RdfFormat format = null;
        String queryDsl = null;
        Path exportFile = null;

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + flag);
                return 1;
            }
            String val = args[++i];
            switch (flag) {
                case "--input" -> inputFile = Path.of(val);
                case "--format" -> {
                    if ("turtle".equalsIgnoreCase(val) || "ttl".equalsIgnoreCase(val)) {
                        format = RdfFormat.TURTLE;
                    } else if ("ntriples".equalsIgnoreCase(val) || "nt".equalsIgnoreCase(val)) {
                        format = RdfFormat.N_TRIPLES;
                    } else {
                        err.println("Unsupported RDF format: " + val);
                        return 1;
                    }
                }
                case "--query" -> queryDsl = val;
                case "--export" -> exportFile = Path.of(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }

        if (inputFile == null) {
            err.println("import-rdf requires --input <file>");
            return 1;
        }
        if (format == null) {
            String lower = inputFile.getFileName().toString().toLowerCase(Locale.ROOT);
            format = lower.endsWith(".nt") ? RdfFormat.N_TRIPLES : RdfFormat.TURTLE;
        }

        String content = Files.readString(inputFile, StandardCharsets.UTF_8);
        VersionedGraphDatabase db = new InMemoryVersionedDatabase();
        RdfImportResult imported;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("cli"), Reason.of("CLI RDF import: " + inputFile.getFileName()))) {
            imported = tx.importRdf(content, format);
            tx.commit();
        }

        out.println("Imported RDF (" + format + "): objects=" + imported.importedObjectCount()
                + ", literals=" + imported.importedLiteralCount()
                + ", edges=" + imported.importedEdgeCount()
                + ", reifiedAnnotations=" + imported.importedAnnotationCount());

        GraphReader snap = db.currentSnapshot();
        if (queryDsl != null) {
            List<QueryRow> rows = snap.executeQuery(queryDsl);
            out.println("Query rows=" + rows.size());
            for (QueryRow row : rows) {
                out.println("  " + row.projectedValues() + " " + row.bindings());
            }
        }

        if (exportFile != null) {
            RdfExportOptions opts = format == RdfFormat.N_TRIPLES
                    ? RdfExportOptions.nTriplesDefault()
                    : RdfExportOptions.turtleDefault();
            RdfExportResult exported = snap.exportRdf(opts);
            Files.writeString(exportFile, exported.content(), StandardCharsets.UTF_8);
            out.println("Exported triples=" + exported.exportedTripleCount()
                    + ", reified=" + exported.reifiedAnnotationCount()
                    + ", warnings=" + exported.warnings().size()
                    + " -> " + exportFile);
        }
        return 0;
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

    private static int runStorageExperiment(String[] args, PrintStream out, PrintStream err) throws IOException {
        int entities = 2_000;
        int commits = 50;
        int branches = 500;
        long seed = 42L;
        int warmup = 1;
        int queries = 400;
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
                case "--commits" -> commits = Integer.parseInt(val);
                case "--branches" -> branches = Integer.parseInt(val);
                case "--seed" -> seed = Long.parseLong(val);
                case "--warmup" -> warmup = Integer.parseInt(val);
                case "--queries" -> queries = Integer.parseInt(val);
                case "--output" -> csvOutput = Path.of(val);
                case "--json" -> jsonOutput = Path.of(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }

        ComparisonConfig config = new ComparisonConfig(entities, commits, branches, seed, warmup, queries);
        StorageEngineComparisonRunner runner = new StorageEngineComparisonRunner();
        ComparisonReport report = runner.runAllEngines(config);

        out.printf(Locale.ROOT,
                "Storage Experiment | Java=%s | Entities=%d | Commits=%d | Branches=%d | Seed=%d%n",
                report.environment().javaVersion(),
                config.entityCount(),
                config.commitCount(),
                config.branchCount(),
                config.seed());

        for (EngineResult r : report.results()) {
            out.printf(Locale.ROOT,
                    "%s: baseEntities=%d commits=%d branches=%d heap=%.2f MB (%.1f B/ent) W2=%.1f c/s W1a=%.0f ns W3-5hop+ACL=%.0f ns W4-branches=%.2f ms W5-merge=%.2f ms W7-asOf=%.0f ns%n",
                    r.engineName(),
                    r.entitiesAfterBase(),
                    r.totalCommits(),
                    r.totalBranches(),
                    r.retainedHeapBytes() / (1024.0 * 1024.0),
                    r.approxBytesPerEntity(),
                    r.w2CommitThroughputPerSec(),
                    (double) r.w1aOperativeMixP50Ns(),
                    (double) r.w3Traversal5HopAclP50Ns(),
                    r.w4BranchesMs(),
                    r.w5MergeMs(),
                    (double) r.w7HistoricalAsOfP50Ns());
        }

        if (csvOutput != null) {
            StorageEngineComparisonRunner.writeReportCsv(report, csvOutput);
            out.println("Wrote CSV report to " + csvOutput);
        }
        if (jsonOutput != null) {
            StorageEngineComparisonRunner.writeReportJson(report, jsonOutput);
            out.println("Wrote JSON report to " + jsonOutput);
        }
        return 0;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  veg-cli demo");
        out.println("  veg-cli import-rdf --input <file.ttl|.nt> [--format turtle|ntriples] [--query \"<DSL>\"] [--export <out.ttl>]");
        out.println("  veg-cli benchmark [--entities N] [--seed S] [--shared-ratio R] [--provenance-ratio P] [--warmup W] [--iterations I] [--queries Q] [--output results.csv] [--json results.json]");
        out.println("  veg-cli assertion-experiment [--pairs N] [--seed S] [--densities 1,2,5] [--warmup W] [--iterations I] [--queries Q] [--output results.csv] [--json results.json]");
        out.println("  veg-cli storage-experiment [--entities N] [--commits C] [--branches B] [--seed S] [--warmup W] [--queries Q] [--output results.csv] [--json results.json]");
    }
}
