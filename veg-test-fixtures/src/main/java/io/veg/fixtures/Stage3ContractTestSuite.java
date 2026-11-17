package io.veg.fixtures;

import io.veg.engine.CardinalityViolationException;
import io.veg.engine.CommitNotFoundException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.RevertConflictException;
import io.veg.engine.TransactionStateException;
import io.veg.engine.UniquenessViolationException;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.GraphDiff;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.SavepointId;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 3 contract tests covering atomic transactions, savepoints,
 * immutable commit logs, {@code asOf}, {@code diff}, {@code revert}, schema evolution,
 * and 1000-mutation deterministic replay (V-01..V-04, V-09..V-11, M-07..M-08, T05..T09, T15).
 */
public abstract class Stage3ContractTestSuite {

    protected abstract VersionedGraphDatabase createDatabase();

    @Test
    void initialDatabaseHasBootstrapCommitZero() {
        VersionedGraphDatabase db = createDatabase();

        assertThat(db.headCommitId()).isEqualTo(CommitId.BOOTSTRAP);
        Commit c0 = db.getCommit(CommitId.BOOTSTRAP);
        assertThat(c0.parentId()).isEmpty();
        assertThat(c0.actor()).isEqualTo(Actor.system());
        assertThat(db.commitHistory()).containsExactly(c0);
        assertThat(db.currentSnapshot().findEntity(BootstrapIds.META_OBJECT_TYPE)).isPresent();
        assertThatThrownBy(() -> db.asOf(new CommitId(99L))).isInstanceOf(CommitNotFoundException.class);
    }

    @Test
    void commitPublishesChangesAndAdvancesHeadCommitId() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        EntityId personId;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Define Person"))) {
            EntityId personType = tx.defineObjectType("Person");
            personId = tx.createObject(personType);
            c1 = tx.commit();
            assertThat(tx.isOpen()).isFalse();
        }

        assertThat(c1).isEqualTo(new CommitId(1L));
        assertThat(db.headCommitId()).isEqualTo(c1);
        assertThat(db.currentSnapshot().findEntity(personId)).isPresent();
        assertThat(db.asOf(CommitId.BOOTSTRAP).findEntity(personId)).isEmpty();
    }

    @Test
    void commitMetadataPreservesActorReasonCorrelationIdAndLinearParentChain() {
        VersionedGraphDatabase db = createDatabase();

        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.of("u123", "Alice"), Reason.of("Schema init"), "JIRA-10")) {
            tx.defineObjectType("Person");
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.of("u456", "Bob"), Reason.of("Add customer"))) {
            EntityId personType = tx.findTypeByName("Person").orElseThrow();
            tx.createObject(personType);
            c2 = tx.commit();
        }

        Commit commit1 = db.getCommit(c1);
        Commit commit2 = db.getCommit(c2);

        assertThat(commit1.parentId()).contains(CommitId.BOOTSTRAP);
        assertThat(commit1.actor()).isEqualTo(Actor.of("u123", "Alice"));
        assertThat(commit1.reason()).isEqualTo(Reason.of("Schema init"));
        assertThat(commit1.correlationId()).contains("JIRA-10");

        assertThat(commit2.parentId()).contains(c1);
        assertThat(commit2.actor()).isEqualTo(Actor.of("u456", "Bob"));
        assertThat(commit2.correlationId()).isEmpty();
        assertThat(db.commitHistory()).hasSize(3);
    }

    @Test
    void uncommittedTransactionChangesAreInvisibleToCurrentSnapshot() {
        VersionedGraphDatabase db = createDatabase();
        long baselineEntities = db.currentSnapshot().entityCount();

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Uncommitted"))) {
            EntityId t = tx.defineObjectType("TransientType");
            EntityId obj = tx.createObject(t);

            assertThat(tx.findEntity(obj)).isPresent();
            assertThat(db.currentSnapshot().findEntity(obj)).isEmpty();
            assertThat(db.currentSnapshot().findTypeByName("TransientType")).isEmpty();
            assertThat(db.currentSnapshot().entityCount()).isEqualTo(baselineEntities);
        }
        assertThat(db.headCommitId()).isEqualTo(CommitId.BOOTSTRAP);
    }

    @Test
    void t05RenameObjectTypeInNewCommitKeepsEntityIdAndHistoricalSnapshotSeesOldName() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personTypeId;
        EntityId personObjId;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Create Person type"))) {
            personTypeId = tx.defineObjectType("Person");
            personObjId = tx.createObject(personTypeId);
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Rename Person to Customer"))) {
            tx.renameSchemaElement(personTypeId, "Customer");
            c2 = tx.commit();
        }

        GraphReader snap1 = db.asOf(c1);
        GraphReader snap2 = db.asOf(c2);

        assertThat(snap1.findTypeByName("Person")).contains(personTypeId);
        assertThat(snap1.findTypeByName("Customer")).isEmpty();
        assertThat(snap1.resolveName(personTypeId)).contains("Person");
        assertThat(snap1.findEntity(personObjId).orElseThrow().typeId()).isEqualTo(personTypeId);

        assertThat(snap2.findTypeByName("Customer")).contains(personTypeId);
        assertThat(snap2.findTypeByName("Person")).isEmpty();
        assertThat(snap2.resolveName(personTypeId)).contains("Customer");
        assertThat(snap2.findEntity(personObjId).orElseThrow().typeId()).isEqualTo(personTypeId);
    }

    @Test
    void t05RenamePredicateInNewCommitKeepsEntityIdAndHistoricalSnapshotSeesOldName() {
        VersionedGraphDatabase db = createDatabase();
        EntityId hasNameId;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Define hasName"))) {
            EntityId p = tx.defineObjectType("Person");
            EntityId n = tx.defineValueType("Name", ValueType.STRING);
            hasNameId = tx.definePredicate("hasName", p, n);
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Rename hasName"))) {
            tx.renameSchemaElement(hasNameId, "hasLegalName");
            c2 = tx.commit();
        }

        assertThat(db.asOf(c1).findPredicateByName("hasName")).contains(hasNameId);
        assertThat(db.asOf(c1).resolveName(hasNameId)).contains("hasName");
        assertThat(db.asOf(c2).findPredicateByName("hasLegalName")).contains(hasNameId);
        assertThat(db.asOf(c2).findPredicateByName("hasName")).isEmpty();
        assertThat(db.asOf(c2).resolveName(hasNameId)).contains("hasLegalName");
    }

    @Test
    void t05AddingTypesAndDataInNewCommitDoesNotMutatePreviousSnapshots() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("v1"))) {
            tx.defineObjectType("Person");
            c1 = tx.commit();
        }
        long c1Entities = db.asOf(c1).entityCount();
        long c1Edges = db.asOf(c1).edgeCount();

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("v2"))) {
            EntityId p = tx.findTypeByName("Person").orElseThrow();
            EntityId fn = tx.defineValueType("FirstName", ValueType.STRING);
            EntityId pred = tx.definePredicate("hasFirstName", p, fn);
            EntityId person = tx.createObject(p);
            EntityId val = tx.createValue(fn, Value.of("Thomas"));
            tx.connect(person, pred, val);
            c2 = tx.commit();
        }

        assertThat(db.asOf(c1).entityCount()).isEqualTo(c1Entities);
        assertThat(db.asOf(c1).edgeCount()).isEqualTo(c1Edges);
        assertThat(db.asOf(c1).findTypeByName("FirstName")).isEmpty();
        assertThat(db.asOf(c2).findTypeByName("FirstName")).isPresent();
    }

    @Test
    void t06TighteningPredicateConstraintsSucceedsWhenExistingDataComplies() {
        VersionedGraphDatabase db = createDatabase();
        EntityId personType;
        EntityId firstNameType;
        EntityId hasFirstName;
        EntityId person1;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Unconstrained schema"))) {
            personType = tx.defineObjectType("Person");
            firstNameType = tx.defineValueType("FirstName", ValueType.STRING);
            hasFirstName = tx.definePredicate("hasFirstName", personType, firstNameType);
            person1 = tx.createObject(personType);
            tx.connect(person1, hasFirstName, tx.createValue(firstNameType, Value.of("Thomas")));
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Tighten to manyToOne"))) {
            tx.updatePredicateConstraints(hasFirstName, PredicateConstraints.manyToOne());
            EntityId extra = tx.createValue(firstNameType, Value.of("Tom"));
            assertThatThrownBy(() -> tx.connect(person1, hasFirstName, extra))
                    .isInstanceOf(CardinalityViolationException.class);
            c2 = tx.commit();
        }

        assertThat(db.asOf(c1).resolvePredicateConstraints(hasFirstName)).contains(PredicateConstraints.unconstrained());
        assertThat(db.asOf(c2).resolvePredicateConstraints(hasFirstName)).contains(PredicateConstraints.manyToOne());
    }

    @Test
    void t06TighteningPredicateConstraintsFailsAtomicallyWhenExistingDataViolatesMaxOutgoing() {
        VersionedGraphDatabase db = createDatabase();
        EntityId hasFirstName;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Two names for one person"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId firstNameType = tx.defineValueType("FirstName", ValueType.STRING);
            hasFirstName = tx.definePredicate("hasFirstName", personType, firstNameType);
            EntityId p = tx.createObject(personType);
            tx.connect(p, hasFirstName, tx.createValue(firstNameType, Value.of("Thomas")));
            tx.connect(p, hasFirstName, tx.createValue(firstNameType, Value.of("Paul")));
            tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Invalid tightening"))) {
            long entitiesBefore = tx.entityCount();
            long edgesBefore = tx.edgeCount();

            assertThatThrownBy(() -> tx.updatePredicateConstraints(hasFirstName, PredicateConstraints.manyToOne()))
                    .isInstanceOf(CardinalityViolationException.class);

            assertThat(tx.entityCount()).isEqualTo(entitiesBefore);
            assertThat(tx.edgeCount()).isEqualTo(edgesBefore);
            assertThat(tx.resolvePredicateConstraints(hasFirstName)).contains(PredicateConstraints.unconstrained());
        }
    }

    @Test
    void t06TighteningPredicateConstraintsFailsAtomicallyWhenExistingDataViolatesUniqueTriple() {
        VersionedGraphDatabase db = createDatabase();
        EntityId worksOn;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Duplicate assertions"))) {
            EntityId p = tx.defineObjectType("Person");
            EntityId proj = tx.defineObjectType("Project");
            worksOn = tx.definePredicate("worksOn", p, proj);
            EntityId alice = tx.createObject(p);
            EntityId veg = tx.createObject(proj);
            tx.connect(alice, worksOn, veg);
            tx.connect(alice, worksOn, veg);
            tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Reject uniqueSet"))) {
            assertThatThrownBy(() -> tx.updatePredicateConstraints(worksOn, PredicateConstraints.uniqueSet()))
                    .isInstanceOf(UniquenessViolationException.class);
            assertThat(tx.resolvePredicateConstraints(worksOn)).contains(PredicateConstraints.unconstrained());
        }
    }

    @Test
    void t06EnablingUniqueValuesFailsAtomicallyWhenDuplicateValuesExist() {
        VersionedGraphDatabase db = createDatabase();
        EntityId emailType;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Seed duplicate emails"))) {
            emailType = tx.defineValueType("Email", ValueType.STRING, false);
            tx.createValue(emailType, Value.of("dup@veg.io"));
            tx.createValue(emailType, Value.of("dup@veg.io"));
            tx.commit();
        }

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Enable uniqueness"))) {
            assertThatThrownBy(() -> tx.enableUniqueValues(emailType))
                    .isInstanceOf(UniquenessViolationException.class);
            assertThat(tx.isUniqueValueType(emailType)).isFalse();
        }
    }

    @Test
    void t06EnablingUniqueValuesSucceedsWhenAllExistingValuesAreDistinct() {
        VersionedGraphDatabase db = createDatabase();
        EntityId emailType;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Distinct emails"))) {
            emailType = tx.defineValueType("Email", ValueType.STRING, false);
            tx.createValue(emailType, Value.of("a@veg.io"));
            tx.createValue(emailType, Value.of("b@veg.io"));
            tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Enforce unique emails"))) {
            tx.enableUniqueValues(emailType);
            assertThat(tx.isUniqueValueType(emailType)).isTrue();
            assertThatThrownBy(() -> tx.createValue(emailType, Value.of("a@veg.io")))
                    .isInstanceOf(UniquenessViolationException.class);
            c2 = tx.commit();
        }

        assertThat(db.asOf(c2).isUniqueValueType(emailType)).isTrue();
    }

    @Test
    void t07ExceptionInsideTransactionAndAutoCloseLeaveDatabaseCompletelyUnchanged() {
        VersionedGraphDatabase db = createDatabase();
        CommitId headBefore = db.headCommitId();
        long entitiesBefore = db.currentSnapshot().entityCount();

        assertThatThrownBy(() -> {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Failing batch"))) {
                EntityId p = tx.defineObjectType("Person");
                tx.createObject(p);
                throw new IllegalStateException("Simulated business failure before commit");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(db.headCommitId()).isEqualTo(headBefore);
        assertThat(db.currentSnapshot().entityCount()).isEqualTo(entitiesBefore);
        assertThat(db.currentSnapshot().findTypeByName("Person")).isEmpty();
    }

    @Test
    void t07ExplicitRollbackDiscardsAllPendingMutationsAndClosesTransaction() {
        VersionedGraphDatabase db = createDatabase();
        GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Rollback test"));
        tx.defineObjectType("Person");
        tx.rollback();

        assertThat(tx.isOpen()).isFalse();
        assertThat(db.headCommitId()).isEqualTo(CommitId.BOOTSTRAP);
        assertThatThrownBy(tx::commit).isInstanceOf(TransactionStateException.class);
        assertThatThrownBy(tx::rollback).isInstanceOf(TransactionStateException.class);
        assertThatThrownBy(() -> tx.defineObjectType("Another")).isInstanceOf(TransactionStateException.class);
    }

    @Test
    void t08SavepointRollbackRestoresTransactionToSavepointAndCommitsOuterWork() {
        VersionedGraphDatabase db = createDatabase();
        EntityId outerPerson;
        EntityId innerPerson;
        CommitId c1;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Savepoint test"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId nameType = tx.defineValueType("FirstName", ValueType.STRING);
            EntityId hasFirstName = tx.definePredicate("hasFirstName", personType, nameType);

            outerPerson = tx.createObject(personType);
            EntityId thomas = tx.createValue(nameType, Value.of("Thomas"));
            tx.connect(outerPerson, hasFirstName, thomas);

            SavepointId sp = tx.createSavepoint("after-outer-person");

            innerPerson = tx.createObject(personType);
            EntityId tempName = tx.createValue(nameType, Value.of("Temporary"));
            tx.connect(innerPerson, hasFirstName, tempName);
            assertThat(tx.findEntity(innerPerson)).isPresent();

            tx.rollbackToSavepoint(sp);

            assertThat(tx.findEntity(outerPerson)).isPresent();
            assertThat(tx.findEntity(innerPerson)).isEmpty();

            c1 = tx.commit();
        }

        GraphReader snap = db.asOf(c1);
        assertThat(snap.findEntity(outerPerson)).isPresent();
        assertThat(snap.findEntity(innerPerson)).isEmpty();
    }

    @Test
    void t08NestedSavepointsRollbackSelectivelyAndInvalidateInnerSavepoint() {
        VersionedGraphDatabase db = createDatabase();
        EntityId p1;
        EntityId p2;
        EntityId p3;
        CommitId c1;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Nested savepoints"))) {
            EntityId personType = tx.defineObjectType("Person");
            p1 = tx.createObject(personType);
            SavepointId sp1 = tx.createSavepoint("sp1");

            p2 = tx.createObject(personType);
            SavepointId sp2 = tx.createSavepoint("sp2");

            p3 = tx.createObject(personType);
            assertThat(tx.findEntity(p3)).isPresent();

            // Rollback inner savepoint sp2 -> p3 gone, p2 kept
            tx.rollbackToSavepoint(sp2);
            assertThat(tx.findEntity(p2)).isPresent();
            assertThat(tx.findEntity(p3)).isEmpty();

            // Re-create p3 after sp2 rollback, then rollback outer sp1 -> p2 & new p3 gone, sp2 invalidated
            EntityId p3Retry = tx.createObject(personType);
            assertThat(tx.findEntity(p3Retry)).isPresent();
            tx.rollbackToSavepoint(sp1);
            assertThat(tx.findEntity(p1)).isPresent();
            assertThat(tx.findEntity(p2)).isEmpty();
            assertThat(tx.findEntity(p3Retry)).isEmpty();

            // Using superseded sp2 must fail
            assertThatThrownBy(() -> tx.rollbackToSavepoint(sp2))
                    .isInstanceOf(TransactionStateException.class);

            c1 = tx.commit();
        }

        assertThat(db.asOf(c1).findEntity(p1)).isPresent();
        assertThat(db.asOf(c1).findEntity(p2)).isEmpty();
    }

    @Test
    void t09RevertOfCommitCreatesNewCounterCommitAndPreservesOriginalCommitInHistory() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        EntityId personType;
        EntityId firstNameType;
        EntityId hasFirstName;
        EntityId person;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Seed person"))) {
            personType = tx.defineObjectType("Person");
            firstNameType = tx.defineValueType("FirstName", ValueType.STRING);
            hasFirstName = tx.definePredicate("hasFirstName", personType, firstNameType);
            person = tx.createObject(personType);
            c1 = tx.commit();
        }

        CommitId c2;
        EntityId wrongName;
        EntityId wrongEdge;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("Wrong entry"))) {
            wrongName = tx.createValue(firstNameType, Value.of("WrongName"));
            wrongEdge = tx.connect(person, hasFirstName, wrongName);
            c2 = tx.commit();
        }

        CommitId c3 = db.revert(c2, Actor.user("admin"), Reason.of("Revert wrong entry"), "INC-99");

        assertThat(c3).isEqualTo(new CommitId(3L));
        assertThat(db.commitHistory()).hasSize(4);
        assertThat(db.getCommit(c2).reason()).isEqualTo(Reason.of("Wrong entry"));
        assertThat(db.getCommit(c3).reason()).isEqualTo(Reason.of("Revert wrong entry"));
        assertThat(db.getCommit(c3).correlationId()).contains("INC-99");

        // c3 state equals c1 state structurally
        assertThat(db.asOf(c3).findEntity(wrongName)).isEmpty();
        assertThat(db.asOf(c3).findEdge(wrongEdge)).isEmpty();
        assertThat(db.asOf(c3).findEntity(person)).isPresent();
        assertThat(db.diff(c1, c3).isEmpty()).isTrue();

        // Historical c2 state remains untouched for audit
        assertThat(db.asOf(c2).findEntity(wrongName)).isPresent();
        assertThat(db.asOf(c2).findEdge(wrongEdge)).isPresent();
    }

    @Test
    void t09RevertOfSharedMutableValueUpdateRestoresPreviousValue() {
        VersionedGraphDatabase db = createDatabase();
        EntityId companyNameId;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Initial company"))) {
            EntityId companyNameType = tx.defineValueType("CompanyName", ValueType.STRING, true);
            companyNameId = tx.createValue(companyNameType, Value.of("Acme GmbH"), ValueMutability.SHARED_MUTABLE);
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("Typo update"))) {
            tx.updateValue(companyNameId, Value.of("Acmee GmbH"));
            c2 = tx.commit();
        }

        CommitId c3 = db.revert(c2, Actor.user("u1"), Reason.of("Fix typo via revert"));
        assertThat(db.asOf(c3).findEntity(companyNameId).orElseThrow().payload()).contains(Value.of("Acme GmbH"));
        assertThat(db.diff(c1, c3).isEmpty()).isTrue();
    }

    @Test
    void t09RevertFailsAtomicallyWithRevertConflictWhenSubsequentCommitDependsOnCreatedEntity() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        EntityId person;
        EntityId docType;
        EntityId hasFirstName;
        EntityId supportedBy;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Base schema and person"))) {
            EntityId personType = tx.defineObjectType("Person");
            docType = tx.defineObjectType("SourceDocument");
            EntityId fnType = tx.defineValueType("FirstName", ValueType.STRING);
            hasFirstName = tx.definePredicate("hasFirstName", personType, fnType);
            supportedBy = tx.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);
            person = tx.createObject(personType);
            c1 = tx.commit();
        }

        CommitId c2;
        EntityId primaryEdge;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("Add primary claim"))) {
            EntityId fnType = tx.findTypeByName("FirstName").orElseThrow();
            EntityId thomas = tx.createValue(fnType, Value.of("Thomas"));
            primaryEdge = tx.connect(person, hasFirstName, thomas);
            c2 = tx.commit();
        }

        CommitId c3;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u3"), Reason.of("Attach provenance to c2 edge"))) {
            EntityId doc = tx.createObject(docType);
            tx.connect(primaryEdge, supportedBy, doc);
            c3 = tx.commit();
        }

        assertThatThrownBy(() -> db.revert(c2, Actor.user("admin"), Reason.of("Conflicting revert")))
                .isInstanceOf(RevertConflictException.class);
        assertThat(db.headCommitId()).isEqualTo(c3);
        assertThat(db.currentSnapshot().findEdge(primaryEdge)).isPresent();
        assertThatThrownBy(() -> db.revert(CommitId.BOOTSTRAP, Actor.user("admin"), Reason.of("Cannot revert c0")))
                .isInstanceOf(RevertConflictException.class);
    }

    @Test
    void t09RevertFailsAtomicallyWhenSubsequentCommitModifiedSameValue() {
        VersionedGraphDatabase db = createDatabase();
        EntityId valId;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Seed"))) {
            EntityId vt = tx.defineValueType("Status", ValueType.STRING);
            valId = tx.createValue(vt, Value.of("OPEN"), ValueMutability.SHARED_MUTABLE);
            tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("Step 2"))) {
            tx.updateValue(valId, Value.of("IN_PROGRESS"));
            c2 = tx.commit();
        }

        CommitId c3;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u3"), Reason.of("Step 3"))) {
            tx.updateValue(valId, Value.of("CLOSED"));
            c3 = tx.commit();
        }

        assertThatThrownBy(() -> db.revert(c2, Actor.user("admin"), Reason.of("Stale revert")))
                .isInstanceOf(RevertConflictException.class);
        assertThat(db.headCommitId()).isEqualTo(c3);
        assertThat(db.currentSnapshot().findEntity(valId).orElseThrow().payload()).contains(Value.of("CLOSED"));
    }

    @Test
    void historyOfEntityReturnsOnlyCommitsTouchingThatEntityOrItsIncidentEdges() {
        VersionedGraphDatabase db = createDatabase();
        EntityId person1;
        EntityId person2;
        EntityId sharedCompany;
        EntityId p1CompanyEdge;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Create persons"))) {
            EntityId pType = tx.defineObjectType("Person");
            EntityId cType = tx.defineValueType("Company", ValueType.STRING);
            EntityId worksAt = tx.definePredicate("worksAt", pType, cType);
            person1 = tx.createObject(pType);
            person2 = tx.createObject(pType);
            sharedCompany = tx.createValue(cType, Value.of("Acme"), ValueMutability.SHARED_MUTABLE);
            p1CompanyEdge = tx.connect(person1, worksAt, sharedCompany);
            c1 = tx.commit();
        }

        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("Touch only person2"))) {
            EntityId worksAt = tx.findPredicateByName("worksAt").orElseThrow();
            tx.connect(person2, worksAt, sharedCompany);
            c2 = tx.commit();
        }

        CommitId c3;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u3"), Reason.of("Update sharedCompany"))) {
            tx.updateValue(sharedCompany, Value.of("Acme AG"));
            c3 = tx.commit();
        }

        CommitId c4;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u4"), Reason.of("Disconnect person1"))) {
            tx.disconnect(p1CompanyEdge);
            c4 = tx.commit();
        }

        assertThat(db.historyOf(person1)).extracting(Commit::id).containsExactly(c1, c4);
        assertThat(db.historyOf(person2)).extracting(Commit::id).containsExactly(c1, c2);
        assertThat(db.historyOf(sharedCompany)).extracting(Commit::id).containsExactly(c1, c2, c3, c4);
    }

    @Test
    void diffComputesAddedRemovedAndUpdatedEntitiesAndEdgesBetweenAnyTwoCommits() {
        VersionedGraphDatabase db = createDatabase();
        EntityId p1;
        EntityId val1;
        EntityId edge1;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u1"), Reason.of("Initial"))) {
            EntityId pType = tx.defineObjectType("Person");
            EntityId nType = tx.defineValueType("Label", ValueType.STRING);
            EntityId hasLabel = tx.definePredicate("hasLabel", pType, nType);
            p1 = tx.createObject(pType);
            val1 = tx.createValue(nType, Value.of("Alpha"), ValueMutability.SHARED_MUTABLE);
            edge1 = tx.connect(p1, hasLabel, val1);
            c1 = tx.commit();
        }

        EntityId p2;
        EntityId edge2;
        CommitId c2;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("u2"), Reason.of("Mutate"))) {
            EntityId pType = tx.findTypeByName("Person").orElseThrow();
            EntityId hasLabel = tx.findPredicateByName("hasLabel").orElseThrow();
            tx.disconnect(edge1);
            tx.updateValue(val1, Value.of("Beta"));
            p2 = tx.createObject(pType);
            edge2 = tx.connect(p2, hasLabel, val1);
            c2 = tx.commit();
        }

        GraphDiff diff = db.diff(c1, c2);
        assertThat(diff.isEmpty()).isFalse();
        assertThat(diff.addedEntities()).extracting(node -> node.id()).contains(p2);
        assertThat(diff.removedEntities()).extracting(node -> node.id()).containsExactly(edge1);
        assertThat(diff.updatedValues()).containsExactly(new Change.ValueUpdated(val1, Value.of("Alpha"), Value.of("Beta")));
        assertThat(diff.addedEdges()).extracting(edge -> edge.id()).containsExactly(edge2);
        assertThat(diff.removedEdges()).extracting(edge -> edge.id()).containsExactly(edge1);
    }

    @Test
    void t15ReplayFromCommitZeroThrough1000DeterministicMutationsMatchesLiveSnapshotExactly() {
        VersionedGraphDatabase db = createDatabase();
        SplittableRandom rng = new SplittableRandom(42L);

        EntityId personType;
        EntityId docType;
        EntityId nameType;
        EntityId hasName;
        EntityId supportedBy;
        try (GraphTransaction tx = db.beginTransaction(Actor.user("seed"), Reason.of("Schema"))) {
            personType = tx.defineObjectType("Person");
            docType = tx.defineObjectType("SourceDocument");
            nameType = tx.defineValueType("Name", ValueType.STRING);
            hasName = tx.definePredicate("hasName", personType, nameType);
            supportedBy = tx.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);
            tx.commit();
        }

        List<EntityId> persons = new ArrayList<>();
        List<EntityId> docs = new ArrayList<>();
        List<EntityId> mutableNames = new ArrayList<>();
        List<EntityId> primaryEdges = new ArrayList<>();
        List<EntityId> provEdges = new ArrayList<>();

        int totalMutations = 0;
        int commitNum = 0;
        while (totalMutations < 1_050) {
            commitNum++;
            try (GraphTransaction tx = db.beginTransaction(Actor.user("worker"), Reason.of("Batch " + commitNum))) {
                for (int step = 0; step < 25 && totalMutations < 1_050; step++) {
                    int op = rng.nextInt(7);
                    switch (op) {
                        case 0 -> {
                            persons.add(tx.createObject(personType));
                            totalMutations++;
                        }
                        case 1 -> {
                            docs.add(tx.createObject(docType));
                            totalMutations++;
                        }
                        case 2 -> {
                            mutableNames.add(tx.createValue(nameType, Value.of("N-" + totalMutations), ValueMutability.SHARED_MUTABLE));
                            totalMutations++;
                        }
                        case 3 -> {
                            if (!persons.isEmpty() && !mutableNames.isEmpty()) {
                                EntityId p = persons.get(rng.nextInt(persons.size()));
                                EntityId n = mutableNames.get(rng.nextInt(mutableNames.size()));
                                primaryEdges.add(tx.connect(p, hasName, n));
                                totalMutations++;
                            }
                        }
                        case 4 -> {
                            if (!primaryEdges.isEmpty() && !docs.isEmpty()) {
                                EntityId e = primaryEdges.get(rng.nextInt(primaryEdges.size()));
                                EntityId d = docs.get(rng.nextInt(docs.size()));
                                provEdges.add(tx.connect(e, supportedBy, d));
                                totalMutations++;
                            }
                        }
                        case 5 -> {
                            if (!mutableNames.isEmpty()) {
                                EntityId n = mutableNames.get(rng.nextInt(mutableNames.size()));
                                tx.updateValue(n, Value.of("Updated-" + totalMutations));
                                totalMutations++;
                            }
                        }
                        case 6 -> {
                            if (!provEdges.isEmpty()) {
                                EntityId pe = provEdges.removeLast();
                                tx.disconnect(pe);
                                totalMutations++;
                            }
                        }
                        default -> {
                        }
                    }
                }
                if (commitNum == 15) {
                    // Interleave a schema rename to verify schema evolution replays identically too
                    tx.renameSchemaElement(personType, "Individual");
                }
                tx.commit();
            }
        }

        // Verify replay equivalence across EVERY commit from c0 to head
        for (Commit commit : db.commitHistory()) {
            GraphReader live = db.asOf(commit.id());
            GraphReader replayed = db.replayAsOf(commit.id());

            assertThat(replayed.entityCount()).isEqualTo(live.entityCount());
            assertThat(replayed.edgeCount()).isEqualTo(live.edgeCount());
            assertThat(replayed.allEntities()).isEqualTo(live.allEntities());
            assertThat(replayed.allEdges()).isEqualTo(live.allEdges());
            assertThat(replayed.resolveName(personType)).isEqualTo(live.resolveName(personType));
            assertThat(replayed.resolvePredicateConstraints(hasName)).isEqualTo(live.resolvePredicateConstraints(hasName));
        }
        assertThat(db.currentSnapshot().findTypeByName("Individual")).contains(personType);
    }
}
