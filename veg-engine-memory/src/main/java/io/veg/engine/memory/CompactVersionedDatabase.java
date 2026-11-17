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
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.Tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Engine B: compact CSR storage engine with legal immutable {@link Value} interning.
 *
 * <p>Supports configurable checkpoint frequency:
 * <ul>
 *   <li>{@code snapshotInterval == 1} (Engine B1): compact CSR snapshot materialized at every commit.</li>
 *   <li>{@code snapshotInterval > 1} (Engine B2): journal-as-truth with periodic CSR checkpoints
 *       plus fast reconstructed reads.</li>
 * </ul>
 */
public final class CompactVersionedDatabase implements VersionedGraphDatabase, CommitPublicationTarget {

    private record ResolvedCache(CommitId commitId, CompactGraphSnapshot snapshot) {
    }

    private final AtomicLong sharedUserEntityIdAllocator = new AtomicLong(EntityId.USER_MIN_ID);
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Object[] idempotencyStripes = IdempotentCommitSupport.createStripes();
    private final ValueInterner valueInterner = new ValueInterner();
    private final int snapshotInterval;

    private final List<Commit> commits = new ArrayList<>();
    private final Map<CommitId, CompactGraphSnapshot> checkpoints = new HashMap<>();
    private final Map<String, Branch> branches = new LinkedHashMap<>();
    private final Map<String, Tag> tags = new LinkedHashMap<>();
    private final Map<String, Set<String>> branchReadRestrictions = new HashMap<>();
    private final Map<String, CommitId> idempotentCommits = new HashMap<>();
    private final List<RedactionReceipt> redactionReceipts = new ArrayList<>();

    private String activeBranch = DEFAULT_BRANCH;
    private volatile ResolvedCache lastResolved;

    public CompactVersionedDatabase() {
        this(1);
    }

    public CompactVersionedDatabase(int snapshotInterval) {
        if (snapshotInterval < 1) {
            throw new IllegalArgumentException("snapshotInterval must be >= 1");
        }
        this.snapshotInterval = snapshotInterval;

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
        CompactGraphSnapshot bootstrapSnapshot = CompactGraphSnapshot.fromState(initialState, valueInterner);
        checkpoints.put(CommitId.BOOTSTRAP, bootstrapSnapshot);
        branches.put(DEFAULT_BRANCH, new Branch(DEFAULT_BRANCH, CommitId.BOOTSTRAP));
    }

    public int snapshotInterval() {
        return snapshotInterval;
    }

    public int storedCheckpointCount() {
        return checkpoints.size();
    }

    public long totalCheckpointBytes() {
        long total = 0L;
        for (CompactGraphSnapshot snap : checkpoints.values()) {
            total += snap.estimatedFootprintBytes();
        }
        return total;
    }

    public int internedDistinctValueCount() {
        return valueInterner.distinctValueCount();
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
            return snapshotFor(headCommitId());
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
            return snapshotFor(branchHead(branchName));
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
            return snapshotFor(commitId);
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
            return new InMemoryTransaction(
                    this, branchName, head, mutableStateForCommit(head), dataCategory, actor, reason, correlationId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public StagingWorkspace createStagingWorkspace(String branchName) {
        rwLock.readLock().lock();
        try {
            CommitId head = branchHead(branchName);
            return new InMemoryStagingWorkspace(this, branchName, head, mutableStateForCommit(head));
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
            MutableGraphState working = mutableStateForCommit(branchHeadId);
            List<Change> counterChanges = CommitReverter.revertOnto(working, target);

            return appendCommit(
                    branchName,
                    List.of(branchHeadId),
                    DataCategory.REFERENCE,
                    actor,
                    reason,
                    Optional.ofNullable(correlationId),
                    working,
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
                    commits, this::mutableStateForCommit, sourceHead, targetHead, policy);
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
                    prepared.mergedState(),
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

            MutableGraphState resolvedState = mutableStateForCommit(targetHead);
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
                    resolvedState,
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

        MutableGraphState replayed = new MutableGraphState(sharedUserEntityIdAllocator);
        for (Commit commit : firstParentLineage) {
            for (Change change : commit.changes()) {
                replayed.applyReplayChange(change);
            }
        }
        replayed.rebuildDerivedIndexes();
        return CompactGraphSnapshot.fromState(replayed, valueInterner);
    }

    @Override
    public void restrictBranchReadAccess(String branchName, Set<String> allowedPrincipalIds) {
        Objects.requireNonNull(allowedPrincipalIds, "allowedPrincipalIds must not be null");
        for (String id : allowedPrincipalIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("allowedPrincipalIds must not contain null or blank principal IDs");
            }
        }
        rwLock.writeLock().lock();
        try {
            requireBranch(branchName);
            branchReadRestrictions.put(branchName, Set.copyOf(allowedPrincipalIds));
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
            GraphReader snapshot = snapshotFor(branch.headCommitId());
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
            GraphReader historicalSnapshot = snapshotFor(commitId);
            String targetBranch = commit.branchName();
            Branch branchObj = branches.get(targetBranch);
            GraphReader headSnapshot = branchObj != null
                    ? snapshotFor(branchObj.headCommitId())
                    : snapshotFor(headCommitId());
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
                    commits, this::mutableStateForCommit, sourceCommitId, targetHead);
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
                    prepared.mergedState(),
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
            lastResolved = null;
            if (outcome.earliestAffectedIndex() >= 0) {
                for (int i = outcome.earliestAffectedIndex(); i < commits.size(); i++) {
                    Commit c = commits.get(i);
                    if (checkpoints.containsKey(c.id())) {
                        checkpoints.remove(c.id());
                        MutableGraphState rebuilt = reconstructStateFromNearestCheckpoint(c.id());
                        checkpoints.put(c.id(), CompactGraphSnapshot.fromState(rebuilt, valueInterner));
                    }
                }
            }
            redactionReceipts.add(outcome.receipt());
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
    public MutableGraphState stateCopyForBranch(String branchName) {
        rwLock.readLock().lock();
        try {
            return mutableStateForCommit(branchHead(branchName));
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
                    branchName, List.of(currentBranchHead), dataCategory, actor, reason, correlationId, committedState, changes);
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
        } finally {
            rwLock.writeLock().unlock();
        }
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
            MutableGraphState nextState,
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
        CompactGraphSnapshot compact = CompactGraphSnapshot.fromState(nextState, valueInterner);
        if (nextId.value() % snapshotInterval == 0) {
            checkpoints.put(nextId, compact);
        }
        lastResolved = new ResolvedCache(nextId, compact);
        branches.put(branchName, new Branch(branchName, nextId));
        return nextId;
    }

    private CompactGraphSnapshot snapshotFor(CommitId commitId) {
        CompactGraphSnapshot direct = checkpoints.get(commitId);
        if (direct != null) {
            return direct;
        }
        ResolvedCache cached = lastResolved;
        if (cached != null && commitId.equals(cached.commitId())) {
            return cached.snapshot();
        }
        MutableGraphState reconstructed = reconstructStateFromNearestCheckpoint(commitId);
        CompactGraphSnapshot built = CompactGraphSnapshot.fromState(reconstructed, valueInterner);
        lastResolved = new ResolvedCache(commitId, built);
        return built;
    }

    private MutableGraphState mutableStateForCommit(CommitId commitId) {
        CompactGraphSnapshot direct = checkpoints.get(commitId);
        if (direct != null) {
            return direct.toMutableState(sharedUserEntityIdAllocator);
        }
        ResolvedCache cached = lastResolved;
        if (cached != null && commitId.equals(cached.commitId())) {
            return cached.snapshot().toMutableState(sharedUserEntityIdAllocator);
        }
        return reconstructStateFromNearestCheckpoint(commitId);
    }

    private MutableGraphState reconstructStateFromNearestCheckpoint(CommitId commitId) {
        List<Commit> tail = new ArrayList<>();
        CommitId cursor = commitId;
        while (cursor != null && !checkpoints.containsKey(cursor)) {
            Commit commit = commits.get((int) cursor.value());
            tail.add(commit);
            cursor = commit.parentId().orElse(null);
        }
        CompactGraphSnapshot baseCheckpoint = checkpoints.get(cursor);
        MutableGraphState working = baseCheckpoint != null
                ? baseCheckpoint.toMutableState(sharedUserEntityIdAllocator)
                : new MutableGraphState(sharedUserEntityIdAllocator);
        Collections.reverse(tail);
        for (Commit commit : tail) {
            for (Change change : commit.changes()) {
                working.applyReplayChange(change);
            }
        }
        working.rebuildDerivedIndexes();
        return working;
    }
}
