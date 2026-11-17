package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.RevertConflictException;
import io.veg.model.Actor;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.SavepointId;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests verifying Stage 3 replay equivalence (I-12), snapshot immutability (I-11),
 * and head-commit revert involution.
 */
class VersionedDatabaseProperties {

    @Property(tries = 30)
    void randomizedMultiCommitHistoriesWithSavepointsAndRollbacksPreserveReplayAndRevertInvariants(@ForAll("seeds") long seed) {
        InMemoryVersionedDatabase db = new InMemoryVersionedDatabase();
        EntityId personType;
        EntityId nameType;
        EntityId hasName;

        try (GraphTransaction tx = db.beginTransaction(Actor.user("init"), Reason.of("Init"))) {
            personType = tx.defineObjectType("Person");
            nameType = tx.defineValueType("Name", ValueType.STRING);
            hasName = tx.definePredicate("hasName", personType, nameType, PredicateConstraints.unconstrained());
            tx.commit();
        }

        List<EntityId> persons = new ArrayList<>();
        List<EntityId> mutableValues = new ArrayList<>();
        List<EntityId> edges = new ArrayList<>();
        Random random = new Random(seed);

        for (int txIdx = 0; txIdx < 12; txIdx++) {
            boolean shouldRollback = random.nextInt(5) == 0;
            CommitId headBefore = db.headCommitId();
            long entitiesBefore = db.currentSnapshot().entityCount();

            try (GraphTransaction tx = db.beginTransaction(Actor.user("u" + txIdx), Reason.of("Tx " + txIdx))) {
                SavepointId sp = null;
                List<EntityId> txPersons = new ArrayList<>();
                List<EntityId> txValues = new ArrayList<>();
                List<EntityId> txEdges = new ArrayList<>();

                for (int step = 0; step < 8; step++) {
                    if (step == 3) {
                        sp = tx.createSavepoint("mid");
                    }
                    switch (random.nextInt(4)) {
                        case 0 -> txPersons.add(tx.createObject(personType));
                        case 1 -> txValues.add(tx.createValue(nameType, Value.of("V-" + txIdx + "-" + step), ValueMutability.SHARED_MUTABLE));
                        case 2 -> {
                            EntityId p = !txPersons.isEmpty() ? txPersons.get(random.nextInt(txPersons.size()))
                                    : (!persons.isEmpty() ? persons.get(random.nextInt(persons.size())) : null);
                            EntityId v = !txValues.isEmpty() ? txValues.get(random.nextInt(txValues.size()))
                                    : (!mutableValues.isEmpty() ? mutableValues.get(random.nextInt(mutableValues.size())) : null);
                            if (p != null && v != null) {
                                txEdges.add(tx.connect(p, hasName, v));
                            }
                        }
                        case 3 -> {
                            if (!mutableValues.isEmpty()) {
                                EntityId v = mutableValues.get(random.nextInt(mutableValues.size()));
                                tx.updateValue(v, Value.of("Upd-" + txIdx + "-" + step));
                            }
                        }
                        default -> {
                        }
                    }
                    if (step == 6 && sp != null && random.nextBoolean()) {
                        tx.rollbackToSavepoint(sp);
                        // Clear local tracking to avoid referring to rolled-back entities
                        txPersons.clear();
                        txValues.clear();
                        txEdges.clear();
                        sp = null;
                    }
                }

                if (shouldRollback) {
                    tx.rollback();
                    assertThat(db.headCommitId()).isEqualTo(headBefore);
                    assertThat(db.currentSnapshot().entityCount()).isEqualTo(entitiesBefore);
                } else {
                    CommitId committed = tx.commit();
                    // Note: if savepoint rolled back mid-way, query snapshot directly for valid live IDs
                    GraphReader snap = db.asOf(committed);
                    for (EntityId id : txPersons) {
                        if (snap.findEntity(id).isPresent()) persons.add(id);
                    }
                    for (EntityId id : txValues) {
                        if (snap.findEntity(id).isPresent()) mutableValues.add(id);
                    }
                    for (EntityId id : txEdges) {
                        if (snap.findEdge(id).isPresent()) edges.add(id);
                    }
                }
            }
        }

        // Verify reverting the current head commit restores the immediate predecessor state
        CommitId lastUserCommit = db.headCommitId();
        if (!lastUserCommit.isBootstrap()) {
            CommitId parent = db.getCommit(lastUserCommit).parentId().orElseThrow();
            try {
                CommitId revertCommit = db.revert(lastUserCommit, Actor.user("verifier"), Reason.of("Revert head"));
                assertThat(db.diff(parent, revertCommit).isEmpty()).isTrue();
            } catch (RevertConflictException ignored) {
                // Allowed only if an earlier non-head scenario occurred; head revert should never fail unless empty
                throw new AssertionError("Reverting head commit should never conflict", ignored);
            }
        }

        // Replay equivalence across the entire commit history (including the revert commit)
        for (Commit commit : db.commitHistory()) {
            GraphReader live = db.asOf(commit.id());
            GraphReader replayed = db.replayAsOf(commit.id());
            assertThat(replayed.allEntities()).isEqualTo(live.allEntities());
            assertThat(replayed.allEdges()).isEqualTo(live.allEdges());
        }
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs().between(1L, 100_000L);
    }
}
