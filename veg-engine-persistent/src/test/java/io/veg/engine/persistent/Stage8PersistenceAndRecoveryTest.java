package io.veg.engine.persistent;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.GraphDiff;
import io.veg.model.MergeResult;
import io.veg.model.Permission;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive verification suite for Stage 8 requirements ({@code P-06}, {@code P-07}, {@code P-08}, {@code T17}).
 */
class Stage8PersistenceAndRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void p06AndP08_cleanRestartReconstructsMultiBranchEntitiesEdgesValuesTagsAndAcls() {
        Path dbDir = tempDir.resolve("clean-restart");
        EntityId personType;
        EntityId companyType;
        EntityId nameType;
        EntityId salaryType;
        EntityId activeType;
        EntityId avatarType;
        EntityId hiredAtType;
        EntityId namePred;
        EntityId salaryPred;
        EntityId activePred;
        EntityId avatarPred;
        EntityId hiredAtPred;
        EntityId worksAtPred;
        EntityId alice;
        EntityId bob;
        EntityId acme;
        CommitId c1;
        CommitId cMain;
        CommitId cFeature;

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("admin"), Reason.of("schema & seed"))) {
                personType = tx.defineObjectType("Person");
                companyType = tx.defineObjectType("Company");
                nameType = tx.defineValueType("Name", ValueType.STRING);
                salaryType = tx.defineValueType("Salary", ValueType.DECIMAL);
                activeType = tx.defineValueType("Active", ValueType.BOOLEAN);
                avatarType = tx.defineValueType("Avatar", ValueType.BINARY);
                hiredAtType = tx.defineValueType("HiredAt", ValueType.TIMESTAMP);

                namePred = tx.definePredicate("name", personType, nameType);
                salaryPred = tx.definePredicate("salary", personType, salaryType);
                activePred = tx.definePredicate("active", personType, activeType);
                avatarPred = tx.definePredicate("avatar", personType, avatarType);
                hiredAtPred = tx.definePredicate("hiredAt", personType, hiredAtType);
                worksAtPred = tx.definePredicate("worksAt", personType, companyType);

                EntityId alicePrincipal = tx.definePrincipal("alice");
                tx.grantPermission(alicePrincipal, Permission.READ, personType);

                alice = tx.createObject(personType);
                acme = tx.createObject(companyType);
                tx.connect(alice, namePred, tx.createValue(nameType, Value.of("Alice"), ValueMutability.SHARED_MUTABLE));
                tx.connect(alice, salaryPred, tx.createValue(salaryType, Value.of(new BigDecimal("95000.50"))));
                tx.connect(alice, activePred, tx.createValue(activeType, Value.of(true)));
                tx.connect(alice, avatarPred, tx.createValue(avatarType, Value.of(new byte[]{1, 2, 3, 4})));
                tx.connect(alice, hiredAtPred, tx.createValue(hiredAtType, Value.of(Instant.parse("2025-05-01T10:15:30Z"))));
                tx.connect(alice, worksAtPred, acme);

                c1 = tx.commit();
            }
            db.createTag("v1.0", c1);

            db.createBranch("feature/hr", c1);
            db.restrictBranchReadAccess("feature/hr", Set.of("alice"));
            db.checkoutBranch("feature/hr");

            try (GraphTransaction tx = db.beginTransaction(Actor.user("alice"), Reason.of("hire bob"))) {
                bob = tx.createObject(personType);
                tx.connect(bob, namePred, tx.createValue(nameType, Value.of("Bob")));
                tx.connect(bob, worksAtPred, acme);
                cFeature = tx.commit();
            }

            db.checkoutBranch("main");
            MergeResult merged = db.merge("feature/hr", "main", Actor.user("admin"), Reason.of("merge hr"));
            assertThat(merged.succeeded()).isTrue();
            cMain = db.headCommitId();
        }

        // Reopen from disk in a brand-new database instance and validate complete fidelity + continuing ID allocation
        try (PersistentDiskVersionedDatabase reopened = PersistentDiskVersionedDatabase.open(dbDir)) {
            assertThat(reopened.currentBranch()).isEqualTo("main");
            assertThat(reopened.headCommitId()).isEqualTo(cMain);
            assertThat(reopened.branchHead("feature/hr")).isEqualTo(cFeature);
            assertThat(reopened.findTag("v1.0")).isPresent();
            assertThat(reopened.findTag("v1.0").get().commitId()).isEqualTo(c1);

            List<io.veg.model.QueryRow> rows = reopened.currentSnapshot().executeQuery(
                    "MATCH (p:Person) SELECT p.name, p.salary, p.active");
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().map(r -> r.stringValue("p.name").orElseThrow()).toList())
                    .containsExactlyInAnyOrder("Alice", "Bob");

            EntityNode recoveredAvatar = reopened.asOf(c1)
                    .findOutgoingEdges(alice, avatarPred)
                    .stream()
                    .map(e -> reopened.asOf(c1).findEntity(e.targetId()).orElseThrow())
                    .findFirst()
                    .orElseThrow();
            assertThat(recoveredAvatar.payload()).contains(Value.of(new byte[]{1, 2, 3, 4}));

            // Branch read restriction survived restart
            AuthorizedGraphReader hrAllowed = reopened.authorizedBranchSnapshot("feature/hr", "alice");
            assertThat(hrAllowed.findEntity(bob)).isPresent();
            AuthorizedGraphReader hrDenied = reopened.authorizedBranchSnapshot("feature/hr", "outsider");
            assertThat(hrDenied.findEntity(bob)).isEmpty();

            // Next allocated user entity ID must not collide with any existing entity ID
            try (GraphTransaction tx = reopened.beginTransaction(Actor.user("admin"), Reason.of("post-restart"))) {
                EntityId carol = tx.createObject(personType);
                assertThat(carol.value()).isGreaterThan(Math.max(alice.value(), Math.max(bob.value(), acme.value())));
                tx.commit();
            }
        }
    }

    @Test
    void p06_periodicCheckpointsAccelerateRestartReplay() throws IOException {
        Path dbDir = tempDir.resolve("checkpoints-db");
        EntityId itemType;
        EntityId labelType;
        EntityId labelPred;

        try (PersistentDiskVersionedDatabase db =
                     PersistentDiskVersionedDatabase.open(dbDir, DurabilityMode.FLUSH_OS_BUFFER, 10)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("schema"))) {
                itemType = tx.defineObjectType("Item");
                labelType = tx.defineValueType("Label", ValueType.STRING);
                labelPred = tx.definePredicate("label", itemType, labelType);
                tx.commit();
            }
            for (int i = 1; i <= 25; i++) {
                try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("item-" + i))) {
                    EntityId item = tx.createObject(itemType);
                    tx.connect(item, labelPred, tx.createValue(labelType, Value.of("Item-" + i)));
                    tx.commit();
                }
            }
        }

        try (Stream<Path> ckpts = Files.list(dbDir.resolve("checkpoints"))) {
            assertThat(ckpts.filter(p -> p.getFileName().toString().endsWith(".bin.gz")).count())
                    .isGreaterThanOrEqualTo(2);
        }

        try (PersistentDiskVersionedDatabase reopened =
                     PersistentDiskVersionedDatabase.open(dbDir, DurabilityMode.FLUSH_OS_BUFFER, 10)) {
            RecoveryReport report = reopened.lastRecoveryReport();
            assertThat(report.loadedCheckpointId()).isPresent();
            assertThat(report.replayedWalCommits()).isLessThan(26);
            assertThat(reopened.commitHistory()).hasSize(27); // c0 + schema + 25 items
            assertThat(reopened.asOf(new CommitId(5)).entityCount())
                    .isEqualTo(reopened.replayAsOf(new CommitId(5)).entityCount());
        }
    }

    @Test
    void t17_crashMidWalFrameWriteTruncatesPartialTailAndPreservesPreviousCommits() {
        Path dbDir = tempDir.resolve("crash-mid-wal");
        EntityId nodeType;
        CommitId c1;
        CommitId c2;

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c1"))) {
                nodeType = tx.defineObjectType("Node");
                c1 = tx.commit();
            }
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c2"))) {
                tx.createObject(nodeType);
                c2 = tx.commit();
            }
            db.setCrashPoint(CrashPoint.MID_WAL_FRAME_WRITE);
            assertThatThrownBy(() -> {
                try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c3-crash"))) {
                    tx.createObject(nodeType);
                    tx.commit();
                }
            }).isInstanceOf(SimulatedCrashException.class);
        }

        try (PersistentDiskVersionedDatabase recovered = PersistentDiskVersionedDatabase.open(dbDir)) {
            RecoveryReport report = recovered.lastRecoveryReport();
            assertThat(report.truncatedTailFrames()).isEqualTo(1);
            assertThat(recovered.headCommitId()).isEqualTo(c2);
            assertThat(recovered.commitHistory()).hasSize(3); // c0, c1, c2

            // Subsequent writes proceed cleanly after WAL tail truncation
            try (GraphTransaction tx = recovered.beginTransaction(Actor.user("u"), Reason.of("c3-clean"))) {
                tx.createObject(nodeType);
                CommitId c3 = tx.commit();
                assertThat(c3).isEqualTo(c2.next());
            }
        }
    }

    @Test
    void t17_crashWithCorruptedWalTailCrcTruncatesDamagedTailAndRecovers() {
        Path dbDir = tempDir.resolve("crash-bad-tail-crc");
        CommitId c1;

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            EntityId itemType;
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c1"))) {
                itemType = tx.defineObjectType("Thing");
                c1 = tx.commit();
            }
            db.setCrashPoint(CrashPoint.CORRUPTED_WAL_TAIL_CRC);
            assertThatThrownBy(() -> {
                try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c2-bad-crc"))) {
                    tx.createObject(itemType);
                    tx.commit();
                }
            }).isInstanceOf(SimulatedCrashException.class);
        }

        try (PersistentDiskVersionedDatabase recovered = PersistentDiskVersionedDatabase.open(dbDir)) {
            assertThat(recovered.lastRecoveryReport().truncatedTailFrames()).isEqualTo(1);
            assertThat(recovered.headCommitId()).isEqualTo(c1);
        }
    }

    @Test
    void t17_crashAfterWalBeforeRefsUpdateRecoversUnindexedCommitAndAdvancesBranchHead() {
        Path dbDir = tempDir.resolve("crash-before-refs");
        EntityId thingType;
        CommitId c1;
        EntityId bobId;

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c1"))) {
                thingType = tx.defineObjectType("User");
                c1 = tx.commit();
            }
            db.setCrashPoint(CrashPoint.AFTER_WAL_BEFORE_REFS_UPDATE);
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c2-committed-to-wal"))) {
                bobId = tx.createObject(thingType);
                assertThatThrownBy(tx::commit)
                        .isInstanceOf(SimulatedCrashException.class);
            }
        }

        try (PersistentDiskVersionedDatabase recovered = PersistentDiskVersionedDatabase.open(dbDir)) {
            RecoveryReport report = recovered.lastRecoveryReport();
            assertThat(report.recoveredUnindexedCommits()).isEqualTo(1);
            assertThat(recovered.headCommitId()).isEqualTo(c1.next());
            assertThat(recovered.currentSnapshot().findEntity(bobId)).isPresent();
        }
    }

    @Test
    void t17_crashMidCheckpointWriteCleansOrphanedTempFileAndRecoversFromWal() {
        Path dbDir = tempDir.resolve("crash-mid-ckpt");
        CommitId c1;

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("c1"))) {
                tx.defineObjectType("Doc");
                c1 = tx.commit();
            }
            db.setCrashPoint(CrashPoint.MID_CHECKPOINT_WRITE);
            assertThatThrownBy(db::createCheckpoint)
                    .isInstanceOf(SimulatedCrashException.class);
        }

        try (PersistentDiskVersionedDatabase recovered = PersistentDiskVersionedDatabase.open(dbDir)) {
            RecoveryReport report = recovered.lastRecoveryReport();
            assertThat(report.cleanedTempFiles()).isGreaterThanOrEqualTo(1);
            assertThat(recovered.headCommitId()).isEqualTo(c1);
        }
    }

    @Test
    void p08_corruptedInteriorWalFrameThrowsStorageCorruptionException() throws IOException {
        Path dbDir = tempDir.resolve("corrupt-interior-wal");
        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            EntityId type;
            try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("c1"))) {
                type = tx.defineObjectType("Note");
                tx.commit();
            }
            try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("c2"))) {
                tx.createObject(type);
                tx.commit();
            }
        }

        // Flip a byte inside the first WAL frame (interior frame, far before the final frame)
        Path walPath = dbDir.resolve("wal.log");
        byte[] bytes = Files.readAllBytes(walPath);
        bytes[20] ^= 0x5A;
        Files.write(walPath, bytes);

        assertThatThrownBy(() -> PersistentDiskVersionedDatabase.open(dbDir))
                .isInstanceOf(StorageCorruptionException.class);
    }

    @Test
    void p08_corruptedCheckpointFileAndRefsMetaThrowStorageCorruptionException() throws IOException {
        Path dbDir = tempDir.resolve("corrupt-ckpt-and-refs");
        CommitId c1;
        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.system(), Reason.of("c1"))) {
                tx.defineObjectType("Event");
                c1 = tx.commit();
            }
            db.createCheckpoint();
        }

        // Corrupt checkpoint file
        Path ckptPath = dbDir.resolve("checkpoints").resolve("ckpt-" + c1.value() + ".bin.gz");
        byte[] ckptBytes = Files.readAllBytes(ckptPath);
        byte[] corruptedCkpt = ckptBytes.clone();
        corruptedCkpt[corruptedCkpt.length / 2] ^= 0x33;
        Files.write(ckptPath, corruptedCkpt);

        assertThatThrownBy(() -> PersistentDiskVersionedDatabase.open(dbDir))
                .isInstanceOf(StorageCorruptionException.class);

        // Restore valid checkpoint, then corrupt refs.meta
        Files.write(ckptPath, ckptBytes);
        Path refsPath = dbDir.resolve("refs.meta");
        byte[] refsBytes = Files.readAllBytes(refsPath);
        refsBytes[refsBytes.length - 5] ^= 0x7F;
        Files.write(refsPath, refsBytes);

        assertThatThrownBy(() -> PersistentDiskVersionedDatabase.open(dbDir))
                .isInstanceOf(StorageCorruptionException.class);
    }

    @Test
    void p07_coldArchivingCompactsWalEvictsHotSnapshotsAndSupportsTransparentHistoricalQueries() throws IOException {
        Path dbDir = tempDir.resolve("cold-archive");
        EntityId taskType;
        EntityId titleType;
        EntityId titlePred;
        long walSizeBeforeArchive;

        try (PersistentDiskVersionedDatabase db =
                     PersistentDiskVersionedDatabase.open(dbDir, DurabilityMode.FLUSH_OS_BUFFER, 100)) {
            try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("schema"))) {
                taskType = tx.defineObjectType("Task");
                titleType = tx.defineValueType("Title", ValueType.STRING);
                titlePred = tx.definePredicate("title", taskType, titleType);
                tx.commit();
            }
            for (int i = 2; i <= 12; i++) {
                try (GraphTransaction tx = db.beginTransaction(Actor.user("u"), Reason.of("task-" + i))) {
                    EntityId t = tx.createObject(taskType);
                    tx.connect(t, titlePred, tx.createValue(titleType, Value.of("Task #" + i)));
                    tx.commit();
                }
            }
            walSizeBeforeArchive = Files.size(dbDir.resolve("wal.log"));
            int hotBefore = db.hotSnapshotCount();
            CommitId cutoff = new CommitId(8);
            db.archiveHistoryBefore(cutoff);

            assertThat(db.coldArchiveSegmentCount()).isEqualTo(1);
            assertThat(db.hotSnapshotCount()).isLessThan(hotBefore);
            assertThat(Files.size(dbDir.resolve("wal.log"))).isLessThan(walSizeBeforeArchive);

            // Historical queries against evicted commits < c8 must transparently work
            assertThat(db.asOf(new CommitId(3)).allEntities()).isEqualTo(db.replayAsOf(new CommitId(3)).allEntities());
            GraphDiff diff = db.diff(new CommitId(2), new CommitId(10));
            assertThat(diff.addedEntities()).isNotEmpty();
        }

        // Restarting after cold archiving must load checkpoint + compacted WAL + support queries on archived commits
        try (PersistentDiskVersionedDatabase reopened =
                     PersistentDiskVersionedDatabase.open(dbDir, DurabilityMode.FLUSH_OS_BUFFER, 100)) {
            assertThat(reopened.commitHistory()).hasSize(13);
            assertThat(reopened.coldArchiveSegmentCount()).isEqualTo(1);
            assertThat(reopened.lastRecoveryReport().loadedCheckpointId()).contains(new CommitId(8));
            assertThat(reopened.lastRecoveryReport().replayedWalCommits()).isEqualTo(4); // c9..c12
            assertThat(reopened.asOf(new CommitId(4)).allEntities())
                    .isEqualTo(reopened.replayAsOf(new CommitId(4)).allEntities());
        }

        // Corrupting the cold archive segment must be detected via CRC32C on open (P-08)
        Path archiveSeg = dbDir.resolve("archive").resolve("segment-0-8.wal.gz");
        byte[] segBytes = Files.readAllBytes(archiveSeg);
        segBytes[segBytes.length - 4] ^= 0x1F;
        Files.write(archiveSeg, segBytes);
        assertThatThrownBy(() -> PersistentDiskVersionedDatabase.open(dbDir))
                .isInstanceOf(StorageCorruptionException.class);
    }

    @Test
    void p07_backupAndRestoreCreatesIndependentVerifiedDatabaseCopy() {
        Path sourceDir = tempDir.resolve("backup-source");
        Path backupDir = tempDir.resolve("backup-copy");
        Path restoredDir = tempDir.resolve("restored-copy");
        EntityId bookType;
        CommitId sourceHead;

        try (PersistentDiskVersionedDatabase sourceDb = PersistentDiskVersionedDatabase.open(sourceDir)) {
            try (GraphTransaction tx = sourceDb.beginTransaction(Actor.user("lib"), Reason.of("books"))) {
                bookType = tx.defineObjectType("Book");
                EntityId titleType = tx.defineValueType("BookTitle", ValueType.STRING);
                EntityId titlePred = tx.definePredicate("bookTitle", bookType, titleType);
                EntityId b = tx.createObject(bookType);
                tx.connect(b, titlePred, tx.createValue(titleType, Value.of("Graph Theory")));
                tx.commit();
            }
            for (int i = 0; i < 4; i++) {
                try (GraphTransaction tx = sourceDb.beginTransaction(Actor.user("lib"), Reason.of("add-" + i))) {
                    tx.createObject(bookType);
                    tx.commit();
                }
            }
            sourceHead = sourceDb.headCommitId();
            sourceDb.archiveHistoryBefore(new CommitId(3));
            sourceDb.backupTo(backupDir);
        }

        PersistentDiskVersionedDatabase.restoreFrom(backupDir, restoredDir);

        try (PersistentDiskVersionedDatabase restoredDb = PersistentDiskVersionedDatabase.open(restoredDir)) {
            assertThat(restoredDb.headCommitId()).isEqualTo(sourceHead);
            assertThat(restoredDb.commitHistory()).hasSize(6);
            assertThat(restoredDb.asOf(new CommitId(2)).allEntities())
                    .isEqualTo(restoredDb.replayAsOf(new CommitId(2)).allEntities());
        }
    }
}
