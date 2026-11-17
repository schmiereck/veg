package io.veg.engine.memory;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.BranchNotFoundException;
import io.veg.engine.CommitNotFoundException;
import io.veg.engine.DuplicateBranchOrTagException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphSession;
import io.veg.engine.GraphTransaction;
import io.veg.engine.RevertConflictException;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.TransactionStateException;
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
import java.util.function.Consumer;

/**
 * Commit-DAG versioned in-memory database supporting branches, tags, selective staging,
 * structural three-way merge, transactions, savepoints, diffs, reverts, and replay (Stages 3–4).
 */
public final class InMemoryVersionedDatabase implements VersionedGraphDatabase, CommitPublicationTarget {

    private final AtomicLong sharedUserEntityIdAllocator = new AtomicLong(EntityId.USER_MIN_ID);
    private final List<Commit> commits = new ArrayList<>();
    private final Map<CommitId, InMemoryGraphSnapshot> snapshots = new HashMap<>();
    private final Map<String, Branch> branches = new LinkedHashMap<>();
    private final Map<String, Tag> tags = new LinkedHashMap<>();
    private final Map<String, Set<String>> branchReadRestrictions = new HashMap<>();

    private String activeBranch = DEFAULT_BRANCH;

    public InMemoryVersionedDatabase() {
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
        snapshots.put(CommitId.BOOTSTRAP, new InMemoryGraphSnapshot(initialState.copy()));
        branches.put(DEFAULT_BRANCH, new Branch(DEFAULT_BRANCH, CommitId.BOOTSTRAP));
    }

    @Override
    public String currentBranch() {
        return activeBranch;
    }

    @Override
    public void checkoutBranch(String branchName) {
        requireBranch(branchName);
        this.activeBranch = branchName;
    }

    @Override
    public CommitId headCommitId() {
        return branchHead(activeBranch);
    }

    @Override
    public GraphReader currentSnapshot() {
        return snapshots.get(headCommitId());
    }

    @Override
    public Branch createBranch(String branchName, CommitId fromCommitId) {
        Objects.requireNonNull(branchName, "branchName must not be null");
        Objects.requireNonNull(fromCommitId, "fromCommitId must not be null");
        if (branchName.isBlank()) {
            throw new IllegalArgumentException("branchName must not be blank");
        }
        if (branches.containsKey(branchName)) {
            throw new DuplicateBranchOrTagException("Branch already exists: " + branchName);
        }
        getCommit(fromCommitId);
        Branch branch = new Branch(branchName, fromCommitId);
        branches.put(branchName, branch);
        return branch;
    }

    @Override
    public Optional<Branch> findBranch(String branchName) {
        return branchName == null ? Optional.empty() : Optional.ofNullable(branches.get(branchName));
    }

    @Override
    public List<Branch> listBranches() {
        return List.copyOf(branches.values());
    }

    @Override
    public CommitId branchHead(String branchName) {
        return requireBranch(branchName).headCommitId();
    }

    @Override
    public GraphReader branchSnapshot(String branchName) {
        return snapshots.get(branchHead(branchName));
    }

    @Override
    public Tag createTag(String tagName, CommitId commitId) {
        Objects.requireNonNull(tagName, "tagName must not be null");
        Objects.requireNonNull(commitId, "commitId must not be null");
        if (tagName.isBlank()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        if (tags.containsKey(tagName)) {
            throw new DuplicateBranchOrTagException("Tag already exists: " + tagName);
        }
        getCommit(commitId);
        Tag tag = new Tag(tagName, commitId);
        tags.put(tagName, tag);
        return tag;
    }

    @Override
    public Optional<Tag> findTag(String tagName) {
        return tagName == null ? Optional.empty() : Optional.ofNullable(tags.get(tagName));
    }

    @Override
    public List<Tag> listTags() {
        return List.copyOf(tags.values());
    }

    @Override
    public GraphReader asOfTag(String tagName) {
        Objects.requireNonNull(tagName, "tagName must not be null");
        Tag tag = tags.get(tagName);
        if (tag == null) {
            throw new CommitNotFoundException("Tag not found: " + tagName);
        }
        return asOf(tag.commitId());
    }

    @Override
    public GraphReader asOf(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        InMemoryGraphSnapshot snapshot = snapshots.get(commitId);
        if (snapshot == null) {
            throw new CommitNotFoundException("Commit not found: " + commitId);
        }
        return snapshot;
    }

    @Override
    public Commit getCommit(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        long idx = commitId.value();
        if (idx < 0 || idx >= commits.size()) {
            throw new CommitNotFoundException("Commit not found: " + commitId);
        }
        return commits.get((int) idx);
    }

    @Override
    public List<Commit> commitHistory() {
        return List.copyOf(commits);
    }

    @Override
    public List<Commit> historyOf(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        List<Commit> result = new ArrayList<>();
        for (Commit commit : commits) {
            if (commit.touchesEntity(entityId)) {
                result.add(commit);
            }
        }
        return List.copyOf(result);
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
        CommitId head = branchHead(branchName);
        return new InMemoryTransaction(
                this, branchName, head, snapshots.get(head).copyState(), dataCategory, actor, reason, correlationId);
    }

    @Override
    public StagingWorkspace createStagingWorkspace(String branchName) {
        CommitId head = branchHead(branchName);
        return new InMemoryStagingWorkspace(this, branchName, head, snapshots.get(head).copyState());
    }

    @Override
    public CommitId revert(String branchName, CommitId targetCommitId, Actor actor, Reason reason, String correlationId) {
        Objects.requireNonNull(targetCommitId, "targetCommitId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        CommitId branchHeadId = branchHead(branchName);
        if (targetCommitId.isBootstrap()) {
            throw new RevertConflictException("Cannot revert bootstrap commit " + targetCommitId);
        }
        Commit target = getCommit(targetCommitId);
        MutableGraphState working = snapshots.get(branchHeadId).copyState();
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
    }

    @Override
    public CommitId findCommonAncestor(CommitId commitA, CommitId commitB) {
        getCommit(commitA);
        getCommit(commitB);
        return ThreeWayMergeEngine.findCommonAncestor(commits, commitA, commitB);
    }

    @Override
    public MergeResult merge(String sourceBranch, String targetBranch, Actor actor, Reason reason, MergePolicy policy) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        CommitId sourceHead = branchHead(sourceBranch);
        CommitId targetHead = branchHead(targetBranch);

        ThreeWayMergeEngine.PreparedMerge prepared =
                ThreeWayMergeEngine.prepareMerge(commits, snapshots, sourceHead, targetHead, policy);
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

        CommitId sourceHead = branchHead(sourceBranch);
        CommitId targetHead = branchHead(targetBranch);

        MutableGraphState resolvedState = snapshots.get(targetHead).copyState();
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
    }

    @Override
    public GraphReader replayAsOf(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        getCommit(commitId);

        List<Commit> firstParentLineage = new ArrayList<>();
        CommitId cursor = commitId;
        while (cursor != null) {
            Commit commit = commits.get((int) cursor.value());
            firstParentLineage.add(commit);
            cursor = commit.parentId().orElse(null);
        }
        Collections.reverse(firstParentLineage);

        MutableGraphState replayed = new MutableGraphState(sharedUserEntityIdAllocator);
        for (Commit commit : firstParentLineage) {
            for (Change change : commit.changes()) {
                replayed.applyReplayChange(change);
            }
        }
        replayed.rebuildDerivedIndexes();
        return new InMemoryGraphSnapshot(replayed);
    }

    @Override
    public void restrictBranchReadAccess(String branchName, Set<String> allowedPrincipalIds) {
        requireBranch(branchName);
        Objects.requireNonNull(allowedPrincipalIds, "allowedPrincipalIds must not be null");
        for (String id : allowedPrincipalIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("allowedPrincipalIds must not contain null or blank principal IDs");
            }
        }
        branchReadRestrictions.put(branchName, Set.copyOf(allowedPrincipalIds));
    }

    @Override
    public AuthorizedGraphReader authorizedCurrentSnapshot(String principalId) {
        return authorizedBranchSnapshot(activeBranch, principalId);
    }

    @Override
    public AuthorizedGraphReader authorizedBranchSnapshot(String branchName, String principalId) {
        Branch branch = requireBranch(branchName);
        InMemoryGraphSnapshot snapshot = snapshots.get(branch.headCommitId());
        return new AuthorizedGraphReaderImpl(
                snapshot,
                snapshot,
                principalId,
                branchName,
                branchReadRestrictions.get(branchName)
        );
    }

    @Override
    public AuthorizedGraphReader authorizedAsOf(CommitId commitId, String principalId) {
        Commit commit = getCommit(commitId);
        InMemoryGraphSnapshot historicalSnapshot = snapshots.get(commitId);
        String targetBranch = commit.branchName();
        Branch branchObj = branches.get(targetBranch);
        InMemoryGraphSnapshot headSnapshot = branchObj != null
                ? snapshots.get(branchObj.headCommitId())
                : snapshots.get(headCommitId());
        return new AuthorizedGraphReaderImpl(
                historicalSnapshot,
                headSnapshot,
                principalId,
                targetBranch,
                branchReadRestrictions.get(targetBranch)
        );
    }

    @Override
    public MutableGraphState stateCopyForBranch(String branchName) {
        return snapshots.get(branchHead(branchName)).copyState();
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
        CommitId currentBranchHead = branchHead(branchName);
        if (!expectedBaseCommitId.equals(currentBranchHead)) {
            throw new TransactionStateException(
                    "Concurrent commit conflict on branch '" + branchName + "': transaction based on "
                            + expectedBaseCommitId + " but head is now " + currentBranchHead);
        }
        return appendCommit(
                branchName, List.of(currentBranchHead), dataCategory, actor, reason, correlationId, committedState, changes);
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
        snapshots.put(nextId, new InMemoryGraphSnapshot(nextState.copy()));
        branches.put(branchName, new Branch(branchName, nextId));
        return nextId;
    }
}
