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
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Stage 1–6 contract equivalence test suite for Engine C ({@link PersistentTreeVersionedDatabase}),
 * plus direct {@link PersistentLongMap} trie and structural sharing tests across commits.
 */
class PersistentTreeEngineContractTest {

    @Nested class CStage1 extends Stage1ContractTestSuite {
        @Override protected GraphSession createSession() {
            return new PersistentTreeVersionedDatabase().beginTransaction(Actor.system(), Reason.of("s1"));
        }
    }

    @Nested class CStage2 extends Stage2ContractTestSuite {
        @Override protected GraphSession createSession() {
            return new PersistentTreeVersionedDatabase().beginTransaction(Actor.system(), Reason.of("s2"));
        }
    }

    @Nested class CStage3 extends Stage3ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new PersistentTreeVersionedDatabase(); }
    }

    @Nested class CStage4 extends Stage4ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new PersistentTreeVersionedDatabase(); }
    }

    @Nested class CStage5 extends Stage5ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new PersistentTreeVersionedDatabase(); }
    }

    @Nested class CStage6 extends Stage6ContractTestSuite {
        @Override protected VersionedGraphDatabase createDatabase() { return new PersistentTreeVersionedDatabase(); }
    }

    @Test
    void persistentLongMapHandlesInsertOverwriteRemoveAndLargeKeysCorrectly() {
        PersistentLongMap<String> map = PersistentLongMap.empty();
        Map<Long, String> oracle = new HashMap<>();

        long[] testKeys = {0L, 1L, 31L, 32L, 33L, 1023L, 1024L, 1_000_000L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (int i = 0; i < testKeys.length; i++) {
            String val = "v" + i;
            map = map.put(testKeys[i], val);
            oracle.put(testKeys[i], val);
        }
        for (long i = 500; i < 650; i++) {
            map = map.put(i, "bulk-" + i);
            oracle.put(i, "bulk-" + i);
        }

        // Idempotent overwrite returns identical instance
        PersistentLongMap<String> unchanged = map.put(32L, "v3");
        assertThat(unchanged).isSameAs(map);

        // Overwrite changes only new map, old map remains immutable
        PersistentLongMap<String> updated = map.put(32L, "v3-updated");
        assertThat(map.get(32L)).isEqualTo("v3");
        assertThat(updated.get(32L)).isEqualTo("v3-updated");

        // Removals preserve remaining entries
        PersistentLongMap<String> trimmed = updated;
        for (long k : new long[]{0L, 31L, 550L, -1L, Long.MAX_VALUE}) {
            trimmed = trimmed.remove(k);
        }
        assertThat(trimmed.size()).isEqualTo(oracle.size() - 5);
        assertThat(trimmed.containsKey(31L)).isFalse();
        assertThat(trimmed.get(32L)).isEqualTo("v3-updated");
        assertThat(trimmed.get(Long.MIN_VALUE)).isEqualTo("v9");
        assertThat(trimmed.valuesSorted(Comparator.naturalOrder())).hasSize(trimmed.size());
    }

    @Test
    void structuralSharingSharesMajorityOfTrieNodesAcrossManyIncrementalCommits() {
        PersistentTreeVersionedDatabase db = new PersistentTreeVersionedDatabase();
        EntityId personType;
        EntityId namePred;
        EntityId nameType;
        List<EntityId> people = new ArrayList<>();

        // Commit 1: bulk-load 500 objects
        try (GraphTransaction tx = db.beginTransaction(Actor.user("seed"), Reason.of("initial population"))) {
            personType = tx.defineObjectType("Person");
            nameType = tx.defineValueType("Name", ValueType.STRING);
            namePred = tx.definePredicate("name", personType, nameType);
            for (int i = 0; i < 500; i++) {
                EntityId p = tx.createObject(personType);
                tx.connect(p, namePred, tx.createValue(nameType, Value.of("P" + i)));
                people.add(p);
            }
            tx.commit();
        }

        long initialEntities = db.currentSnapshot().entityCount();
        int nodesAfterCommit1 = db.countTotalUniqueTrieNodesAcrossAllCommits();

        // Commits 2..51: 50 small incremental commits (1 new person each)
        for (int c = 0; c < 50; c++) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("writer"), Reason.of("inc-" + c))) {
                EntityId p = tx.createObject(personType);
                tx.connect(p, namePred, tx.createValue(nameType, Value.of("Inc" + c)));
                tx.commit();
            }
        }

        int nodesAfter51Commits = db.countTotalUniqueTrieNodesAcrossAllCommits();
        // Without structural sharing, 50 copies of ~1800+ entries would allocate ~90k+ unique nodes;
        // With path-copying on radix-32 trie, growth is proportional only to O(changes * log32 N)!
        assertThat(nodesAfter51Commits).isLessThan(nodesAfterCommit1 * 4);
        assertThat(db.currentSnapshot().entityCount()).isGreaterThan(initialEntities);
        CommitId mid = new CommitId(25L);
        assertThat(db.asOf(mid).allEntities()).isEqualTo(db.replayAsOf(mid).allEntities());
    }
}
