package io.veg.engine.memory;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.BranchNotFoundException;
import io.veg.engine.CommitNotFoundException;
import io.veg.engine.DuplicateBranchOrTagException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphSession;
import io.veg.engine.GraphTransaction;
import io.veg.engine.OptimisticCommitConflictException;
import io.veg.engine.RevertConflictException;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.Branch;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.EntityId;
import io.veg.model.GraphDiff;
import io.veg.model.IdGenerationStrategy;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.Tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Engine C: structurally shared persistent radix-32 tree storage engine ({@code P-04}, {@code H3}).
 *
 * <p>Each commit produces a new immutable {@link PersistentGraphSnapshot} solely by path-copying the
 * modified trie paths from its parent commit, leaving all unmodified subtrees physically shared across
 * arbitrary numbers of commits and branches.</p>
 */
public final class PersistentTreeVersionedDatabase implements VersionedGraphDatabase, CommitPublicationTarget {

    /**
     * Hook invoked on metadata and commit mutations so disk-backed wrappers can persist WAL frames,
     * branch pointers, tags, and checkpoints atomically.
     */
    public interface PersistenceObserver {
        default void onCommitAppended(Commit commit, PersistentGraphSnapshot snapshot) {
        }

        default void onBranchCreated(Branch branch) {
        }

        default void onBranchCheckedOut(String branchName) {
        }

        default void onTagCreated(Tag tag) {
        }

        default void onBranchReadRestricted(String branchName, Set<String> allowedPrincipalIds) {
        }

        default void onIdempotentCommitRecorded(String compositeKey, CommitId commitId) {
        }

        default void onHistoryRedacted(
                RedactionReceipt receipt,
                List<Commit> rewrittenCommits,
                int earliestAffectedIndex
        ) {
        }
    }

    private final AtomicLong sharedUserEntityIdAllocator;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Object[] idempotencyStripes = IdempotentCommitSupport.createStripes();
    private final ValueInterner valueInterner = new ValueInterner();
    private final List<Commit> commits = new ArrayList<>();
    private final Map<CommitId, PersistentGraphSnapshot> snapshots = new HashMap<>();
    private final Map<String, Branch> branches = new LinkedHashMap<>();
    private final Map<String, Tag> tags = new LinkedHashMap<>();
    private final Map<String, Set<String>> branchReadRestrictions = new HashMap<>();
    private final Map<String, CommitId> idempotentCommits = new LinkedHashMap<>();
    private final List<RedactionReceipt> redactionReceipts = new ArrayList<>();

    private String activeBranch = DEFAULT_BRANCH;
    private PersistenceObserver persistenceObserver;
    private Function<CommitId, PersistentGraphSnapshot> coldSnapshotLoader;
    private volatile IdGenerationStrategy idGenerationStrategy = IdGenerationStrategy.SEQUENTIAL;

    public PersistentTreeVersionedDatabase() {
        this.sharedUserEntityIdAllocator = new AtomicLong(EntityId.USER_MIN_ID);
        MutableGraphState initialState = new MutableGraphState(sharedUserEntityIdAllocator);
        List<Change> bootstrapChanges = new ArrayList<>();
        BootstrapKernelInitializer.initialize(initialState, bootstrapChanges);

        Commit bootstrapCommit = new Commit(
                CommitId.BOOTSTRAP,
                List.of(),
                DEFAULT_BRANCH,
                DataCategory.SCHEMA,
                Actor.system(),
                Reason.of("Bootstrap kernel initialization"),
                Optional.empty(),
                Instant.now(),
                bootstrapChanges
        );
        commits.add(bootstrapCommit);
        PersistentGraphSnapshot initialSnapshot = PersistentGraphSnapshot.empty()
                .applyChanges(bootstrapChanges, valueInterner);
        snapshots.put(CommitId.BOOTSTRAP, initialSnapshot);
        branches.put(DEFAULT_BRANCH, new Branch(DEFAULT_BRANCH, CommitId.BOOTSTRAP));
    }

    private PersistentTreeVersionedDatabase(long nextEntityId) {
        this.sharedUserEntityIdAllocator = new AtomicLong(nextEntityId);
    }

    /**
     * Constructs a {@link PersistentTreeVersionedDatabase} from recovered disk state (commits, hot snapshots,
     * branches, tags, branch ACL restrictions, active branch, and entity ID allocator state).
     */
    public static PersistentTreeVersionedDatabase createRecovered(
            List<Commit> recoveredCommits,
            Map<CommitId, PersistentGraphSnapshot> recoveredHotSnapshots,
            Map<String, Branch> recoveredBranches,
            Map<String, Tag> recoveredTags,
            Map<String, Set<String>> recoveredRestrictions,
            String activeBranchName,
            long nextUserEntityId
    ) {
        return createRecovered(
                recoveredCommits,
                recoveredHotSnapshots,
                recoveredBranches,
                recoveredTags,
                recoveredRestrictions,
                activeBranchName,
                nextUserEntityId,
                Map.of(),
                List.of()
        );
    }

    public static PersistentTreeVersionedDatabase createRecovered(
            List<Commit> recoveredCommits,
            Map<CommitId, PersistentGraphSnapshot> recoveredHotSnapshots,
            Map<String, Branch> recoveredBranches,
            Map<String, Tag> recoveredTags,
            Map<String, Set<String>> recoveredRestrictions,
            String activeBranchName,
            long nextUserEntityId,
            Map<String, CommitId> recoveredIdempotentCommits,
            List<RedactionReceipt> recoveredRedactionReceipts
    ) {
        Objects.requireNonNull(recoveredCommits, "recoveredCommits must not be null");
        Objects.requireNonNull(recoveredHotSnapshots, "recoveredHotSnapshots must not be null");
        Objects.requireNonNull(recoveredBranches, "recoveredBranches must not be null");
        Objects.requireNonNull(recoveredTags, "recoveredTags must not be null");
        Objects.requireNonNull(recoveredRestrictions, "recoveredRestrictions must not be null");
        Objects.requireNonNull(activeBranchName, "activeBranchName must not be null");
        Objects.requireNonNull(recoveredIdempotentCommits, "recoveredIdempotentCommits must not be null");
        Objects.requireNonNull(recoveredRedactionReceipts, "recoveredRedactionReceipts must not be null");

        PersistentTreeVersionedDatabase db = new PersistentTreeVersionedDatabase(
                Math.max(EntityId.USER_MIN_ID, nextUserEntityId));
        db.commits.addAll(recoveredCommits);
        db.snapshots.putAll(recoveredHotSnapshots);
        db.branches.putAll(recoveredBranches);
        db.tags.putAll(recoveredTags);
        for (var entry : recoveredRestrictions.entrySet()) {
            db.branchReadRestrictions.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        db.idempotentCommits.putAll(recoveredIdempotentCommits);
        db.redactionReceipts.addAll(recoveredRedactionReceipts);
        db.activeBranch = activeBranchName;
        return db;
    }

    public void setPersistenceObserver(PersistenceObserver observer) {
        this.persistenceObserver = observer;
    }

    public void setColdSnapshotLoader(Function<CommitId, PersistentGraphSnapshot> coldSnapshotLoader) {
        this.coldSnapshotLoader = coldSnapshotLoader;
    }

    public PersistentGraphSnapshot snapshotForCommit(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        rwLock.readLock().lock();
        try {
            return resolveSnapshot(commitId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Optional<PersistentGraphSnapshot> findHotSnapshot(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(snapshots.get(commitId));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean evictHotSnapshot(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        rwLock.writeLock().lock();
        try {
            return snapshots.remove(commitId) != null;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int hotSnapshotCount() {
        rwLock.readLock().lock();
        try {
            return snapshots.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Map<String, Set<String>> branchReadRestrictionsSnapshot() {
        rwLock.readLock().lock();
        try {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            for (var entry : branchReadRestrictions.entrySet()) {
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return copy;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Map<String, CommitId> idempotentCommitsSnapshot() {
        rwLock.readLock().lock();
        try {
            return Map.copyOf(idempotentCommits);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public long currentNextEntityId() {
        return sharedUserEntityIdAllocator.get();
    }

    public int countTotalUniqueTrieNodesAcrossAllCommits() {
        rwLock.readLock().lock();
        try {
            Set<Object> seenNodes = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PersistentGraphSnapshot snapshot : snapshots.values()) {
                snapshot.collectUniqueTrieNodes(seenNodes);
            }
            return seenNodes.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public String currentBranch() {
        rwLock.readLock().lock();
        try {
            return activeBranch;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void checkoutBranch(String branchName) {
        rwLock.writeLock().lock();
        try {
            requireBranch(branchName);
            this.activeBranch = branchName;
            if (persistenceObserver != null) {
                persistenceObserver.onBranchCheckedOut(branchName);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public CommitId headCommitId() {
        rwLock.readLock().lock();
        try {
            return branchHead(activeBranch);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public GraphReader currentSnapshot() {
        rwLock.readLock().lock();
        try {
            return resolveSnapshot(headCommitId());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Branch createBranch(String branchName, CommitId fromCommitId) {
        Objects.requireNonNull(branchName, "branchName must not be null");
        Objects.requireNonNull(fromCommitId, "fromCommitId must not be null");
        if (branchName.isBlank()) {
            throw new IllegalArgumentException("branchName must not be blank");
        }
        rwLock.writeLock().lock();
        try {
            if (branches.containsKey(branchName)) {
                throw new DuplicateBranchOrTagException("Branch already exists: " + branchName);
            }
            getCommit(fromCommitId);
            Branch branch = new Branch(branchName, fromCommitId);
            branches.put(branchName, branch);
            if (persistenceObserver != null) {
                persistenceObserver.onBranchCreated(branch);
            }
            return branch;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Branch> findBranch(String branchName) {
        if (branchName == null) {
            return Optional.empty();
        }
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(branches.get(branchName));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<Branch> listBranches() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(branches.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public CommitId branchHead(String branchName) {
        rwLock.readLock().lock();
        try {
            return requireBranch(branchName).headCommitId();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public GraphReader branchSnapshot(String branchName) {
        rwLock.readLock().lock();
        try {
            return resolveSnapshot(branchHead(branchName));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Tag createTag(String tagName, CommitId commitId) {
        Objects.requireNonNull(tagName, "tagName must not be null");
        Objects.requireNonNull(commitId, "commitId must not be null");
        if (tagName.isBlank()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        rwLock.writeLock().lock();
        try {
            if (tags.containsKey(tagName)) {
                throw new DuplicateBranchOrTagException("Tag already exists: " + tagName);
            }
            getCommit(commitId);
            Tag tag = new Tag(tagName, commitId);
            tags.put(tagName, tag);
            if (persistenceObserver != null) {
                persistenceObserver.onTagCreated(tag);
            }
            return tag;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Tag> findTag(String tagName) {
        if (tagName == null) {
            return Optional.empty();
        }
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(tags.get(tagName));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<Tag> listTags() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(tags.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public GraphReader asOfTag(String tagName) {
        Objects.requireNonNull(tagName, "tagName must not be null");
        rwLock.readLock().lock();
        try {
            Tag tag = tags.get(tagName);
            if (tag == null) {
                throw new CommitNotFoundException("Tag not found: " + tagName);
            }
            return asOf(tag.commitId());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public GraphReader asOf(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        rwLock.readLock().lock();
        try {
            getCommit(commitId);
            return resolveSnapshot(commitId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Commit getCommit(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        rwLock.readLock().lock();
        try {
            long idx = commitId.value();
            if (idx < 0 || idx >= commits.size()) {
                throw new CommitNotFoundException("Commit not found: " + commitId);
            }
            return commits.get((int) idx);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<Commit> commitHistory() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(commits);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<Commit> historyOf(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        rwLock.readLock().lock();
        try {
            List<Commit> result = new ArrayList<>();
            for (Commit commit : commits) {
                if (commit.touchesEntity(entityId)) {
                    result.add(commit);
                }
            }
            return List.copyOf(result);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public GraphDiff diff(CommitId fromCommit, CommitId toCommit) {
        Objects.requireNonNull(fromCommit, "fromCommit must not be null");
        Objects.requireNonNull(toCommit, "toCommit must not be null");
        return GraphDiffCalculator.compute(fromCommit, asOf(fromCommit), toCommit, asOf(toCommit));
    }

    @Override
    public GraphTransaction beginTransaction(
            String branchName,
            Actor actor,
            Reason reason,
            String correlationId,
            DataCategory dataCategory
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(dataCategory, "dataCategory must not be null");
        rwLock.readLock().lock();
        try {
            CommitId head = branchHead(branchName);
            MutableGraphState baseState = resolveSnapshot(head).toMutableState(sharedUserEntityIdAllocator);
            baseState.idGenerationStrategy = this.idGenerationStrategy;
            return new InMemoryTransaction(
                    this,
                    branchName,
                    head,
                    baseState,
                    dataCategory,
                    actor,
                    reason,
                    correlationId
            );
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public StagingWorkspace createStagingWorkspace(String branchName) {
        rwLock.readLock().lock();
        try {
            CommitId head = branchHead(branchName);
            return new InMemoryStagingWorkspace(
                    this,
                    branchName,
                    head,
                    resolveSnapshot(head).toMutableState(sharedUserEntityIdAllocator)
            );
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public CommitId revert(String branchName, CommitId targetCommitId, Actor actor, Reason reason, String correlationId) {
        Objects.requireNonNull(targetCommitId, "targetCommitId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        rwLock.writeLock().lock();
        try {
            CommitId branchHeadId = branchHead(branchName);
            if (targetCommitId.isBootstrap()) {
                throw new RevertConflictException("Cannot revert bootstrap commit " + targetCommitId);
            }
            Commit target = getCommit(targetCommitId);
            MutableGraphState working = resolveSnapshot(branchHeadId).toMutableState(sharedUserEntityIdAllocator);
            List<Change> counterChanges = CommitReverter.revertOnto(working, target);

            return appendCommit(
                    branchName,
                    List.of(branchHeadId),
                    DataCategory.REFERENCE,
                    actor,
                    reason,
                    Optional.ofNullable(correlationId),
                    counterChanges
            );
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public CommitId findCommonAncestor(CommitId commitA, CommitId commitB) {
        rwLock.readLock().lock();
        try {
            getCommit(commitA);
            getCommit(commitB);
            return ThreeWayMergeEngine.findCommonAncestor(commits, commitA, commitB);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public MergeResult merge(String sourceBranch, String targetBranch, Actor actor, Reason reason, MergePolicy policy) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        rwLock.writeLock().lock();
        try {
            CommitId sourceHead = branchHead(sourceBranch);
            CommitId targetHead = branchHead(targetBranch);

            ThreeWayMergeEngine.PreparedMerge prepared = ThreeWayMergeEngine.prepareMerge(
                    commits,
                    commitId -> resolveSnapshot(commitId).toMutableState(sharedUserEntityIdAllocator),
                    sourceHead,
                    targetHead,
                    policy
            );
            if (!prepared.succeeded()) {
                return new MergeResult(
                        false, Optional.empty(), prepared.baseCommitId(), sourceHead, targetHead, prepared.conflicts());
            }

            List<CommitId> parents = sourceHead.equals(targetHead)
                    ? List.of(targetHead)
                    : List.of(targetHead, sourceHead);
            CommitId mergeCommitId = appendCommit(
                    targetBranch,
                    parents,
                    DataCategory.REFERENCE,
                    actor,
                    reason,
                    Optional.empty(),
                    prepared.changesFromTarget()
            );
            return new MergeResult(
                    true, Optional.of(mergeCommitId), prepared.baseCommitId(), sourceHead, targetHead, List.of());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public CommitId resolveMerge(
            String sourceBranch,
            String targetBranch,
            Actor actor,
            Reason reason,
            Consumer<GraphSession> conflictResolver
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(conflictResolver, "conflictResolver must not be null");

        rwLock.writeLock().lock();
        try {
            CommitId sourceHead = branchHead(sourceBranch);
            CommitId targetHead = branchHead(targetBranch);

            MutableGraphState resolvedState = resolveSnapshot(targetHead).toMutableState(sharedUserEntityIdAllocator);
            List<Change> resolutionChanges = new ArrayList<>();
            InMemoryGraphEngine session = new InMemoryGraphEngine(resolvedState, resolutionChanges);
            conflictResolver.accept(session);

            return appendCommit(
                    targetBranch,
                    List.of(targetHead, sourceHead),
                    DataCategory.REFERENCE,
                    actor,
                    reason,
                    Optional.empty(),
                    resolutionChanges
            );
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public GraphReader replayAsOf(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        List<Commit> firstParentLineage = new ArrayList<>();
        rwLock.readLock().lock();
        try {
            getCommit(commitId);
            CommitId cursor = commitId;
            while (cursor != null) {
                Commit commit = commits.get((int) cursor.value());
                firstParentLineage.add(commit);
                cursor = commit.parentId().orElse(null);
            }
        } finally {
            rwLock.readLock().unlock();
        }
        Collections.reverse(firstParentLineage);

        PersistentGraphSnapshot replayed = PersistentGraphSnapshot.empty();
        for (Commit commit : firstParentLineage) {
            replayed = replayed.applyChanges(commit.changes(), valueInterner);
        }
        return replayed;
    }

    @Override
    public void restrictBranchReadAccess(String branchName, Set<String> allowedPrincipalIds) {
        Objects.requireNonNull(allowedPrincipalIds, "allowedPrincipalIds must not be null");
        for (String id : allowedPrincipalIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("allowedPrincipalIds must not contain null or blank principal IDs");
            }
        }
        Set<String> copied = Set.copyOf(allowedPrincipalIds);
        rwLock.writeLock().lock();
        try {
            requireBranch(branchName);
            branchReadRestrictions.put(branchName, copied);
            if (persistenceObserver != null) {
                persistenceObserver.onBranchReadRestricted(branchName, copied);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public AuthorizedGraphReader authorizedCurrentSnapshot(String principalId) {
        return authorizedBranchSnapshot(currentBranch(), principalId);
    }

    @Override
    public AuthorizedGraphReader authorizedBranchSnapshot(String branchName, String principalId) {
        rwLock.readLock().lock();
        try {
            Branch branch = requireBranch(branchName);
            GraphReader snapshot = resolveSnapshot(branch.headCommitId());
            return new AuthorizedGraphReaderImpl(
                    snapshot,
                    snapshot,
                    principalId,
                    branchName,
                    branchReadRestrictions.get(branchName)
            );
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public AuthorizedGraphReader authorizedAsOf(CommitId commitId, String principalId) {
        rwLock.readLock().lock();
        try {
            Commit commit = getCommit(commitId);
            GraphReader historicalSnapshot = resolveSnapshot(commitId);
            String targetBranch = commit.branchName();
            Branch branchObj = branches.get(targetBranch);
            GraphReader headSnapshot = branchObj != null
                    ? resolveSnapshot(branchObj.headCommitId())
                    : resolveSnapshot(headCommitId());
            return new AuthorizedGraphReaderImpl(
                    historicalSnapshot,
                    headSnapshot,
                    principalId,
                    targetBranch,
                    branchReadRestrictions.get(targetBranch)
            );
        } finally {
            rwLock.readLock().unlock();
        }
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
        return IdempotentCommitSupport.execute(
                this,
                idempotencyStripes,
                this::lookupIdempotentCommit,
                this::registerIdempotentCommit,
                branchName,
                idempotencyKey,
                actor,
                reason,
                correlationId,
                work
        );
    }

    @Override
    public MergeResult cherryPick(CommitId sourceCommitId, String targetBranch, Actor actor, Reason reason) {
        Objects.requireNonNull(sourceCommitId, "sourceCommitId must not be null");
        Objects.requireNonNull(targetBranch, "targetBranch must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (sourceCommitId.isBootstrap()) {
            throw new IllegalArgumentException("Cannot cherry-pick bootstrap commit " + sourceCommitId);
        }

        rwLock.writeLock().lock();
        try {
            getCommit(sourceCommitId);
            CommitId targetHead = branchHead(targetBranch);
            ThreeWayMergeEngine.PreparedMerge prepared = ThreeWayMergeEngine.prepareCherryPick(
                    commits,
                    commitId -> resolveSnapshot(commitId).toMutableState(sharedUserEntityIdAllocator),
                    sourceCommitId,
                    targetHead
            );
            if (!prepared.succeeded()) {
                return new MergeResult(
                        false, Optional.empty(), prepared.baseCommitId(), sourceCommitId, targetHead, prepared.conflicts());
            }

            CommitId cherryId = appendCommit(
                    targetBranch,
                    List.of(targetHead),
                    commits.get((int) sourceCommitId.value()).dataCategory(),
                    actor,
                    reason,
                    Optional.empty(),
                    prepared.changesFromTarget()
            );
            return new MergeResult(
                    true, Optional.of(cherryId), prepared.baseCommitId(), sourceCommitId, targetHead, List.of());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public RedactionReceipt redactEntityPayload(EntityId valueEntityId, Actor actor, Reason reason) {
        rwLock.writeLock().lock();
        try {
            RedactionEngine.RedactionOutcome outcome =
                    RedactionEngine.redactAcrossHistory(commits, valueEntityId, actor, reason);
            commits.clear();
            commits.addAll(outcome.rewrittenCommits());
            valueInterner.clear();

            if (outcome.earliestAffectedIndex() >= 0) {
                Map<CommitId, PersistentGraphSnapshot> rebuilt = new HashMap<>();
                for (int i = outcome.earliestAffectedIndex(); i < commits.size(); i++) {
                    Commit c = commits.get(i);
                    PersistentGraphSnapshot parentSnap = resolveParentForRedactionRebuild(
                            c.parentId().orElse(null), outcome.earliestAffectedIndex(), rebuilt);
                    PersistentGraphSnapshot nextSnap = parentSnap.applyChanges(c.changes(), valueInterner);
                    rebuilt.put(c.id(), nextSnap);
                    if (snapshots.containsKey(c.id())) {
                        snapshots.put(c.id(), nextSnap);
                    }
                }
            }

            redactionReceipts.add(outcome.receipt());
            if (persistenceObserver != null) {
                persistenceObserver.onHistoryRedacted(
                        outcome.receipt(), List.copyOf(commits), outcome.earliestAffectedIndex());
            }
            return outcome.receipt();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public List<RedactionReceipt> listRedactionReceipts() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(redactionReceipts);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public IdGenerationStrategy idGenerationStrategy() {
        return idGenerationStrategy;
    }

    @Override
    public void setIdGenerationStrategy(IdGenerationStrategy strategy) {
        this.idGenerationStrategy = Objects.requireNonNull(strategy, "strategy must not be null");
    }

    @Override
    public MutableGraphState stateCopyForBranch(String branchName) {
        rwLock.readLock().lock();
        try {
            MutableGraphState copy = resolveSnapshot(branchHead(branchName)).toMutableState(sharedUserEntityIdAllocator);
            copy.idGenerationStrategy = this.idGenerationStrategy;
            return copy;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public CommitId publishBranchCommit(
            String branchName,
            CommitId expectedBaseCommitId,
            DataCategory dataCategory,
            Actor actor,
            Reason reason,
            Optional<String> correlationId,
            MutableGraphState committedState,
            List<Change> changes
    ) {
        rwLock.writeLock().lock();
        try {
            CommitId currentBranchHead = branchHead(branchName);
            if (!expectedBaseCommitId.equals(currentBranchHead)) {
                throw new OptimisticCommitConflictException(branchName, expectedBaseCommitId, currentBranchHead);
            }
            return appendCommit(
                    branchName, List.of(currentBranchHead), dataCategory, actor, reason, correlationId, changes);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private Optional<CommitId> lookupIdempotentCommit(String key) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(idempotentCommits.get(key));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void registerIdempotentCommit(String key, CommitId commitId) {
        rwLock.writeLock().lock();
        try {
            idempotentCommits.put(key, commitId);
            if (persistenceObserver != null) {
                persistenceObserver.onIdempotentCommitRecorded(key, commitId);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private PersistentGraphSnapshot resolveParentForRedactionRebuild(
            CommitId parentId,
            int earliestAffectedIndex,
            Map<CommitId, PersistentGraphSnapshot> rebuilt
    ) {
        if (parentId == null) {
            return PersistentGraphSnapshot.empty();
        }
        PersistentGraphSnapshot cached = rebuilt.get(parentId);
        if (cached != null) {
            return cached;
        }
        if (parentId.value() < earliestAffectedIndex) {
            PersistentGraphSnapshot hot = snapshots.get(parentId);
            if (hot != null) {
                return hot;
            }
        }
        // Reconstruct from rewrittenCommits directly without consulting pre-redaction cold checkpoints on disk
        List<Commit> lineage = new ArrayList<>();
        PersistentGraphSnapshot base = PersistentGraphSnapshot.empty();
        CommitId cursor = parentId;
        while (cursor != null) {
            PersistentGraphSnapshot fromRebuilt = rebuilt.get(cursor);
            if (fromRebuilt != null) {
                base = fromRebuilt;
                break;
            }
            if (cursor.value() < earliestAffectedIndex && snapshots.containsKey(cursor)) {
                base = snapshots.get(cursor);
                break;
            }
            Commit c = commits.get((int) cursor.value());
            lineage.add(c);
            cursor = c.parentId().orElse(null);
        }
        Collections.reverse(lineage);
        PersistentGraphSnapshot result = base;
        for (Commit c : lineage) {
            result = result.applyChanges(c.changes(), valueInterner);
            rebuilt.put(c.id(), result);
        }
        return result;
    }

    private Branch requireBranch(String branchName) {
        Objects.requireNonNull(branchName, "branchName must not be null");
        Branch branch = branches.get(branchName);
        if (branch == null) {
            throw new BranchNotFoundException("Branch not found: " + branchName);
        }
        return branch;
    }

    private CommitId appendCommit(
            String branchName,
            List<CommitId> parentIds,
            DataCategory dataCategory,
            Actor actor,
            Reason reason,
            Optional<String> correlationId,
            List<Change> changes
    ) {
        CommitId nextId = new CommitId(commits.size());
        Commit commit = new Commit(
                nextId,
                parentIds,
                branchName,
                dataCategory,
                actor,
                reason,
                correlationId,
                Instant.now(),
                changes
        );
        commits.add(commit);
        PersistentGraphSnapshot parentSnapshot = parentIds.isEmpty()
                ? PersistentGraphSnapshot.empty()
                : resolveSnapshot(parentIds.getFirst());
        PersistentGraphSnapshot nextSnapshot = parentSnapshot.applyChanges(changes, valueInterner);
        snapshots.put(nextId, nextSnapshot);
        branches.put(branchName, new Branch(branchName, nextId));
        if (persistenceObserver != null) {
            persistenceObserver.onCommitAppended(commit, nextSnapshot);
        }
        return nextId;
    }

    private PersistentGraphSnapshot resolveSnapshot(CommitId commitId) {
        PersistentGraphSnapshot hot = snapshots.get(commitId);
        if (hot != null) {
            return hot;
        }
        if (coldSnapshotLoader != null && commitId.value() >= 0 && commitId.value() < commits.size()) {
            PersistentGraphSnapshot loaded = coldSnapshotLoader.apply(commitId);
            if (loaded != null) {
                return loaded;
            }
        }
        throw new CommitNotFoundException("Snapshot not found for commit: " + commitId);
    }
}
