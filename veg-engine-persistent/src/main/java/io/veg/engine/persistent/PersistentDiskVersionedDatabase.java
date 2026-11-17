package io.veg.engine.persistent;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.CommitNotFoundException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphSession;
import io.veg.engine.GraphTransaction;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.engine.memory.PersistentGraphSnapshot;
import io.veg.engine.memory.PersistentTreeVersionedDatabase;
import io.veg.model.Actor;
import io.veg.model.Branch;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.EntityId;
import io.veg.model.GraphDiff;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.Tag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Durable, disk-backed {@link VersionedGraphDatabase} built on top of Engine C's structurally shared
 * persistent radix-32 tree ({@link PersistentTreeVersionedDatabase}) with:
 * <ul>
 *     <li>Append-only framed Write-Ahead Log ({@code wal.log}) protected by {@code CRC32C} ({@code P-06}, {@code P-08})</li>
 *     <li>Atomic branch/tag/ACL/allocator root metadata ({@code refs.meta})</li>
 *     <li>Periodic GZIP-compressed checkpoints ({@code checkpoints/ckpt-*.bin.gz})</li>
 *     <li>Non-destructive WAL compaction and cold archiving ({@code archive/segment-*.wal.gz}) with
 *         transparent historical LRU caching ({@code P-07})</li>
 *     <li>Deterministic crash recovery and checksum verification ({@code T17})</li>
 * </ul>
 */
public final class PersistentDiskVersionedDatabase implements VersionedGraphDatabase, AutoCloseable {

    private static final int DEFAULT_CHECKPOINT_INTERVAL = 25;
    private static final int DEFAULT_COLD_CACHE_SIZE = 16;

    private final Path dataDir;
    private final DurabilityMode durabilityMode;
    private final int checkpointInterval;
    private final WriteAheadLogStore walStore;
    private final CheckpointAndArchiveStore metaStore;
    private final PersistentTreeVersionedDatabase delegate;
    private final RecoveryReport recoveryReport;
    private final Map<CommitId, PersistentGraphSnapshot> coldCache;

    private Optional<CommitId> latestCheckpointId;
    private Optional<CommitId> archivedUpToCommitId;
    private CrashPoint crashPoint = CrashPoint.NONE;
    private int commitsSinceLastCheckpoint;

    private PersistentDiskVersionedDatabase(
            Path dataDir,
            DurabilityMode durabilityMode,
            int checkpointInterval,
            int coldCacheSize,
            WriteAheadLogStore walStore,
            CheckpointAndArchiveStore metaStore,
            PersistentTreeVersionedDatabase delegate,
            Optional<CommitId> latestCheckpointId,
            Optional<CommitId> archivedUpToCommitId,
            RecoveryReport recoveryReport
    ) {
        this.dataDir = dataDir;
        this.durabilityMode = durabilityMode;
        this.checkpointInterval = checkpointInterval;
        this.walStore = walStore;
        this.metaStore = metaStore;
        this.delegate = delegate;
        this.latestCheckpointId = latestCheckpointId;
        this.archivedUpToCommitId = archivedUpToCommitId;
        this.recoveryReport = recoveryReport;
        this.commitsSinceLastCheckpoint = 0;
        this.coldCache = Collections.synchronizedMap(new LinkedHashMap<>(coldCacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CommitId, PersistentGraphSnapshot> eldest) {
                return size() > coldCacheSize;
            }
        });

        this.delegate.setColdSnapshotLoader(this::loadHistoricalSnapshotOnDemand);
        this.delegate.setPersistenceObserver(new DiskPersistenceObserver());
    }

    public static PersistentDiskVersionedDatabase open(Path dataDir) {
        return open(dataDir, DurabilityMode.FSYNC_EVERY_COMMIT, DEFAULT_CHECKPOINT_INTERVAL, DEFAULT_COLD_CACHE_SIZE);
    }

    public static PersistentDiskVersionedDatabase open(
            Path dataDir,
            DurabilityMode durabilityMode,
            int checkpointInterval
    ) {
        return open(dataDir, durabilityMode, checkpointInterval, DEFAULT_COLD_CACHE_SIZE);
    }

    public static PersistentDiskVersionedDatabase open(
            Path dataDir,
            DurabilityMode durabilityMode,
            int checkpointInterval,
            int coldCacheSize
    ) {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        Objects.requireNonNull(durabilityMode, "durabilityMode must not be null");
        if (checkpointInterval <= 0) {
            throw new IllegalArgumentException("checkpointInterval must be positive, was: " + checkpointInterval);
        }
        if (coldCacheSize <= 0) {
            throw new IllegalArgumentException("coldCacheSize must be positive, was: " + coldCacheSize);
        }

        WriteAheadLogStore walStore = null;
        try {
            CheckpointAndArchiveStore metaStore = new CheckpointAndArchiveStore(dataDir);
            int cleanedTmp = metaStore.cleanupTempFiles();

            walStore = WriteAheadLogStore.open(dataDir.resolve("wal.log"));
            Optional<BinaryGraphCodec.RefsMetadata> optRefs = metaStore.readRefsMeta();
            List<Commit> archivedCommits = metaStore.readAllArchivedCommits();
            WriteAheadLogStore.WalRecoveryResult walResult = walStore.readAndRecover(true);

            boolean isFreshDatabase = optRefs.isEmpty()
                    && archivedCommits.isEmpty()
                    && walResult.commits().isEmpty();

            if (isFreshDatabase) {
                return initializeFreshDatabase(
                        dataDir, durabilityMode, checkpointInterval, coldCacheSize, walStore, metaStore, cleanedTmp);
            }

            return recoverExistingDatabase(
                    dataDir,
                    durabilityMode,
                    checkpointInterval,
                    coldCacheSize,
                    walStore,
                    metaStore,
                    optRefs,
                    archivedCommits,
                    walResult,
                    cleanedTmp
            );
        } catch (IOException ex) {
            closeQuietly(walStore);
            throw new UncheckedIOException("Failed to open persistent VEG database at " + dataDir, ex);
        } catch (RuntimeException ex) {
            closeQuietly(walStore);
            throw ex;
        }
    }

    public static void restoreFrom(Path backupDir, Path targetDataDir) {
        Objects.requireNonNull(backupDir, "backupDir must not be null");
        Objects.requireNonNull(targetDataDir, "targetDataDir must not be null");
        if (!Files.isDirectory(backupDir)) {
            throw new IllegalArgumentException("Backup directory does not exist: " + backupDir);
        }
        if (!Files.exists(backupDir.resolve("refs.meta")) && !Files.exists(backupDir.resolve("wal.log"))) {
            throw new IllegalArgumentException("Directory does not contain a valid VEG backup: " + backupDir);
        }
        try {
            copyDirectoryContents(backupDir, targetDataDir);
            // Verify full checksum and WAL/checkpoint integrity immediately upon restore.
            try (PersistentDiskVersionedDatabase verified = open(targetDataDir)) {
                verified.headCommitId();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to restore VEG database from " + backupDir, ex);
        }
    }

    public RecoveryReport lastRecoveryReport() {
        return recoveryReport;
    }

    public void setCrashPoint(CrashPoint crashPoint) {
        this.crashPoint = Objects.requireNonNull(crashPoint, "crashPoint must not be null");
    }

    public synchronized void createCheckpoint() {
        createCheckpointAt(headCommitId());
    }

    public synchronized void createCheckpointAt(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        delegate.getCommit(commitId);
        PersistentGraphSnapshot snapshot = delegate.snapshotForCommit(commitId);
        CrashPoint activeCrash = crashPoint;
        if (activeCrash == CrashPoint.MID_CHECKPOINT_WRITE) {
            crashPoint = CrashPoint.NONE;
        }
        try {
            metaStore.writeCheckpoint(commitId, snapshot, durabilityMode, activeCrash);
            if (latestCheckpointId.isEmpty() || commitId.compareTo(latestCheckpointId.get()) > 0) {
                latestCheckpointId = Optional.of(commitId);
            }
            persistRefsMeta();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create checkpoint at " + commitId, ex);
        }
    }

    public synchronized void archiveHistoryBefore(CommitId cutoffCommitId) {
        Objects.requireNonNull(cutoffCommitId, "cutoffCommitId must not be null");
        delegate.getCommit(cutoffCommitId);
        if (cutoffCommitId.isBootstrap()) {
            throw new IllegalArgumentException("Cannot archive before bootstrap commit " + cutoffCommitId);
        }

        long nextFrom = archivedUpToCommitId.map(c -> c.value() + 1L).orElse(0L);
        long cutoffVal = cutoffCommitId.value();
        if (cutoffVal < nextFrom) {
            return;
        }

        try {
            List<Commit> allCommits = delegate.commitHistory();
            List<Commit> toArchive = new ArrayList<>();
            for (long id = nextFrom; id <= cutoffVal; id++) {
                toArchive.add(allCommits.get((int) id));
            }
            metaStore.writeArchiveSegment(nextFrom, cutoffVal, toArchive, durabilityMode);

            if (!metaStore.hasCheckpoint(cutoffCommitId)) {
                PersistentGraphSnapshot cutoffSnapshot = delegate.snapshotForCommit(cutoffCommitId);
                metaStore.writeCheckpoint(cutoffCommitId, cutoffSnapshot, durabilityMode, CrashPoint.NONE);
            }
            if (latestCheckpointId.isEmpty() || cutoffCommitId.compareTo(latestCheckpointId.get()) > 0) {
                latestCheckpointId = Optional.of(cutoffCommitId);
            }
            archivedUpToCommitId = Optional.of(cutoffCommitId);
            persistRefsMeta();

            List<Commit> remainingWalCommits = new ArrayList<>();
            for (Commit c : allCommits) {
                if (c.id().value() > cutoffVal) {
                    remainingWalCommits.add(c);
                }
            }
            walStore.rewrite(remainingWalCommits, durabilityMode);

            Set<CommitId> protectedHeads = new HashSet<>();
            protectedHeads.add(cutoffCommitId);
            for (Branch branch : delegate.listBranches()) {
                protectedHeads.add(branch.headCommitId());
            }
            for (long id = 0L; id < cutoffVal; id++) {
                CommitId cid = new CommitId(id);
                if (!protectedHeads.contains(cid)) {
                    delegate.evictHotSnapshot(cid);
                    coldCache.remove(cid);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to archive history before " + cutoffCommitId, ex);
        }
    }

    public synchronized void backupTo(Path backupDir) {
        Objects.requireNonNull(backupDir, "backupDir must not be null");
        if (backupDir.toAbsolutePath().normalize().equals(dataDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Backup directory must differ from database directory: " + backupDir);
        }
        try {
            persistRefsMeta();
            copyDirectoryContents(dataDir, backupDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create backup in " + backupDir, ex);
        }
    }

    public int hotSnapshotCount() {
        return delegate.hotSnapshotCount();
    }

    public int coldArchiveSegmentCount() {
        try {
            return metaStore.listArchiveSegments().size();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to list archive segments", ex);
        }
    }

    public Path dataDirectory() {
        return dataDir;
    }

    // --- VersionedGraphDatabase delegation ---

    @Override
    public String currentBranch() {
        return delegate.currentBranch();
    }

    @Override
    public synchronized void checkoutBranch(String branchName) {
        delegate.checkoutBranch(branchName);
    }

    @Override
    public CommitId headCommitId() {
        return delegate.headCommitId();
    }

    @Override
    public GraphReader currentSnapshot() {
        return delegate.currentSnapshot();
    }

    @Override
    public synchronized Branch createBranch(String branchName, CommitId fromCommitId) {
        return delegate.createBranch(branchName, fromCommitId);
    }

    @Override
    public Optional<Branch> findBranch(String branchName) {
        return delegate.findBranch(branchName);
    }

    @Override
    public List<Branch> listBranches() {
        return delegate.listBranches();
    }

    @Override
    public CommitId branchHead(String branchName) {
        return delegate.branchHead(branchName);
    }

    @Override
    public GraphReader branchSnapshot(String branchName) {
        return delegate.branchSnapshot(branchName);
    }

    @Override
    public synchronized Tag createTag(String tagName, CommitId commitId) {
        return delegate.createTag(tagName, commitId);
    }

    @Override
    public Optional<Tag> findTag(String tagName) {
        return delegate.findTag(tagName);
    }

    @Override
    public List<Tag> listTags() {
        return delegate.listTags();
    }

    @Override
    public GraphReader asOfTag(String tagName) {
        return delegate.asOfTag(tagName);
    }

    @Override
    public GraphReader asOf(CommitId commitId) {
        return delegate.asOf(commitId);
    }

    @Override
    public Commit getCommit(CommitId commitId) {
        return delegate.getCommit(commitId);
    }

    @Override
    public List<Commit> commitHistory() {
        return delegate.commitHistory();
    }

    @Override
    public List<Commit> historyOf(EntityId entityId) {
        return delegate.historyOf(entityId);
    }

    @Override
    public GraphDiff diff(CommitId fromCommit, CommitId toCommit) {
        return delegate.diff(fromCommit, toCommit);
    }

    @Override
    public GraphReader replayAsOf(CommitId commitId) {
        return delegate.replayAsOf(commitId);
    }

    @Override
    public GraphTransaction beginTransaction(
            String branchName,
            Actor actor,
            Reason reason,
            String correlationId,
            DataCategory dataCategory
    ) {
        return delegate.beginTransaction(branchName, actor, reason, correlationId, dataCategory);
    }

    @Override
    public StagingWorkspace createStagingWorkspace(String branchName) {
        return delegate.createStagingWorkspace(branchName);
    }

    @Override
    public synchronized CommitId revert(
            String branchName,
            CommitId targetCommitId,
            Actor actor,
            Reason reason,
            String correlationId
    ) {
        return delegate.revert(branchName, targetCommitId, actor, reason, correlationId);
    }

    @Override
    public CommitId findCommonAncestor(CommitId commitA, CommitId commitB) {
        return delegate.findCommonAncestor(commitA, commitB);
    }

    @Override
    public synchronized MergeResult merge(
            String sourceBranch,
            String targetBranch,
            Actor actor,
            Reason reason,
            MergePolicy policy
    ) {
        return delegate.merge(sourceBranch, targetBranch, actor, reason, policy);
    }

    @Override
    public synchronized CommitId resolveMerge(
            String sourceBranch,
            String targetBranch,
            Actor actor,
            Reason reason,
            Consumer<GraphSession> conflictResolver
    ) {
        return delegate.resolveMerge(sourceBranch, targetBranch, actor, reason, conflictResolver);
    }

    @Override
    public synchronized void restrictBranchReadAccess(String branchName, Set<String> allowedPrincipalIds) {
        delegate.restrictBranchReadAccess(branchName, allowedPrincipalIds);
    }

    @Override
    public AuthorizedGraphReader authorizedCurrentSnapshot(String principalId) {
        return delegate.authorizedCurrentSnapshot(principalId);
    }

    @Override
    public AuthorizedGraphReader authorizedBranchSnapshot(String branchName, String principalId) {
        return delegate.authorizedBranchSnapshot(branchName, principalId);
    }

    @Override
    public AuthorizedGraphReader authorizedAsOf(CommitId commitId, String principalId) {
        return delegate.authorizedAsOf(commitId, principalId);
    }

    @Override
    public CommitId executeIdempotent(
            String branchName,
            String idempotencyKey,
            Actor actor,
            Reason reason,
            String correlationId,
            Consumer<GraphTransaction> work
    ) {
        return delegate.executeIdempotent(branchName, idempotencyKey, actor, reason, correlationId, work);
    }

    @Override
    public synchronized MergeResult cherryPick(CommitId sourceCommitId, String targetBranch, Actor actor, Reason reason) {
        return delegate.cherryPick(sourceCommitId, targetBranch, actor, reason);
    }

    @Override
    public synchronized RedactionReceipt redactEntityPayload(EntityId valueEntityId, Actor actor, Reason reason) {
        return delegate.redactEntityPayload(valueEntityId, actor, reason);
    }

    @Override
    public List<RedactionReceipt> listRedactionReceipts() {
        return delegate.listRedactionReceipts();
    }

    @Override
    public synchronized void close() {
        try {
            walStore.close();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to close WAL store in " + dataDir, ex);
        }
    }

    // --- Internal initialization, recovery, persistence hooks, and cold cache replay ---

    private static PersistentDiskVersionedDatabase initializeFreshDatabase(
            Path dataDir,
            DurabilityMode durabilityMode,
            int checkpointInterval,
            int coldCacheSize,
            WriteAheadLogStore walStore,
            CheckpointAndArchiveStore metaStore,
            int cleanedTmp
    ) throws IOException {
        PersistentTreeVersionedDatabase delegate = new PersistentTreeVersionedDatabase();
        Commit bootstrap = delegate.getCommit(CommitId.BOOTSTRAP);
        walStore.append(bootstrap, durabilityMode, CrashPoint.NONE);

        BinaryGraphCodec.RefsMetadata meta = snapshotMetadataFromDelegate(
                delegate, Optional.empty(), Optional.empty());
        metaStore.writeRefsMeta(meta, durabilityMode);

        RecoveryReport report = new RecoveryReport(0, 0, 0, cleanedTmp, Optional.empty());
        return new PersistentDiskVersionedDatabase(
                dataDir,
                durabilityMode,
                checkpointInterval,
                coldCacheSize,
                walStore,
                metaStore,
                delegate,
                Optional.empty(),
                Optional.empty(),
                report
        );
    }

    private static PersistentDiskVersionedDatabase recoverExistingDatabase(
            Path dataDir,
            DurabilityMode durabilityMode,
            int checkpointInterval,
            int coldCacheSize,
            WriteAheadLogStore walStore,
            CheckpointAndArchiveStore metaStore,
            Optional<BinaryGraphCodec.RefsMetadata> optRefs,
            List<Commit> archivedCommits,
            WriteAheadLogStore.WalRecoveryResult walResult,
            int cleanedTmp
    ) throws IOException {
        List<Commit> unifiedCommits = mergeAndValidateCommits(archivedCommits, walResult.commits());
        if (unifiedCommits.isEmpty()) {
            throw new StorageCorruptionException("Database directory has metadata but zero commits in archive/WAL");
        }

        Optional<CommitId> checkpointToLoad = resolveCheckpointToLoad(optRefs, metaStore);
        Map<CommitId, PersistentGraphSnapshot> hotSnapshots = new HashMap<>();
        long checkpointCommitValue = -1L;

        if (checkpointToLoad.isPresent()) {
            CommitId ckptId = checkpointToLoad.get();
            if (ckptId.value() >= unifiedCommits.size()) {
                throw new StorageCorruptionException(
                        "Checkpoint " + ckptId + " references commit missing from WAL/archive");
            }
            BinaryGraphCodec.DecodedSnapshot decoded = metaStore.readCheckpoint(ckptId);
            hotSnapshots.put(ckptId, decoded.snapshot());
            checkpointCommitValue = ckptId.value();
        }

        int replayedWalCommits = replayPostCheckpointCommits(
                unifiedCommits, hotSnapshots, checkpointCommitValue, walResult.commits(), metaStore);

        Map<String, Branch> branches = new LinkedHashMap<>();
        Map<String, Tag> tags = new LinkedHashMap<>();
        Map<String, Set<String>> restrictions = new LinkedHashMap<>();
        String activeBranch = DEFAULT_BRANCH;
        Optional<CommitId> archivedUpTo = Optional.empty();
        long refsNextEntityId = EntityId.USER_MIN_ID;
        Map<String, CommitId> recoveredIdempotent = Map.of();
        List<RedactionReceipt> recoveredReceipts = List.of();

        if (optRefs.isPresent()) {
            BinaryGraphCodec.RefsMetadata refs = optRefs.get();
            activeBranch = refs.activeBranch();
            branches.putAll(refs.branches());
            tags.putAll(refs.tags());
            restrictions.putAll(refs.branchReadRestrictions());
            archivedUpTo = refs.archivedUpToCommitId();
            refsNextEntityId = refs.nextUserEntityId();
            recoveredIdempotent = refs.idempotentCommits();
            recoveredReceipts = refs.redactionReceipts();
        } else {
            branches.put(DEFAULT_BRANCH, new Branch(DEFAULT_BRANCH, CommitId.BOOTSTRAP));
        }

        int recoveredUnindexed = advanceUnindexedBranches(unifiedCommits, branches);
        ensureBranchHeadsHot(unifiedCommits, branches, hotSnapshots, metaStore);

        long recoveredNextEntityId = Math.max(
                refsNextEntityId, computeMaxUserEntityId(unifiedCommits, hotSnapshots) + 1L);

        PersistentTreeVersionedDatabase delegate = PersistentTreeVersionedDatabase.createRecovered(
                unifiedCommits,
                hotSnapshots,
                branches,
                tags,
                restrictions,
                activeBranch,
                recoveredNextEntityId,
                recoveredIdempotent,
                recoveredReceipts
        );

        RecoveryReport report = new RecoveryReport(
                replayedWalCommits,
                recoveredUnindexed,
                walResult.truncatedTailFrames(),
                cleanedTmp,
                checkpointToLoad
        );

        PersistentDiskVersionedDatabase db = new PersistentDiskVersionedDatabase(
                dataDir,
                durabilityMode,
                checkpointInterval,
                coldCacheSize,
                walStore,
                metaStore,
                delegate,
                checkpointToLoad,
                archivedUpTo,
                report
        );

        if (recoveredUnindexed > 0 || walResult.truncatedTailFrames() > 0 || optRefs.isEmpty()) {
            db.persistRefsMeta();
        }
        return db;
    }

    private static List<Commit> mergeAndValidateCommits(List<Commit> archived, List<Commit> walCommits) {
        Map<Long, Commit> byId = new HashMap<>();
        for (Commit c : archived) {
            byId.put(c.id().value(), c);
        }
        for (Commit c : walCommits) {
            byId.putIfAbsent(c.id().value(), c);
        }
        List<Commit> ordered = new ArrayList<>(byId.size());
        for (long i = 0L; i < byId.size(); i++) {
            Commit c = byId.get(i);
            if (c == null) {
                throw new StorageCorruptionException(
                        "Missing commit c" + i + " in contiguous WAL/archive commit sequence");
            }
            ordered.add(c);
        }
        return ordered;
    }

    private static Optional<CommitId> resolveCheckpointToLoad(
            Optional<BinaryGraphCodec.RefsMetadata> optRefs,
            CheckpointAndArchiveStore metaStore
    ) throws IOException {
        if (optRefs.isPresent() && optRefs.get().latestCheckpointId().isPresent()) {
            return optRefs.get().latestCheckpointId();
        }
        List<CommitId> onDisk = metaStore.listCheckpointIds();
        return onDisk.isEmpty() ? Optional.empty() : Optional.of(onDisk.getLast());
    }

    private static int replayPostCheckpointCommits(
            List<Commit> unifiedCommits,
            Map<CommitId, PersistentGraphSnapshot> hotSnapshots,
            long checkpointCommitValue,
            List<Commit> walCommits,
            CheckpointAndArchiveStore metaStore
    ) throws IOException {
        Set<CommitId> walIds = new HashSet<>(walCommits.size());
        for (Commit c : walCommits) {
            walIds.add(c.id());
        }

        int replayedWal = 0;
        for (Commit commit : unifiedCommits) {
            if (commit.id().value() <= checkpointCommitValue) {
                continue;
            }
            PersistentGraphSnapshot parentSnap = resolveForReplay(
                    commit.parentId().orElse(null), unifiedCommits, hotSnapshots, metaStore);
            PersistentGraphSnapshot nextSnap = parentSnap.applyDelta(commit.changes());
            hotSnapshots.put(commit.id(), nextSnap);
            if (walIds.contains(commit.id())) {
                replayedWal++;
            }
        }
        return replayedWal;
    }

    private static PersistentGraphSnapshot resolveForReplay(
            CommitId targetId,
            List<Commit> allCommits,
            Map<CommitId, PersistentGraphSnapshot> hotSnapshots,
            CheckpointAndArchiveStore metaStore
    ) throws IOException {
        if (targetId == null) {
            return PersistentGraphSnapshot.emptySnapshot();
        }
        return replayLineageToSnapshot(
                targetId,
                allCommits,
                id -> Optional.ofNullable(hotSnapshots.get(id)),
                metaStore
        );
    }

    private PersistentGraphSnapshot loadHistoricalSnapshotOnDemand(CommitId commitId) {
        PersistentGraphSnapshot fromCache = coldCache.get(commitId);
        if (fromCache != null) {
            return fromCache;
        }
        List<Commit> allCommits = delegate.commitHistory();
        if (commitId.value() < 0 || commitId.value() >= allCommits.size()) {
            throw new CommitNotFoundException("Commit not found: " + commitId);
        }
        try {
            PersistentGraphSnapshot result = replayLineageToSnapshot(
                    commitId,
                    allCommits,
                    id -> delegate.findHotSnapshot(id).or(() -> Optional.ofNullable(coldCache.get(id))),
                    metaStore
            );
            coldCache.put(commitId, result);
            return result;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to reconstruct cold snapshot for " + commitId, ex);
        }
    }

    private static PersistentGraphSnapshot replayLineageToSnapshot(
            CommitId targetId,
            List<Commit> allCommits,
            Function<CommitId, Optional<PersistentGraphSnapshot>> snapshotLookup,
            CheckpointAndArchiveStore metaStore
    ) throws IOException {
        Optional<PersistentGraphSnapshot> direct = snapshotLookup.apply(targetId);
        if (direct.isPresent()) {
            return direct.get();
        }
        List<Commit> lineage = new ArrayList<>();
        PersistentGraphSnapshot base = PersistentGraphSnapshot.emptySnapshot();
        CommitId cursor = targetId;
        while (cursor != null) {
            Optional<PersistentGraphSnapshot> cached = snapshotLookup.apply(cursor);
            if (cached.isPresent()) {
                base = cached.get();
                break;
            }
            if (metaStore != null && metaStore.hasCheckpoint(cursor)) {
                base = metaStore.readCheckpoint(cursor).snapshot();
                break;
            }
            Commit c = allCommits.get((int) cursor.value());
            lineage.add(c);
            cursor = c.parentId().orElse(null);
        }
        Collections.reverse(lineage);
        PersistentGraphSnapshot replayed = base;
        for (Commit c : lineage) {
            replayed = replayed.applyDelta(c.changes());
        }
        return replayed;
    }

    private static int advanceUnindexedBranches(List<Commit> commits, Map<String, Branch> branches) {
        int recoveredCount = 0;
        for (Commit commit : commits) {
            Branch existing = branches.get(commit.branchName());
            if (existing == null || existing.headCommitId().compareTo(commit.id()) < 0) {
                branches.put(commit.branchName(), new Branch(commit.branchName(), commit.id()));
                recoveredCount++;
            }
        }
        return recoveredCount;
    }

    private static void ensureBranchHeadsHot(
            List<Commit> commits,
            Map<String, Branch> branches,
            Map<CommitId, PersistentGraphSnapshot> hotSnapshots,
            CheckpointAndArchiveStore metaStore
    ) throws IOException {
        for (Branch branch : branches.values()) {
            CommitId head = branch.headCommitId();
            if (!hotSnapshots.containsKey(head)) {
                hotSnapshots.put(head, resolveForReplay(head, commits, hotSnapshots, metaStore));
            }
        }
    }

    private static long computeMaxUserEntityId(
            List<Commit> commits,
            Map<CommitId, PersistentGraphSnapshot> hotSnapshots
    ) {
        long maxId = EntityId.USER_MIN_ID - 1L;
        for (Commit c : commits) {
            for (Change change : c.changes()) {
                maxId = Math.max(maxId, change.primaryEntityId().value());
            }
        }
        for (PersistentGraphSnapshot snap : hotSnapshots.values()) {
            for (var node : snap.allEntities()) {
                maxId = Math.max(maxId, node.id().value());
            }
        }
        return maxId;
    }

    private void persistRefsMeta() throws IOException {
        BinaryGraphCodec.RefsMetadata meta = snapshotMetadataFromDelegate(
                delegate, latestCheckpointId, archivedUpToCommitId);
        metaStore.writeRefsMeta(meta, durabilityMode);
    }

    private static BinaryGraphCodec.RefsMetadata snapshotMetadataFromDelegate(
            PersistentTreeVersionedDatabase delegate,
            Optional<CommitId> latestCheckpointId,
            Optional<CommitId> archivedUpToCommitId
    ) {
        Map<String, Branch> branches = new LinkedHashMap<>();
        for (Branch b : delegate.listBranches()) {
            branches.put(b.name(), b);
        }
        Map<String, Tag> tags = new LinkedHashMap<>();
        for (Tag t : delegate.listTags()) {
            tags.put(t.name(), t);
        }
        return new BinaryGraphCodec.RefsMetadata(
                delegate.currentBranch(),
                branches,
                tags,
                delegate.branchReadRestrictionsSnapshot(),
                latestCheckpointId,
                archivedUpToCommitId,
                delegate.currentNextEntityId(),
                delegate.idempotentCommitsSnapshot(),
                delegate.listRedactionReceipts()
        );
    }

    private static void copyDirectoryContents(Path sourceDir, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            for (Path source : walk.toList()) {
                Path relative = sourceDir.relativize(source);
                if (relative.toString().endsWith(".tmp")) {
                    continue;
                }
                Path target = targetDir.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void closeQuietly(WriteAheadLogStore walStore) {
        if (walStore != null) {
            try {
                walStore.close();
            } catch (IOException ignored) {
                // Best-effort cleanup after open failure.
            }
        }
    }

    private final class DiskPersistenceObserver implements PersistentTreeVersionedDatabase.PersistenceObserver {

        @Override
        public void onCommitAppended(Commit commit, PersistentGraphSnapshot snapshot) {
            CrashPoint activeCrash = crashPoint;
            crashPoint = CrashPoint.NONE;
            try {
                walStore.append(commit, durabilityMode, activeCrash);
                if (activeCrash == CrashPoint.AFTER_WAL_BEFORE_REFS_UPDATE) {
                    throw new SimulatedCrashException(
                            CrashPoint.AFTER_WAL_BEFORE_REFS_UPDATE,
                            "Simulated crash after WAL frame append before updating refs.meta for " + commit.id());
                }

                commitsSinceLastCheckpoint++;
                if (commitsSinceLastCheckpoint >= checkpointInterval) {
                    metaStore.writeCheckpoint(commit.id(), snapshot, durabilityMode, activeCrash);
                    latestCheckpointId = Optional.of(commit.id());
                    commitsSinceLastCheckpoint = 0;
                }
                persistRefsMeta();
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to persist commit " + commit.id(), ex);
            }
        }

        @Override
        public void onBranchCreated(Branch branch) {
            persistRefsSafely();
        }

        @Override
        public void onBranchCheckedOut(String branchName) {
            persistRefsSafely();
        }

        @Override
        public void onTagCreated(Tag tag) {
            persistRefsSafely();
        }

        @Override
        public void onBranchReadRestricted(String branchName, Set<String> allowedPrincipalIds) {
            persistRefsSafely();
        }

        @Override
        public void onIdempotentCommitRecorded(String compositeKey, CommitId commitId) {
            persistRefsSafely();
        }

        @Override
        public void onHistoryRedacted(
                RedactionReceipt receipt,
                List<Commit> rewrittenCommits,
                int earliestAffectedIndex
        ) {
            coldCache.clear();
            try {
                if (earliestAffectedIndex >= 0) {
                    for (CheckpointAndArchiveStore.ArchiveSegment seg : metaStore.listArchiveSegments()) {
                        if (seg.toCommit() >= earliestAffectedIndex) {
                            List<Commit> slice = rewrittenCommits.subList((int) seg.fromCommit(), (int) seg.toCommit() + 1);
                            metaStore.writeArchiveSegment(seg.fromCommit(), seg.toCommit(), slice, durabilityMode);
                        }
                    }
                    for (CommitId ckptId : metaStore.listCheckpointIds()) {
                        if (ckptId.value() >= earliestAffectedIndex) {
                            PersistentGraphSnapshot ckptSnap = replayLineageToSnapshot(
                                    ckptId, rewrittenCommits, delegate::findHotSnapshot, null);
                            metaStore.writeCheckpoint(ckptId, ckptSnap, durabilityMode, CrashPoint.NONE);
                        }
                    }
                    long archivedCutoff = archivedUpToCommitId.map(CommitId::value).orElse(-1L);
                    List<Commit> unarchived = rewrittenCommits.stream()
                            .filter(c -> c.id().value() > archivedCutoff)
                            .toList();
                    walStore.rewrite(unarchived, durabilityMode);
                }
                persistRefsMeta();
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to persist GDPR redaction across disk storage", ex);
            }
        }

        private void persistRefsSafely() {
            try {
                persistRefsMeta();
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to persist refs metadata", ex);
            }
        }
    }
}
