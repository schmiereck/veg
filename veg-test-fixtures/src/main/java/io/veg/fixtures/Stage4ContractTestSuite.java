package io.veg.fixtures;

import io.veg.engine.BranchNotFoundException;
import io.veg.engine.DuplicateBranchOrTagException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.MissingDependencyException;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.Branch;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.EntityId;
import io.veg.model.MergeConflictType;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.Tag;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 4 contract tests covering branches, tags, structural sharing (T16),
 * selective staging (V-06), three-way merges and conflict classes (H5, T10–T12), and
 * operational vs. reference data policies (V-13).
 */
public abstract class Stage4ContractTestSuite {

    protected abstract VersionedGraphDatabase createDatabase();

    @Test
    void defaultBranchIsMainAndPointsToBootstrapCommit() {
        VersionedGraphDatabase db = createDatabase();

        assertThat(db.currentBranch()).isEqualTo(VersionedGraphDatabase.DEFAULT_BRANCH);
        assertThat(db.findBranch("main")).contains(new Branch("main", CommitId.BOOTSTRAP));
        assertThat(db.listBranches()).containsExactly(new Branch("main", CommitId.BOOTSTRAP));
        assertThat(db.branchHead("main")).isEqualTo(CommitId.BOOTSTRAP);
    }

    @Test
    void createBranchAndIsolateBranchLocalCommitsFromMain() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        CommitId mainC1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base schema"))) {
            personType = tx.defineObjectType("Person");
            mainC1 = tx.commit();
        }

        Branch feature = db.createBranch("feature/customer", mainC1);
        assertThat(feature.headCommitId()).isEqualTo(mainC1);

        EntityId featurePerson;
        CommitId featureC2;
        try (GraphTransaction tx = db.beginTransaction("feature/customer", Actor.user("u2"), Reason.of("Feature work"))) {
            featurePerson = tx.createObject(personType);
            featureC2 = tx.commit();
        }

        assertThat(db.branchHead("main")).isEqualTo(mainC1);
        assertThat(db.branchHead("feature/customer")).isEqualTo(featureC2);
        assertThat(db.branchSnapshot("main").findEntity(featurePerson)).isEmpty();
        assertThat(db.branchSnapshot("feature/customer").findEntity(featurePerson)).isPresent();
    }

    @Test
    void checkoutBranchSwitchesDefaultBranchForTransactions() {
        VersionedGraphDatabase db = createDatabase();
        db.createBranch("feature/a");
        db.checkoutBranch("feature/a");
        assertThat(db.currentBranch()).isEqualTo("feature/a");

        EntityId typeId;
        CommitId featureC1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("On checked-out branch"))) {
            typeId = tx.defineObjectType("Product");
            featureC1 = tx.commit();
        }

        assertThat(db.headCommitId()).isEqualTo(featureC1);
        assertThat(db.branchHead("main")).isEqualTo(CommitId.BOOTSTRAP);
        assertThat(db.branchSnapshot("main").findEntity(typeId)).isEmpty();
        assertThat(db.currentSnapshot().findEntity(typeId)).isPresent();

        assertThatThrownBy(() -> db.checkoutBranch("nonexistent")).isInstanceOf(BranchNotFoundException.class);
    }

    @Test
    void createTagAndReadHistoricalSnapshotAsOfTag() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        EntityId personType;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Release 1.0"))) {
            personType = tx.defineObjectType("Person");
            c1 = tx.commit();
        }
        Tag v1 = db.createTag("release/v1.0", c1);
        assertThat(db.findTag("release/v1.0")).contains(v1);
        assertThat(db.listTags()).containsExactly(v1);

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Post-release change"))) {
            tx.renameSchemaElement(personType, "Individual");
            tx.commit();
        }

        assertThat(db.asOfTag("release/v1.0").resolveName(personType)).contains("Person");
        assertThat(db.currentSnapshot().resolveName(personType)).contains("Individual");
    }

    @Test
    void duplicateBranchOrTagNameIsRejected() {
        VersionedGraphDatabase db = createDatabase();
        db.createBranch("release/1");
        db.createTag("v1", CommitId.BOOTSTRAP);

        assertThatThrownBy(() -> db.createBranch("release/1")).isInstanceOf(DuplicateBranchOrTagException.class);
        assertThatThrownBy(() -> db.createBranch("main")).isInstanceOf(DuplicateBranchOrTagException.class);
        assertThatThrownBy(() -> db.createTag("v1", CommitId.BOOTSTRAP)).isInstanceOf(DuplicateBranchOrTagException.class);
    }

    @Test
    void t16CreatingManyBranchesOnPopulatedGraphDoesNotCopyEntitiesOrEdges() {
        VersionedGraphDatabase db = createDatabase();
        CommitId seedCommit;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("seed"), Reason.of("Populate 500 persons"))) {
            new DeterministicGraphGenerator().generate(tx, 42L, 500, 0.5, 0.25);
            seedCommit = tx.commit();
        }

        GraphReader baseSnapshot = db.asOf(seedCommit);
        long baseEntities = baseSnapshot.entityCount();
        long baseEdges = baseSnapshot.edgeCount();

        for (int i = 0; i < 500; i++) {
            db.createBranch("feature/bulk-" + i, seedCommit);
        }

        assertThat(db.listBranches()).hasSize(501);
        // All branches structurally share the exact same snapshot instance without duplicating entities/edges
        assertThat(db.branchSnapshot("feature/bulk-0")).isSameAs(baseSnapshot);
        assertThat(db.branchSnapshot("feature/bulk-499")).isSameAs(baseSnapshot);
        assertThat(db.branchSnapshot("feature/bulk-499").entityCount()).isEqualTo(baseEntities);
        assertThat(db.branchSnapshot("feature/bulk-499").edgeCount()).isEqualTo(baseEdges);
    }

    @Test
    void selectiveStagingCommitsIndependentChangesSeparately() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Schema"))) {
            personType = tx.defineObjectType("Person");
            tx.commit();
        }

        StagingWorkspace ws = db.createStagingWorkspace("main");
        EntityId p1 = ws.createObject(personType);
        EntityId p2 = ws.createObject(personType);

        ws.stageEntity(p1);
        assertThat(ws.stagedEntityIds()).containsExactly(p1);
        assertThat(ws.stagedChanges()).hasSize(1);
        assertThat(ws.unstagedChanges()).hasSize(1);

        CommitId c2 = ws.commitStaged(Actor.user("u1"), Reason.of("Commit only p1"));
        assertThat(db.asOf(c2).findEntity(p1)).isPresent();
        assertThat(db.asOf(c2).findEntity(p2)).isEmpty();
        assertThat(ws.stagedChanges()).isEmpty();
        assertThat(ws.unstagedChanges()).hasSize(1);

        ws.stageEntity(p2);
        CommitId c3 = ws.commitStaged(Actor.user("u1"), Reason.of("Commit remaining p2"));
        assertThat(db.asOf(c3).findEntity(p1)).isPresent();
        assertThat(db.asOf(c3).findEntity(p2)).isPresent();
        assertThat(ws.unstagedChanges()).isEmpty();
    }

    @Test
    void selectiveStagingRejectsEdgeWithoutStagedEndpointEntities() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        EntityId nameType;
        EntityId hasName;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Schema"))) {
            personType = tx.defineObjectType("Person");
            nameType = tx.defineValueType("Name", ValueType.STRING);
            hasName = tx.definePredicate("hasName", personType, nameType);
            tx.commit();
        }

        StagingWorkspace ws = db.createStagingWorkspace("main");
        EntityId person = ws.createObject(personType);
        EntityId nameVal = ws.createValue(nameType, Value.of("Thomas"));
        EntityId edge = ws.connect(person, hasName, nameVal);

        // Staging only the edge without staging person/nameVal must fail atomically
        ws.stageEntity(edge);
        CommitId headBefore = db.headCommitId();
        assertThatThrownBy(() -> ws.commitStaged(Actor.user("u1"), Reason.of("Invalid partial edge")))
                .isInstanceOf(MissingDependencyException.class);
        assertThat(db.headCommitId()).isEqualTo(headBefore);

        // Once endpoints are staged too, commit succeeds
        ws.stageEntity(person);
        ws.stageEntity(nameVal);
        CommitId c2 = ws.commitStaged(Actor.user("u1"), Reason.of("Complete unit"));
        assertThat(db.asOf(c2).findEdge(edge)).isPresent();
    }

    @Test
    void selectiveStagingRejectsObjectWithoutStagedTypeDefinition() {
        VersionedGraphDatabase db = createDatabase();
        StagingWorkspace ws = db.createStagingWorkspace("main");

        EntityId orderType = ws.defineObjectType("Order");
        EntityId order = ws.createObject(orderType);

        ws.stageEntity(order);
        assertThatThrownBy(() -> ws.commitStaged(Actor.user("u1"), Reason.of("Missing Order type")))
                .isInstanceOf(MissingDependencyException.class);

        ws.stageEntity(orderType);
        CommitId c1 = ws.commitStaged(Actor.user("u1"), Reason.of("Type + instance"));
        assertThat(db.asOf(c1).findTypeByName("Order")).contains(orderType);
        assertThat(db.asOf(c1).findEntity(order)).isPresent();
    }

    @Test
    void selectiveStagingUnstageRestoresChangeToUnstagedPool() {
        VersionedGraphDatabase db = createDatabase();
        StagingWorkspace ws = db.createStagingWorkspace("main");
        EntityId t1 = ws.defineObjectType("TypeA");
        EntityId t2 = ws.defineObjectType("TypeB");

        ws.stageAll();
        assertThat(ws.unstagedChanges()).isEmpty();
        ws.unstageEntity(t2);
        assertThat(ws.stagedEntityIds()).containsExactly(t1);
        assertThat(ws.unstagedChanges()).isNotEmpty();

        ws.unstageAll();
        assertThat(ws.stagedEntityIds()).isEmpty();
        assertThat(ws.stagedChanges()).isEmpty();
    }

    @Test
    void t10ThreeWayMergeAutomaticallyCombinesIndependentSchemaAndDataChanges() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("init"), Reason.of("Common ancestor"))) {
            personType = tx.defineObjectType("Person");
            base = tx.commit();
        }

        db.createBranch("feature/a", base);
        db.createBranch("feature/b", base);

        EntityId emailType;
        EntityId hasEmail;
        EntityId alice;
        EntityId aliceEmail;
        try (GraphTransaction tx = db.beginTransaction("feature/a", Actor.user("devA"), Reason.of("Add email schema + Alice"))) {
            emailType = tx.defineValueType("Email", ValueType.STRING, true);
            hasEmail = tx.definePredicate("hasEmail", personType, emailType, PredicateConstraints.manyToOne());
            alice = tx.createObject(personType);
            aliceEmail = tx.createValue(emailType, Value.of("alice@veg.io"));
            tx.connect(alice, hasEmail, aliceEmail);
            tx.commit();
        }

        EntityId phoneType;
        EntityId hasPhone;
        EntityId bob;
        EntityId bobPhone;
        try (GraphTransaction tx = db.beginTransaction("feature/b", Actor.user("devB"), Reason.of("Add phone schema + Bob"))) {
            phoneType = tx.defineValueType("Phone", ValueType.STRING);
            hasPhone = tx.definePredicate("hasPhone", personType, phoneType);
            bob = tx.createObject(personType);
            bobPhone = tx.createValue(phoneType, Value.of("+49-123-456"));
            tx.connect(bob, hasPhone, bobPhone);
            tx.commit();
        }

        // First merge feature/a -> main, then 3-way merge feature/b -> main
        MergeResult m1 = db.merge("feature/a", "main", Actor.user("merger"), Reason.of("Merge feature/a"));
        assertThat(m1.succeeded()).isTrue();

        MergeResult m2 = db.merge("feature/b", "main", Actor.user("merger"), Reason.of("Merge feature/b"));
        assertThat(m2.succeeded()).isTrue();
        assertThat(m2.baseCommitId()).isEqualTo(base);

        GraphReader main = db.branchSnapshot("main");
        assertThat(main.findTypeByName("Email")).contains(emailType);
        assertThat(main.findTypeByName("Phone")).contains(phoneType);
        assertThat(main.findPredicateByName("hasEmail")).contains(hasEmail);
        assertThat(main.findPredicateByName("hasPhone")).contains(hasPhone);
        assertThat(main.findEntity(alice)).isPresent();
        assertThat(main.findEntity(bob)).isPresent();
        assertThat(main.findOutgoingEdges(alice, hasEmail)).hasSize(1);
        assertThat(main.findOutgoingEdges(bob, hasPhone)).hasSize(1);

        // Replay equivalence holds even across 2-parent merge commits!
        CommitId mergeId = m2.mergeCommitId().orElseThrow();
        GraphReader replayed = db.replayAsOf(mergeId);
        assertThat(replayed.allEntities()).isEqualTo(main.allEntities());
        assertThat(replayed.allEdges()).isEqualTo(main.allEdges());
    }

    @Test
    void t10ThreeWayMergeRecordsTwoParentIdsAndFindsCommonAncestorInDag() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("c1"))) {
            tx.defineObjectType("Person");
            c1 = tx.commit();
        }
        db.createBranch("feature/x", c1);

        CommitId c2Main;
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("u1"), Reason.of("main c2"))) {
            tx.defineObjectType("Order");
            c2Main = tx.commit();
        }

        CommitId c3Feature;
        try (GraphTransaction tx = db.beginTransaction("feature/x", Actor.user("u2"), Reason.of("feature c3"))) {
            tx.defineObjectType("Invoice");
            c3Feature = tx.commit();
        }

        assertThat(db.findCommonAncestor(c2Main, c3Feature)).isEqualTo(c1);

        MergeResult merged = db.merge("feature/x", "main", Actor.user("merger"), Reason.of("Merge x"));
        assertThat(merged.succeeded()).isTrue();
        Commit mergeCommit = db.getCommit(merged.mergeCommitId().orElseThrow());
        assertThat(mergeCommit.isMergeCommit()).isTrue();
        assertThat(mergeCommit.parentIds()).containsExactly(c2Main, c3Feature);
    }

    @Test
    void t11ConflictOnSharedMutableValueModifiedInBothBranchesIsDetectedExplicitly() {
        VersionedGraphDatabase db = createDatabase();
        EntityId sharedCompany;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Shared value"))) {
            EntityId companyType = tx.defineValueType("CompanyName", ValueType.STRING);
            sharedCompany = tx.createValue(companyType, Value.of("Acme GmbH"), ValueMutability.SHARED_MUTABLE);
            base = tx.commit();
        }

        db.createBranch("feature/rename-a", base);
        try (GraphTransaction tx = db.beginTransaction("feature/rename-a", Actor.user("devA"), Reason.of("Update to AG"))) {
            tx.updateValue(sharedCompany, Value.of("Acme AG"));
            tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("devB"), Reason.of("Update to SE"))) {
            tx.updateValue(sharedCompany, Value.of("Acme SE"));
            tx.commit();
        }

        MergeResult result = db.merge("feature/rename-a", "main", Actor.user("merger"), Reason.of("Attempt merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.mergeCommitId()).isEmpty();
        assertThat(result.conflicts())
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(MergeConflictType.SHARED_VALUE_MODIFIED);
                    assertThat(c.subjectId()).isEqualTo(sharedCompany);
                });
        // No silent overwrite on main
        assertThat(db.branchSnapshot("main").findEntity(sharedCompany).orElseThrow().payload())
                .contains(Value.of("Acme SE"));
    }

    @Test
    void t11ConflictOnSharedMutableValueModifiedInOneBranchAndReferencedInOtherIsDetected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        EntityId worksAt;
        EntityId sharedCompany;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Shared mutable setup"))) {
            personType = tx.defineObjectType("Person");
            EntityId companyType = tx.defineValueType("CompanyName", ValueType.STRING);
            worksAt = tx.definePredicate("worksAt", personType, companyType);
            sharedCompany = tx.createValue(companyType, Value.of("Acme GmbH"), ValueMutability.SHARED_MUTABLE);
            base = tx.commit();
        }

        db.createBranch("feature/link-new-person", base);
        try (GraphTransaction tx = db.beginTransaction("feature/link-new-person", Actor.user("devA"), Reason.of("Link Bob to Acme GmbH"))) {
            EntityId bob = tx.createObject(personType);
            tx.connect(bob, worksAt, sharedCompany);
            tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("devB"), Reason.of("Mutate shared value"))) {
            tx.updateValue(sharedCompany, Value.of("Legacy Corp"));
            tx.commit();
        }

        MergeResult result = db.merge("feature/link-new-person", "main", Actor.user("merger"), Reason.of("Merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.conflicts())
                .extracting(c -> c.type())
                .contains(MergeConflictType.SHARED_VALUE_MODIFIED);
    }

    @Test
    void t12ConflictBetweenTightenedCardinalityConstraintAndNewDataInOtherBranchIsDetected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        EntityId emailType;
        EntityId hasEmail;
        EntityId alice;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Unconstrained hasEmail"))) {
            personType = tx.defineObjectType("Person");
            emailType = tx.defineValueType("Email", ValueType.STRING);
            hasEmail = tx.definePredicate("hasEmail", personType, emailType, PredicateConstraints.unconstrained());
            alice = tx.createObject(personType);
            tx.connect(alice, hasEmail, tx.createValue(emailType, Value.of("alice1@veg.io")));
            base = tx.commit();
        }

        db.createBranch("feature/second-email", base);
        // Feature adds a 2nd email for Alice (valid under base schema)
        try (GraphTransaction tx = db.beginTransaction("feature/second-email", Actor.user("dev"), Reason.of("Add 2nd email"))) {
            tx.connect(alice, hasEmail, tx.createValue(emailType, Value.of("alice2@veg.io")));
            tx.commit();
        }

        // Main tightens hasEmail to manyToOne (maxOutgoing=1) (valid on main alone)
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("dba"), Reason.of("Tighten to 1 email"))) {
            tx.updatePredicateConstraints(hasEmail, PredicateConstraints.manyToOne());
            tx.commit();
        }

        MergeResult result = db.merge("feature/second-email", "main", Actor.user("merger"), Reason.of("Merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.conflicts())
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE);
                    assertThat(c.subjectId()).isEqualTo(hasEmail);
                });
        assertThat(db.branchSnapshot("main").findOutgoingEdges(alice, hasEmail)).hasSize(1);
    }

    @Test
    void t12ConflictBetweenEnabledUniqueValuesAndDuplicateValueInOtherBranchIsDetected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId emailType;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Non-unique Email"))) {
            emailType = tx.defineValueType("Email", ValueType.STRING, false);
            tx.createValue(emailType, Value.of("team@veg.io"));
            base = tx.commit();
        }

        db.createBranch("feature/dup", base);
        try (GraphTransaction tx = db.beginTransaction("feature/dup", Actor.user("dev"), Reason.of("Add duplicate email"))) {
            tx.createValue(emailType, Value.of("team@veg.io"));
            tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("dba"), Reason.of("Enable unique emails"))) {
            tx.enableUniqueValues(emailType);
            tx.commit();
        }

        MergeResult result = db.merge("feature/dup", "main", Actor.user("merger"), Reason.of("Merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.conflicts())
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE);
                    assertThat(c.subjectId()).isEqualTo(emailType);
                });
    }

    @Test
    void conflictOnConcurrentSchemaRenameOfSameTypeToDifferentNamesIsDetected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base"))) {
            personType = tx.defineObjectType("Person");
            base = tx.commit();
        }

        db.createBranch("feature/rename", base);
        try (GraphTransaction tx = db.beginTransaction("feature/rename", Actor.user("devA"), Reason.of("Rename to Contact"))) {
            tx.renameSchemaElement(personType, "Contact");
            tx.commit();
        }
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("devB"), Reason.of("Rename to Customer"))) {
            tx.renameSchemaElement(personType, "Customer");
            tx.commit();
        }

        MergeResult result = db.merge("feature/rename", "main", Actor.user("merger"), Reason.of("Merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.conflicts())
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(MergeConflictType.CONCURRENT_SCHEMA_RENAME);
                    assertThat(c.subjectId()).isEqualTo(personType);
                });
    }

    @Test
    void conflictOnEdgeDeletedInOneBranchAndAnnotatedInOtherBranchIsDetected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId docType;
        EntityId supportedBy;
        EntityId statementEdge;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base edge"))) {
            EntityId pType = tx.defineObjectType("Person");
            docType = tx.defineObjectType("SourceDocument");
            EntityId fnType = tx.defineValueType("FirstName", ValueType.STRING);
            EntityId hasFirstName = tx.definePredicate("hasFirstName", pType, fnType);
            supportedBy = tx.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);
            EntityId p = tx.createObject(pType);
            EntityId v = tx.createValue(fnType, Value.of("Thomas"));
            statementEdge = tx.connect(p, hasFirstName, v);
            base = tx.commit();
        }

        db.createBranch("feature/annotate", base);
        try (GraphTransaction tx = db.beginTransaction("feature/annotate", Actor.user("devA"), Reason.of("Annotate edge"))) {
            EntityId doc = tx.createObject(docType);
            tx.connect(statementEdge, supportedBy, doc);
            tx.commit();
        }
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("devB"), Reason.of("Delete edge"))) {
            tx.disconnect(statementEdge);
            tx.commit();
        }

        MergeResult result = db.merge("feature/annotate", "main", Actor.user("merger"), Reason.of("Merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.conflicts())
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(MergeConflictType.EDGE_DELETED_AND_ANNOTATED);
                    assertThat(c.subjectId()).isEqualTo(statementEdge);
                });
    }

    @Test
    void conflictOnConcurrentFunctionalAttributeAssignmentIsDetected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId person;
        EntityId nameType;
        EntityId hasName;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base person"))) {
            EntityId pType = tx.defineObjectType("Person");
            nameType = tx.defineValueType("Name", ValueType.STRING);
            hasName = tx.definePredicate("hasName", pType, nameType, PredicateConstraints.manyToOne());
            person = tx.createObject(pType);
            base = tx.commit();
        }

        db.createBranch("feature/name", base);
        try (GraphTransaction tx = db.beginTransaction("feature/name", Actor.user("devA"), Reason.of("Name A"))) {
            tx.connect(person, hasName, tx.createValue(nameType, Value.of("Alice")));
            tx.commit();
        }
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("devB"), Reason.of("Name B"))) {
            tx.connect(person, hasName, tx.createValue(nameType, Value.of("Alicia")));
            tx.commit();
        }

        MergeResult result = db.merge("feature/name", "main", Actor.user("merger"), Reason.of("Merge"));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.conflicts())
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(MergeConflictType.CONCURRENT_FUNCTIONAL_ATTRIBUTE);
                    assertThat(c.subjectId()).isEqualTo(person);
                });
    }

    @Test
    void explicitMergeResolutionCreatesValidTwoParentMergeCommit() {
        VersionedGraphDatabase db = createDatabase();
        EntityId sharedCompany;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base"))) {
            EntityId companyType = tx.defineValueType("Company", ValueType.STRING);
            sharedCompany = tx.createValue(companyType, Value.of("Acme GmbH"), ValueMutability.SHARED_MUTABLE);
            base = tx.commit();
        }

        db.createBranch("feature/upd", base);
        CommitId sourceHead;
        try (GraphTransaction tx = db.beginTransaction("feature/upd", Actor.user("devA"), Reason.of("AG"))) {
            tx.updateValue(sharedCompany, Value.of("Acme AG"));
            sourceHead = tx.commit();
        }
        CommitId targetHead;
        try (GraphTransaction tx = db.beginTransaction("main", Actor.user("devB"), Reason.of("SE"))) {
            tx.updateValue(sharedCompany, Value.of("Acme SE"));
            targetHead = tx.commit();
        }

        MergeResult conflict = db.merge("feature/upd", "main", Actor.user("merger"), Reason.of("Auto merge"));
        assertThat(conflict.succeeded()).isFalse();

        CommitId resolvedId = db.resolveMerge("feature/upd", "main", Actor.user("lead"), Reason.of("Resolved to Acme SE & Co."),
                session -> session.updateValue(sharedCompany, Value.of("Acme SE & Co.")));

        Commit resolvedCommit = db.getCommit(resolvedId);
        assertThat(resolvedCommit.parentIds()).containsExactly(targetHead, sourceHead);
        assertThat(db.branchSnapshot("main").findEntity(sharedCompany).orElseThrow().payload())
                .contains(Value.of("Acme SE & Co."));
        assertThat(db.replayAsOf(resolvedId).allEntities())
                .isEqualTo(db.branchSnapshot("main").allEntities());
    }

    @Test
    void v13DevelopmentMergeExcludesOperationalAndTestDataCommitsWhileMergingSchemaAndReferenceData() {
        VersionedGraphDatabase db = createDatabase();
        EntityId customerType;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base"))) {
            customerType = tx.defineObjectType("Customer");
            base = tx.commit();
        }

        db.createBranch("feature/crm-release", base);
        EntityId countryType;
        EntityId germanyRef;
        // 1. Schema + Reference data commit
        try (GraphTransaction tx = db.beginTransaction(
                "feature/crm-release", Actor.user("dev"), Reason.of("Add Country reference"), null, DataCategory.REFERENCE)) {
            countryType = tx.defineObjectType("Country");
            germanyRef = tx.createObject(countryType);
            tx.commit();
        }

        // 2. Operational customer commit (must NOT be promoted to main by developmentDefault merge!)
        EntityId prodCustomer;
        try (GraphTransaction tx = db.beginTransaction(
                "feature/crm-release", Actor.user("crm-service"), Reason.of("Prod customer signup"), null, DataCategory.OPERATIONAL)) {
            prodCustomer = tx.createObject(customerType);
            tx.commit();
        }

        // 3. Test fixture commit (must NOT be promoted by developmentDefault merge!)
        EntityId testCustomer;
        try (GraphTransaction tx = db.beginTransaction(
                "feature/crm-release", Actor.user("qa"), Reason.of("Test fixture"), null, DataCategory.TEST)) {
            testCustomer = tx.createObject(customerType);
            tx.commit();
        }

        MergeResult result = db.merge("feature/crm-release", "main",
                Actor.user("release-mgr"), Reason.of("Promote schema & reference data"), MergePolicy.developmentDefault());
        assertThat(result.succeeded()).isTrue();

        GraphReader mainSnap = db.branchSnapshot("main");
        assertThat(mainSnap.findTypeByName("Country")).contains(countryType);
        assertThat(mainSnap.findEntity(germanyRef)).isPresent();
        assertThat(mainSnap.findEntity(prodCustomer)).isEmpty();
        assertThat(mainSnap.findEntity(testCustomer)).isEmpty();
    }

    @Test
    void v13MergeWithIncludeAllPolicyIncludesOperationalDataWhenExplicitlyRequested() {
        VersionedGraphDatabase db = createDatabase();
        EntityId customerType;
        CommitId base;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base"))) {
            customerType = tx.defineObjectType("Customer");
            base = tx.commit();
        }

        db.createBranch("feature/op", base);
        EntityId opCustomer;
        try (GraphTransaction tx = db.beginTransaction(
                "feature/op", Actor.user("svc"), Reason.of("Operational sync"), null, DataCategory.OPERATIONAL)) {
            opCustomer = tx.createObject(customerType);
            tx.commit();
        }

        MergeResult devResult = db.merge("feature/op", "main",
                Actor.user("mgr"), Reason.of("Include all"), MergePolicy.includeAll());
        assertThat(devResult.succeeded()).isTrue();
        assertThat(db.branchSnapshot("main").findEntity(opCustomer)).isPresent();
    }
}
