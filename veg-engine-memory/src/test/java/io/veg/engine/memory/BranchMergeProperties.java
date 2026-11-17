package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Reason;
import io.veg.model.Value;
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
 * Property-based tests verifying merge commutativity for independent branch changes
 * and replay equivalence across two-parent merge commits.
 */
class BranchMergeProperties {

    @Property(tries = 25)
    void mergingIndependentBranchChangesIsCommutativeAndReplayConsistent(@ForAll("seeds") long seed) {
        // Run 1: merge branchA then branchB into main
        InMemoryVersionedDatabase dbAB = new InMemoryVersionedDatabase();
        // Run 2: identical independent changes, merge branchB then branchA into main
        InMemoryVersionedDatabase dbBA = new InMemoryVersionedDatabase();

        EntityId[] typesAB = setupBase(dbAB);
        EntityId[] typesBA = setupBase(dbBA);
        CommitId baseAB = dbAB.headCommitId();
        CommitId baseBA = dbBA.headCommitId();

        dbAB.createBranch("branchA", baseAB);
        dbAB.createBranch("branchB", baseAB);
        dbBA.createBranch("branchA", baseBA);
        dbBA.createBranch("branchB", baseBA);

        populateIndependentBranch(dbAB, "branchA", typesAB, seed, "A");
        populateIndependentBranch(dbAB, "branchB", typesAB, seed + 1, "B");
        populateIndependentBranch(dbBA, "branchA", typesBA, seed, "A");
        populateIndependentBranch(dbBA, "branchB", typesBA, seed + 1, "B");

        MergeResult rAB1 = dbAB.merge("branchA", "main", Actor.user("m"), Reason.of("Merge A"), MergePolicy.developmentDefault());
        MergeResult rAB2 = dbAB.merge("branchB", "main", Actor.user("m"), Reason.of("Merge B"), MergePolicy.developmentDefault());

        MergeResult rBA1 = dbBA.merge("branchB", "main", Actor.user("m"), Reason.of("Merge B"), MergePolicy.developmentDefault());
        MergeResult rBA2 = dbBA.merge("branchA", "main", Actor.user("m"), Reason.of("Merge A"), MergePolicy.developmentDefault());

        assertThat(rAB1.succeeded()).isTrue();
        assertThat(rAB2.succeeded()).isTrue();
        assertThat(rBA1.succeeded()).isTrue();
        assertThat(rBA2.succeeded()).isTrue();

        GraphReader snapAB = dbAB.branchSnapshot("main");
        GraphReader snapBA = dbBA.branchSnapshot("main");

        assertThat(snapAB.entityCount()).isEqualTo(snapBA.entityCount());
        assertThat(snapAB.edgeCount()).isEqualTo(snapBA.edgeCount());
        assertThat(snapAB.allEntities()).isEqualTo(snapBA.allEntities());
        assertThat(snapAB.allEdges()).isEqualTo(snapBA.allEdges());

        CommitId mergeIdAB = rAB2.mergeCommitId().orElseThrow();
        GraphReader replayedAB = dbAB.replayAsOf(mergeIdAB);
        assertThat(replayedAB.allEntities()).isEqualTo(snapAB.allEntities());
        assertThat(replayedAB.allEdges()).isEqualTo(snapAB.allEdges());
    }

    private static EntityId[] setupBase(InMemoryVersionedDatabase db) {
        try (GraphTransaction tx = db.beginTransaction(Actor.user("init"), Reason.of("Schema"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId labelType = tx.defineValueType("Label", ValueType.STRING);
            EntityId hasLabel = tx.definePredicate("hasLabel", personType, labelType);
            tx.commit();
            return new EntityId[]{personType, labelType, hasLabel};
        }
    }

    private static void populateIndependentBranch(
            InMemoryVersionedDatabase db,
            String branchName,
            EntityId[] schema,
            long seed,
            String prefix
    ) {
        Random rng = new Random(seed);
        try (GraphTransaction tx = db.beginTransaction(branchName, Actor.user(prefix), Reason.of("Work " + prefix))) {
            int count = 3 + rng.nextInt(8);
            List<EntityId> created = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                EntityId p = tx.createObject(schema[0]);
                EntityId lbl = tx.createValue(schema[1], Value.of(prefix + "-" + i));
                tx.connect(p, schema[2], lbl);
                created.add(p);
            }
            assertThat(created).hasSize(count);
            tx.commit();
        }
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs().between(1L, 100_000L);
    }
}
