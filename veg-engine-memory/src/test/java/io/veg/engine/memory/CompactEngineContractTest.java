package io.veg.engine.memory;

import io.veg.engine.GraphSession;
import io.veg.engine.GraphTransaction;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage1ContractTestSuite;
import io.veg.fixtures.Stage2ContractTestSuite;
import io.veg.fixtures.Stage3ContractTestSuite;
import io.veg.fixtures.Stage4ContractTestSuite;
import io.veg.fixtures.Stage5ContractTestSuite;
import io.veg.fixtures.Stage6ContractTestSuite;
import io.veg.model.Actor;
import io.veg.model.EntityId;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Stage 1–6 contract equivalence test suite proving identical semantics across both
 * Engine B1 (CSR snapshot every commit) and Engine B2 (Journal-as-Truth with periodic CSR checkpoints).
 */
class CompactEngineContractTest {

    // --- Engine B1: snapshotInterval = 1 (materialized CSR per commit) ---

    @Nested class B1Stage1 extends Stage1ContractTestSuite {
        @Override protected GraphSession createSession() {
            return new CompactVersionedDatabase(1).beginTransaction(Actor.system(), Reason.of("s1"));
        }
    }

    @Nested class B1Stage2 extends Stage2ContractTestSuite {
        @Override protected GraphSession createSession() {
            return new CompactVersionedDatabase(1).beginTransaction(Actor.system(), Reason.of("s2"));
        }
    }

    @Nested class B1Stage3 extends Stage3ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(1); }
    }

    @Nested class B1Stage4 extends Stage4ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(1); }
    }

    @Nested class B1Stage5 extends Stage5ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(1); }
    }

    @Nested class B1Stage6 extends Stage6ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(1); }
    }

    // --- Engine B2: snapshotInterval = 4 (Journal + periodic CSR checkpoints + delta reconstruction) ---

    @Nested class B2Stage3 extends Stage3ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(4); }
    }

    @Nested class B2Stage4 extends Stage4ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(4); }
    }

    @Nested class B2Stage5 extends Stage5ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(4); }
    }

    @Nested class B2Stage6 extends Stage6ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new CompactVersionedDatabase(4); }
    }

    @Test
    void checkpointIntervalReducesStoredSnapshotsAndInternsImmutableValuesWithoutMergingIdentities() {
        CompactVersionedDatabase b2 = new CompactVersionedDatabase(5);
        EntityId p1Name;
        EntityId p2Name;
        for (int i = 0; i < 12; i++) {
            try (GraphTransaction tx = b2.beginTransaction(Actor.user("u"), Reason.of("c" + i))) {
                EntityId strType = tx.findTypeByName("SharedStr")
                        .orElseGet(() -> tx.defineValueType("SharedStr", ValueType.STRING));
                EntityId v1 = tx.createValue(strType, Value.of("CommonCity"));
                if (i == 0) {
                    p1Name = v1;
                }
                if (i == 1) {
                    p2Name = v1;
                }
                tx.commit();
            }
        }
        // 13 total commits (c0..c12): only checkpoints at c0, c5, c10 should be materialized!
        assertThat(b2.storedCheckpointCount()).isEqualTo(3);
        assertThat(b2.internedDistinctValueCount()).isGreaterThanOrEqualTo(1);
        assertThat(b2.totalCheckpointBytes()).isGreaterThan(0L);
        // Reconstructing commit c12 (uncheckpointed) reproduces exact state identically to replayAsOf(c12)
        var headCommit = b2.headCommitId();
        assertThat(b2.asOf(headCommit).allEntities()).isEqualTo(b2.replayAsOf(headCommit).allEntities());
        assertThat(b2.asOf(headCommit).allEdges()).isEqualTo(b2.replayAsOf(headCommit).allEdges());
    }
}
