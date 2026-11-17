package io.veg.fixtures;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.OptimisticCommitConflictException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.MergeResult;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.QueryRow;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.Value;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 10 contract suite verifying MVCC concurrency, optimistic retry and
 * idempotency, correlation auditing ({@code V-15}), selective cherry-pick ({@code V-16}),
 * bitemporal validity windows ({@code V-14}), automatic secondary indexing + ACL memoization
 * ({@code P-05}), and GDPR payload redaction ({@code S-09}).
 */
public abstract class Stage10ContractTestSuite {

    protected abstract VersionedGraphDatabase createDatabase();

    @Test
    void v14_bitemporalValidityWindowFiltersEntitiesAndEdgesByBusinessValidTime() {
        VersionedGraphDatabase db = createDatabase();
        Instant jan1 = Instant.parse("2025-01-01T00:00:00Z");
        Instant jun1 = Instant.parse("2025-06-01T00:00:00Z");
        Instant jul1 = Instant.parse("2025-07-01T00:00:00Z");
        Instant dec1 = Instant.parse("2025-12-01T00:00:00Z");

        EntityId empType;
        EntityId roleType;
        EntityId titleType;
        EntityId hasRole;
        EntityId hasTitle;
        EntityId alice;
        EntityId juniorRole;
        EntityId seniorRole;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("hr"), Reason.of("bitemporal employment"))) {
            empType = tx.defineObjectType("Employee");
            roleType = tx.defineObjectType("Role");
            titleType = tx.defineValueType("RoleTitle", ValueType.STRING);
            hasRole = tx.definePredicate("hasRole", empType, roleType);
            hasTitle = tx.definePredicate("roleTitle", roleType, titleType);

            alice = tx.createObject(empType);
            juniorRole = tx.createObject(roleType);
            tx.connect(juniorRole, hasTitle, tx.createValue(titleType, Value.of("Junior Engineer")));

            seniorRole = tx.createObject(roleType);
            tx.connect(seniorRole, hasTitle, tx.createValue(titleType, Value.of("Senior Engineer")));

            // Half-open validity intervals [validFrom, validTo)
            tx.connectWithValidity(alice, hasRole, juniorRole, jan1, jun1);
            tx.connectWithValidity(alice, hasRole, seniorRole, jun1, null);
            tx.commit();
        }

        GraphReader march = db.currentSnapshot().validAt(Instant.parse("2025-03-15T00:00:00Z"));
        assertThat(march.findOutgoingEdges(alice, hasRole))
                .extracting(Edge::targetId)
                .containsExactly(juniorRole);

        // Exact half-open boundary check: validTo=jun1 excludes Junior, validFrom=jun1 includes Senior
        GraphReader atJun1 = db.currentSnapshot().validAt(jun1);
        assertThat(atJun1.findOutgoingEdges(alice, hasRole))
                .extracting(Edge::targetId)
                .containsExactly(seniorRole);

        List<QueryRow> julyQuery = db.currentSnapshot().validAt(jul1).executeQuery("""
                MATCH (e:Employee)
                JOIN (e)-[hasRole]->(r:Role)
                SELECT r.roleTitle
                """);
        assertThat(julyQuery).hasSize(1);
        assertThat(julyQuery.getFirst().stringValue("r.roleTitle")).contains("Senior Engineer");

        GraphReader beforeEmployment = db.currentSnapshot().validAt(Instant.parse("2024-12-31T23:59:59Z"));
        assertThat(beforeEmployment.findOutgoingEdges(alice, hasRole)).isEmpty();
        assertThat(db.currentSnapshot().validAt(dec1).findOutgoingEdges(alice, hasRole))
                .extracting(Edge::targetId)
                .containsExactly(seniorRole);
    }

    @Test
    void v14_bitemporalSystemTimeVsValidTimeDistinguishesRetroactiveCorrectionFromHistoricalKnowledge()
            throws InterruptedException {
        VersionedGraphDatabase db = createDatabase();
        Instant validMar = Instant.parse("2025-03-10T00:00:00Z");
        Instant q1Start = Instant.parse("2025-01-01T00:00:00Z");
        Instant q2Start = Instant.parse("2025-04-01T00:00:00Z");

        EntityId pricePred;
        EntityId priceType;
        EntityId sku;
        EntityId oldPriceEdge;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("pricing"), Reason.of("initial Q1 price"))) {
            EntityId skuType = tx.defineObjectType("Sku");
            priceType = tx.defineValueType("Price", ValueType.DECIMAL);
            pricePred = tx.definePredicate("price", skuType, priceType);
            sku = tx.createObject(skuType);
            oldPriceEdge = tx.connectWithValidity(
                    sku, pricePred, tx.createValue(priceType, Value.of(new BigDecimal("100.00"))), q1Start, null);
            tx.commit();
        }

        // Guarantee distinct system clock timestamp between commits
        Thread.sleep(15);
        Instant knownBeforeCorrection = Instant.now();
        Thread.sleep(15);

        // Retroactive correction committed later in system time for [q1Start, q2Start)
        try (GraphTransaction tx = db.beginTransaction(Actor.user("pricing"), Reason.of("retroactive Q1 rebate"))) {
            tx.setValidityWindow(oldPriceEdge, q2Start, null);
            tx.connectWithValidity(
                    sku, pricePred, tx.createValue(priceType, Value.of(new BigDecimal("85.00"))), q1Start, q2Start);
            tx.commit();
        }

        // "What did we know before the retroactive correction about March 10?" -> 100.00
        GraphReader historicalKnowledge = db.asOfSystemAndValidTime("main", knownBeforeCorrection, validMar);
        assertThat(historicalKnowledge.findOutgoingEdges(sku, pricePred)).hasSize(1);
        EntityId knownPriceId = historicalKnowledge.findOutgoingEdges(sku, pricePred).getFirst().targetId();
        assertThat(historicalKnowledge.findEntity(knownPriceId).orElseThrow().payload())
                .contains(Value.of(new BigDecimal("100.00")));

        // "What do we know now was valid on March 10?" -> 85.00
        GraphReader correctedKnowledge = db.asOfBitemporal(db.headCommitId(), validMar);
        assertThat(correctedKnowledge.findOutgoingEdges(sku, pricePred)).hasSize(1);
        EntityId actualPriceId = correctedKnowledge.findOutgoingEdges(sku, pricePred).getFirst().targetId();
        assertThat(correctedKnowledge.findEntity(actualPriceId).orElseThrow().payload())
                .contains(Value.of(new BigDecimal("85.00")));
    }

    @Test
    void v15_commitsByCorrelationIdLinksMultiTransactionBusinessWorkflow() {
        VersionedGraphDatabase db = createDatabase();
        String workflowId = "ORDER-2026-991";

        EntityId orderType;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("sales"), Reason.of("create order"), workflowId)) {
            orderType = tx.defineObjectType("Order");
            tx.createObject(orderType);
            tx.commit();
        }
        try (GraphTransaction tx = db.beginTransaction(Actor.user("warehouse"), Reason.of("unrelated"), "OTHER-1")) {
            tx.createObject(orderType);
            tx.commit();
        }
        try (GraphTransaction tx = db.beginTransaction(Actor.user("billing"), Reason.of("invoice order"), workflowId)) {
            tx.createObject(orderType);
            tx.commit();
        }

        List<Commit> correlated = db.commitsByCorrelationId(workflowId);
        assertThat(correlated).hasSize(2);
        assertThat(correlated).extracting(c -> c.actor().id()).containsExactly("sales", "billing");
    }

    @Test
    void v16_cherryPickAppliesIsolatedCommitToTargetBranchWithoutBringingEarlierBranchCommits() {
        VersionedGraphDatabase db = createDatabase();
        EntityId taskType;
        EntityId titleType;
        EntityId titlePred;
        CommitId base;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("admin"), Reason.of("base schema"))) {
            taskType = tx.defineObjectType("Task");
            titleType = tx.defineValueType("TaskTitle", ValueType.STRING);
            titlePred = tx.definePredicate("taskTitle", taskType, titleType);
            base = tx.commit();
        }

        db.createBranch("feature/work", base);
        EntityId featureOnlyTask;
        EntityId hotfixTask;
        CommitId cHotfix;

        // First commit on feature should NOT be copied when cherry-picking second commit
        try (GraphTransaction tx = db.beginTransaction("feature/work", Actor.user("dev"), Reason.of("wip feature"))) {
            featureOnlyTask = tx.createObject(taskType);
            tx.connect(featureOnlyTask, titlePred, tx.createValue(titleType, Value.of("WIP Feature")));
            tx.commit();
        }
        try (GraphTransaction tx = db.beginTransaction("feature/work", Actor.user("dev"), Reason.of("urgent hotfix"))) {
            hotfixTask = tx.createObject(taskType);
            tx.connect(hotfixTask, titlePred, tx.createValue(titleType, Value.of("Hotfix Patch")));
            cHotfix = tx.commit();
        }

        MergeResult cherry = db.cherryPick(cHotfix, "main", Actor.user("release"), Reason.of("cherry-pick hotfix"));
        assertThat(cherry.succeeded()).isTrue();
        assertThat(cherry.mergeCommitId()).isPresent();

        GraphReader mainSnap = db.branchSnapshot("main");
        assertThat(mainSnap.findEntity(hotfixTask)).isPresent();
        assertThat(mainSnap.findEntity(featureOnlyTask)).isEmpty();
        assertThat(db.getCommit(cherry.mergeCommitId().get()).parentIds()).containsExactly(base);
    }

    @Test
    void v16_cherryPickDetectsConflictCleanlyWithoutMutatingTargetBranch() {
        VersionedGraphDatabase db = createDatabase();
        EntityId empType;
        EntityId badgeType;
        EntityId badgePred;
        EntityId alice;
        CommitId base;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("admin"), Reason.of("base"))) {
            empType = tx.defineObjectType("Emp");
            badgeType = tx.defineValueType("Badge", ValueType.STRING);
            badgePred = tx.definePredicate("hasBadge", empType, badgeType, PredicateConstraints.manyToOne());
            alice = tx.createObject(empType);
            base = tx.commit();
        }

        CommitId mainHeadBefore;
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("m"), Reason.of("main badge"))) {
            tx.connect(alice, badgePred, tx.createValue(badgeType, Value.of("MAIN-01")));
            mainHeadBefore = tx.commit();
        }

        db.createBranch("feature/badge", base);
        CommitId featureBadgeCommit;
        try (GraphTransaction tx = db.beginTransaction("feature/badge", Actor.user("f"), Reason.of("feature badge"))) {
            tx.connect(alice, badgePred, tx.createValue(badgeType, Value.of("FEAT-99")));
            featureBadgeCommit = tx.commit();
        }

        MergeResult conflict = db.cherryPick(featureBadgeCommit, "main", Actor.user("rel"), Reason.of("try cherry"));
        assertThat(conflict.succeeded()).isFalse();
        assertThat(conflict.conflicts()).isNotEmpty();
        assertThat(db.branchHead("main")).isEqualTo(mainHeadBefore);
    }

    @Test
    void p05_automaticTypeIndexAndAclCacheAccelerateQueriesWithIdenticalSemantics() {
        VersionedGraphDatabase db = createDatabase();
        EntityId customerType;
        EntityId deptType;
        EntityId nameType;
        EntityId namePred;
        EntityId secretEntity;
        EntityId publicEntity;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("admin"), Reason.of("index & acl seed"))) {
            customerType = tx.defineObjectType("Customer");
            deptType = tx.defineObjectType("Department");
            nameType = tx.defineValueType("Label", ValueType.STRING);
            namePred = tx.definePredicate("label", customerType, nameType);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, customerType);

            publicEntity = tx.createObject(customerType);
            tx.connect(publicEntity, namePred, tx.createValue(nameType, Value.of("Public Corp")));
            secretEntity = tx.createObject(customerType);
            tx.connect(secretEntity, namePred, tx.createValue(nameType, Value.of("Secret Corp")));
            tx.denyPermission(alice, Permission.READ, secretEntity);

            for (int i = 0; i < 40; i++) {
                tx.createObject(deptType);
            }
            tx.commit();
        }

        GraphReader raw = db.currentSnapshot();
        assertThat(raw.findEntitiesByType(customerType)).extracting(EntityNode::id)
                .containsExactly(publicEntity, secretEntity);
        assertThat(raw.findEntitiesByType(deptType)).hasSize(40);

        // Authorized reader & repeated ACL evaluation benefit from memoization while enforcing Deny Wins
        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot("alice");
        assertThat(aliceReader.findEntitiesByType(customerType)).extracting(EntityNode::id)
                .containsExactly(publicEntity);
        assertThat(raw.evaluateAccess("alice", Permission.READ, publicEntity).allowed()).isTrue();
        assertThat(raw.evaluateAccess("alice", Permission.READ, publicEntity).allowed()).isTrue();
        assertThat(raw.evaluateAccess("alice", Permission.READ, secretEntity).allowed()).isFalse();
    }

    @Test
    void mvcc_optimisticConflictThrowsTypedExceptionAndExecuteWithRetrySucceeds() {
        VersionedGraphDatabase db = createDatabase();
        EntityId itemType;
        EntityId nameType;
        EntityId namePred;
        CommitId c1;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("admin"), Reason.of("init"))) {
            itemType = tx.defineObjectType("Item");
            nameType = tx.defineValueType("ItemName", ValueType.STRING);
            namePred = tx.definePredicate("itemName", itemType, nameType);
            c1 = tx.commit();
        }

        GraphTransaction staleTx = db.beginTransaction("main", Actor.user("u1"), Reason.of("stale"));
        EntityId staleItem = staleTx.createObject(itemType);
        staleTx.connect(staleItem, namePred, staleTx.createValue(nameType, Value.of("Stale")));

        CommitId c2;
        try (GraphTransaction concurrentTx = db.beginTransaction("main", Actor.user("u2"), Reason.of("winner"))) {
            EntityId win = concurrentTx.createObject(itemType);
            concurrentTx.connect(win, namePred, concurrentTx.createValue(nameType, Value.of("Winner")));
            c2 = concurrentTx.commit();
        }

        assertThatThrownBy(staleTx::commit)
                .isInstanceOf(OptimisticCommitConflictException.class)
                .satisfies(ex -> {
                    OptimisticCommitConflictException conflict = (OptimisticCommitConflictException) ex;
                    assertThat(conflict.branchName()).isEqualTo("main");
                    assertThat(conflict.expectedBaseCommitId()).isEqualTo(c1);
                    assertThat(conflict.actualHeadCommitId()).isEqualTo(c2);
                });
        staleTx.close();

        // Now executeWithRetry injects a conflict on the first attempt and succeeds cleanly on the second attempt
        AtomicInteger attempts = new AtomicInteger(0);
        CommitId retriedCommit = db.executeWithRetry("main", Actor.user("u1"), Reason.of("retried"), "RETRY-1", 3, tx -> {
            if (attempts.incrementAndGet() == 1) {
                // Concurrent writer advances branch HEAD mid-transaction to trigger OptimisticCommitConflictException
                try (GraphTransaction interrupter = db.beginTransaction("main", Actor.user("u3"), Reason.of("race"))) {
                    interrupter.createObject(itemType);
                    interrupter.commit();
                }
            }
            EntityId created = tx.createObject(itemType);
            tx.connect(created, namePred, tx.createValue(nameType, Value.of("Recovered Item")));
        });

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(db.getCommit(retriedCommit).reason().text()).isEqualTo("retried");
        assertThat(db.currentSnapshot().findEntitiesByType(itemType)).hasSize(3);
    }

    @Test
    void mvcc_executeIdempotentDeduplicatesRepeatedRetriesWithSameIdempotencyKey() {
        VersionedGraphDatabase db = createDatabase();
        EntityId noteType;
        try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("schema"))) {
            noteType = tx.defineObjectType("Note");
            tx.commit();
        }

        AtomicInteger sideEffectCount = new AtomicInteger(0);
        CommitId first = db.executeIdempotent(
                "main", "req-abc-123", Actor.user("api"), Reason.of("idempotent create"), "API-1", tx -> {
                    sideEffectCount.incrementAndGet();
                    tx.createObject(noteType);
                });
        CommitId second = db.executeIdempotent(
                "main", "req-abc-123", Actor.user("api"), Reason.of("idempotent create"), "API-1", tx -> {
                    sideEffectCount.incrementAndGet();
                    tx.createObject(noteType);
                });

        assertThat(second).isEqualTo(first);
        assertThat(sideEffectCount.get()).isEqualTo(1);
        assertThat(db.currentSnapshot().findEntitiesByType(noteType)).hasSize(1);
    }

    @Test
    void mvcc_concurrentReadersAndWritersMaintainSnapshotIsolationWithoutDeadlock() throws Exception {
        VersionedGraphDatabase db = createDatabase();
        EntityId eventType;
        try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("schema"))) {
            eventType = tx.defineObjectType("Event");
            tx.commit();
        }

        int writerThreads = 4;
        int commitsPerWriter = 5;
        int readerThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<CommitId> publishedCommits = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int w = 0; w < writerThreads; w++) {
                final int writerIdx = w;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int k = 0; k < commitsPerWriter; k++) {
                        CommitId id = db.executeWithRetry(
                                "main",
                                Actor.user("writer-" + writerIdx),
                                Reason.of("w" + writerIdx + "-c" + k),
                                "CORR-W" + writerIdx,
                                16,
                                tx -> tx.createObject(eventType)
                        );
                        publishedCommits.add(id);
                    }
                    return null;
                }));
            }

            for (int r = 0; r < readerThreads; r++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < 30; i++) {
                        GraphReader snap = db.currentSnapshot();
                        long viaIndex = snap.findEntitiesByType(eventType).size();
                        assertThat(viaIndex).isGreaterThanOrEqualTo(0L);
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(publishedCommits).hasSize(writerThreads * commitsPerWriter);
        assertThat(db.currentSnapshot().findEntitiesByType(eventType)).hasSize(writerThreads * commitsPerWriter);
    }

    @Test
    void s09_redactEntityPayloadPurgesSensitiveValueAcrossHistoryWhilePreservingGraphIntegrityAndReceipt() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        EntityId emailType;
        EntityId hasEmail;
        EntityId alice;
        EntityId sensitiveEmail;
        CommitId c1;
        CommitId c2;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("hr"), Reason.of("onboard alice"))) {
            personType = tx.defineObjectType("Person");
            emailType = tx.defineValueType("Email", ValueType.STRING);
            hasEmail = tx.definePredicate("hasEmail", personType, emailType);
            alice = tx.createObject(personType);
            sensitiveEmail = tx.createValue(emailType, Value.of("alice.secret@example.com"));
            tx.connect(alice, hasEmail, sensitiveEmail);
            c1 = tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction(Actor.user("hr"), Reason.of("follow-up commit"))) {
            tx.createObject(personType);
            c2 = tx.commit();
        }

        RedactionReceipt receipt = db.redactEntityPayload(
                sensitiveEmail, Actor.user("dpo"), Reason.of("GDPR Art. 17 erasure"));
        assertThat(db.listRedactionReceipts()).hasSize(1);
        assertThat(receipt.redactedEntityId()).isEqualTo(sensitiveEmail);
        assertThat(receipt.originalPayloadSha256()).hasSize(64);
        assertThat(receipt.affectedCommitCount()).isEqualTo(1);

        // Neither historical c1 nor c2 nor replayAsOf retains the original PII payload, while EntityId/Edge remain intact
        for (CommitId cid : List.of(c1, c2)) {
            assertThat(db.asOf(cid).findEntity(sensitiveEmail).orElseThrow().payload())
                    .contains(Value.of("[REDACTED]"));
            assertThat(db.asOf(cid).findOutgoingEdges(alice, hasEmail)).hasSize(1);
            assertThat(db.replayAsOf(cid).findEntity(sensitiveEmail).orElseThrow().payload())
                    .contains(Value.of("[REDACTED]"));
        }

        // Schema metadata values cannot be redacted
        EntityId personNameValueId = db.currentSnapshot().findOutgoingEdges(personType).getFirst().targetId();
        assertThatThrownBy(() -> db.redactEntityPayload(personNameValueId, Actor.user("dpo"), Reason.of("bad")))
                .isInstanceOf(SchemaViolationException.class);
    }
}
