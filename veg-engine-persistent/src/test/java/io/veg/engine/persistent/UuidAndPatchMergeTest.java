package io.veg.engine.persistent;

import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.VegPatchService;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VegPatchService.PatchResult;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.engine.memory.CompactVersionedDatabase;
import io.veg.engine.memory.InMemoryVersionedDatabase;
import io.veg.engine.memory.PersistentTreeVersionedDatabase;
import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.IdGenerationStrategy;
import io.veg.model.MergeConflictType;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class UuidAndPatchMergeTest {

    @TempDir
    Path tempDir;

    @Test
    void uuidV7AndV4WorkAcrossAllEnginesAndSurviveDiskRestart() {
        List<Supplier<VersionedGraphDatabase>> engines = List.of(
                InMemoryVersionedDatabase::new,
                CompactVersionedDatabase::new,
                PersistentTreeVersionedDatabase::new
        );

        for (Supplier<VersionedGraphDatabase> supplier : engines) {
            VersionedGraphDatabase db = supplier.get();
            db.setIdGenerationStrategy(IdGenerationStrategy.UUID_V7);

            EntityId objId;
            EntityId valId;
            EntityId edgeId;
            try (GraphTransaction tx = db.beginTransaction(Actor.user("alice"), Reason.of("UUID engine check"))) {
                EntityId person = tx.defineObjectType("Person");
                EntityId nameType = tx.defineValueType("Name", ValueType.STRING);
                EntityId hasName = tx.definePredicate("hasName", person, nameType, PredicateConstraints.manyToOne());

                assertThat(person.isUuid()).isTrue();
                objId = tx.createObject(person);
                valId = tx.createValue(nameType, Value.of("Thomas"));
                edgeId = tx.connect(objId, hasName, valId);
                tx.commit();
            }

            assertThat(objId.isUuid()).isTrue();
            assertThat(valId.isUuid()).isTrue();
            assertThat(edgeId.isUuid()).isTrue();

            GraphReader snap = db.currentSnapshot();
            assertThat(snap.findEntity(objId)).isPresent();
            assertThat(snap.findEdge(edgeId)).isPresent();
            assertThat(snap.findOutgoingEdges(objId)).hasSize(1);
            assertThat(snap.findEntitiesByType(snap.findTypeByName("Person").orElseThrow())).hasSize(1);
        }

        // Disk engine round-trip with UUID_V7 + UUID_V4 and WAL recovery
        Path diskDir = tempDir.resolve("uuid-disk");
        EntityId aliceId;
        EntityId bobId;
        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(diskDir)) {
            db.setIdGenerationStrategy(IdGenerationStrategy.UUID_V7);
            try (GraphTransaction tx = db.beginTransaction(Actor.user("alice"), Reason.of("seed uuid v7"))) {
                EntityId person = tx.defineObjectType("Person");
                EntityId code = tx.defineValueType("Code", ValueType.STRING);
                EntityId hasCode = tx.definePredicate("hasCode", person, code, PredicateConstraints.manyToOne());
                aliceId = tx.createObject(person);
                tx.connect(aliceId, hasCode, tx.createValue(code, Value.of("A1")));
                tx.commit();
            }
            db.setIdGenerationStrategy(IdGenerationStrategy.UUID_V4);
            try (GraphTransaction tx = db.beginTransaction(Actor.user("alice"), Reason.of("add uuid v4"))) {
                EntityId person = tx.findTypeByName("Person").orElseThrow();
                EntityId hasCode = tx.findPredicateByName("hasCode").orElseThrow();
                EntityId code = tx.findTypeByName("Code").orElseThrow();
                bobId = tx.createObject(person);
                tx.connect(bobId, hasCode, tx.createValue(code, Value.of("B4")));
                tx.commit();
            }
        }

        try (PersistentDiskVersionedDatabase reopened = PersistentDiskVersionedDatabase.open(diskDir)) {
            assertThat(aliceId.isUuid()).isTrue();
            assertThat(bobId.isUuid()).isTrue();
            assertThat(reopened.currentSnapshot().findEntity(aliceId)).isPresent();
            assertThat(reopened.currentSnapshot().findEntity(bobId)).isPresent();
            assertThat(reopened.currentSnapshot().executeQuery(
                    "MATCH (p:Person) SELECT p.hasCode ORDER BY p.hasCode ASC"))
                    .extracting(r -> r.stringValue("p.hasCode").orElseThrow())
                    .containsExactly("A1", "B4");
        }
    }

    @Test
    void exportAndThreeWayMergeVegPatchHandlesCleanAndConflictingGitWorkflows() {
        InMemoryVersionedDatabase originDb = new InMemoryVersionedDatabase();
        originDb.setIdGenerationStrategy(IdGenerationStrategy.UUID_V7);

        EntityId aliceObj;
        EntityId roleVal;
        CommitId cBase;
        try (GraphTransaction tx = originDb.beginTransaction(Actor.user("lead"), Reason.of("base schema + alice"))) {
            EntityId person = tx.defineObjectType("Person");
            EntityId nameType = tx.defineValueType("Name", ValueType.STRING);
            EntityId roleType = tx.defineValueType("Role", ValueType.STRING);
            EntityId hasName = tx.definePredicate("hasName", person, nameType, PredicateConstraints.manyToOne());
            EntityId hasRole = tx.definePredicate("hasRole", person, roleType, PredicateConstraints.manyToOne());

            aliceObj = tx.createObject(person);
            tx.connect(aliceObj, hasName, tx.createValue(nameType, Value.of("Alice")));
            roleVal = tx.createValue(roleType, Value.of("Engineer"), ValueMutability.SHARED_MUTABLE);
            tx.connect(aliceObj, hasRole, roleVal);
            cBase = tx.commit();
        }

        // Simulate contributor branch from cBase exporting a clean Git patch (adds a new UUID object + new schema predicate)
        originDb.createBranch("contrib/bob", cBase);
        CommitId cContrib;
        EntityId bobUuid;
        try (GraphTransaction tx = originDb.beginTransaction("contrib/bob", Actor.user("bob"), Reason.of("add bob + bio"), null, io.veg.model.DataCategory.REFERENCE)) {
            EntityId person = tx.findTypeByName("Person").orElseThrow();
            EntityId nameType = tx.findTypeByName("Name").orElseThrow();
            EntityId hasName = tx.findPredicateByName("hasName").orElseThrow();
            EntityId bioType = tx.defineValueType("Bio", ValueType.STRING);
            EntityId hasBio = tx.definePredicate("hasBio", person, bioType, PredicateConstraints.manyToOne());

            bobUuid = tx.createObject(person);
            tx.connect(bobUuid, hasName, tx.createValue(nameType, Value.of("Bob")));
            tx.connect(bobUuid, hasBio, tx.createValue(bioType, Value.of("Graph Engineer")));
            cContrib = tx.commit();
        }

        String patchText = VegPatchService.exportPatch(originDb, cBase, cContrib);
        assertThat(patchText)
                .contains("VEG-PATCH v1")
                .contains("patch-id: sha256:")
                .contains("base-commit: " + cBase)
                .contains("+VALUE_TYPE")
                .contains("+PREDICATE")
                .contains("+OBJECT " + bobUuid + " type=@Type:Person")
                .contains("-[@Pred:hasBio]->");

        // Advance `main` concurrently without conflicting with Bob's addition (adds Carol)
        EntityId carolUuid;
        try (GraphTransaction tx = originDb.beginTransaction("main", Actor.user("lead"), Reason.of("add carol on main"))) {
            EntityId person = tx.findTypeByName("Person").orElseThrow();
            EntityId nameType = tx.findTypeByName("Name").orElseThrow();
            EntityId hasName = tx.findPredicateByName("hasName").orElseThrow();
            carolUuid = tx.createObject(person);
            tx.connect(carolUuid, hasName, tx.createValue(nameType, Value.of("Carol")));
            tx.commit();
        }

        // Remove temporary contrib branch & create a clean clone at cBase? Better: create an independent target DB with same cBase + Carol commit
        // Or merge patch directly onto `main` of a fresh branch `main-target` whose history is [cBase -> cCarol] (so no contrib/bob exists!)
        InMemoryVersionedDatabase targetDb = new InMemoryVersionedDatabase();
        String baseExport = VegPatchService.exportPatch(originDb, CommitId.BOOTSTRAP, cBase);
        PatchResult seedRes = VegPatchService.applyOrMergePatch(targetDb, baseExport, "main", PatchApplyMode.APPLY, null, null);
        assertThat(seedRes.merged()).isTrue();

        // Now advance targetDb's `main` concurrently (adding Carol) while `cBase` = seedRes.resultCommitId()
        try (GraphTransaction tx = targetDb.beginTransaction("main", Actor.user("lead"), Reason.of("local commit carol"))) {
            EntityId person = tx.findTypeByName("Person").orElseThrow();
            EntityId nameType = tx.findTypeByName("Name").orElseThrow();
            EntityId hasName = tx.findPredicateByName("hasName").orElseThrow();
            EntityId localCarol = tx.createObject(person);
            tx.connect(localCarol, hasName, tx.createValue(nameType, Value.of("Carol")));
            tx.commit();
        }

        // Merge Bob's .vegpatch (whose base-commit is c1 = seedRes.resultCommitId()) via 3-way merge onto targetDb `main`
        PatchResult mergeRes = VegPatchService.applyOrMergePatch(targetDb, patchText, "main", PatchApplyMode.MERGE, null, null);
        assertThat(mergeRes.merged()).isTrue();
        assertThat(mergeRes.alreadyApplied()).isFalse();
        assertThat(mergeRes.conflicts()).isEmpty();
        assertThat(targetDb.getCommit(mergeRes.resultCommitId()).parentIds()).hasSize(2);
        assertThat(targetDb.currentSnapshot().findEntity(bobUuid)).isPresent();
        assertThat(targetDb.currentSnapshot().executeQuery(
                "MATCH (p:Person) SELECT p.hasName ORDER BY p.hasName ASC"))
                .extracting(r -> r.stringValue("p.hasName").orElseThrow())
                .containsExactly("Alice", "Bob", "Carol");

        // Re-applying the same patch is cleanly idempotent
        PatchResult repeatRes = VegPatchService.applyOrMergePatch(targetDb, patchText, "main", PatchApplyMode.MERGE, null, null);
        assertThat(repeatRes.alreadyApplied()).isTrue();
        assertThat(repeatRes.merged()).isTrue();

        // Now test a conflicting 3-way patch merge: both local main and incoming PR modify Alice's `hasRole` (maxOutgoing=1)
        CommitId divergeBase = targetDb.headCommitId();
        targetDb.createBranch("pr/role-update", divergeBase);
        CommitId prCommit;
        try (GraphTransaction tx = targetDb.beginTransaction("pr/role-update", Actor.user("pr-author"), Reason.of("promote to principal"))) {
            EntityId hasRole = tx.findPredicateByName("hasRole").orElseThrow();
            EntityId roleType = tx.findTypeByName("Role").orElseThrow();
            tx.disconnect(tx.findOutgoingEdges(aliceObj, hasRole).getFirst().id());
            tx.connect(aliceObj, hasRole, tx.createValue(roleType, Value.of("Principal Engineer")));
            prCommit = tx.commit();
        }
        String conflictingPatch = VegPatchService.exportPatch(targetDb, divergeBase, prCommit);

        // Local main independently sets Alice's role to Staff Engineer
        try (GraphTransaction tx = targetDb.beginTransaction("main", Actor.user("local-lead"), Reason.of("promote locally"))) {
            EntityId hasRole = tx.findPredicateByName("hasRole").orElseThrow();
            EntityId roleType = tx.findTypeByName("Role").orElseThrow();
            tx.disconnect(tx.findOutgoingEdges(aliceObj, hasRole).getFirst().id());
            tx.connect(aliceObj, hasRole, tx.createValue(roleType, Value.of("Staff Engineer")));
            tx.commit();
        }

        // Build clean receiver DB copy or replay? Let's apply conflictingPatch on a DB without `pr/role-update` branch:
        // Wait: targetDb has `pr/role-update` already, whereas applying on a DB snapshot clone tests fresh patch branch creation!
        // Notice VegPatchService uses `patch/<shortHash>`, NOT `pr/role-update`, so it creates `patch/...` directly from divergeBase!
        PatchResult conflictRes = VegPatchService.applyOrMergePatch(
                targetDb, conflictingPatch, "main", PatchApplyMode.MERGE, null, null);
        assertThat(conflictRes.merged()).isFalse();
        assertThat(conflictRes.conflicts())
                .isNotEmpty()
                .anySatisfy(c -> assertThat(c.type()).isEqualTo(MergeConflictType.CONCURRENT_FUNCTIONAL_ATTRIBUTE));
        assertThat(targetDb.currentSnapshot().executeQuery(
                "MATCH (p:Person) WHERE p.hasName = \"Alice\" SELECT p.hasRole"))
                .extracting(r -> r.stringValue("p.hasRole").orElseThrow())
                .containsExactly("Staff Engineer");
    }
}
