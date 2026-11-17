package io.veg.engine;

import io.veg.model.Actor;
import io.veg.model.Branch;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.EntityId;
import io.veg.model.GraphDiff;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.Reason;
import io.veg.model.Tag;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Versioned graph database supporting atomic transactions, immutable commit DAGs,
 * branches, tags, selective staging, three-way merges, diffs, reverts, and replay.
 */
public interface VersionedGraphDatabase {

    String DEFAULT_BRANCH = "main";

    // --- Active branch & head navigation ---

    String currentBranch();

    void checkoutBranch(String branchName);

    CommitId headCommitId();

    GraphReader currentSnapshot();

    // --- Branches & tags (V-05, V-08, P-04) ---

    Branch createBranch(String branchName, CommitId fromCommitId);

    default Branch createBranch(String branchName) {
        return createBranch(branchName, headCommitId());
    }

    Optional<Branch> findBranch(String branchName);

    List<Branch> listBranches();

    CommitId branchHead(String branchName);

    GraphReader branchSnapshot(String branchName);

    Tag createTag(String tagName, CommitId commitId);

    Optional<Tag> findTag(String tagName);

    List<Tag> listTags();

    GraphReader asOfTag(String tagName);

    // --- History, snapshots & diffs ---

    GraphReader asOf(CommitId commitId);

    Commit getCommit(CommitId commitId);

    List<Commit> commitHistory();

    List<Commit> historyOf(EntityId entityId);

    GraphDiff diff(CommitId fromCommit, CommitId toCommit);

    GraphReader replayAsOf(CommitId commitId);

    // --- Transactions & selective staging ---

    GraphTransaction beginTransaction(
            String branchName,
            Actor actor,
            Reason reason,
            String correlationId,
            DataCategory dataCategory
    );

    default GraphTransaction beginTransaction(String branchName, Actor actor, Reason reason, String correlationId) {
        return beginTransaction(branchName, actor, reason, correlationId, DataCategory.REFERENCE);
    }

    default GraphTransaction beginTransaction(String branchName, Actor actor, Reason reason) {
        return beginTransaction(branchName, actor, reason, null, DataCategory.REFERENCE);
    }

    default GraphTransaction beginTransaction(Actor actor, Reason reason, String correlationId) {
        return beginTransaction(currentBranch(), actor, reason, correlationId, DataCategory.REFERENCE);
    }

    default GraphTransaction beginTransaction(Actor actor, Reason reason) {
        return beginTransaction(currentBranch(), actor, reason, null, DataCategory.REFERENCE);
    }

    StagingWorkspace createStagingWorkspace(String branchName);

    default StagingWorkspace createStagingWorkspace() {
        return createStagingWorkspace(currentBranch());
    }

    // --- Revert & three-way merge (V-07, V-10, V-12, V-13) ---

    CommitId revert(String branchName, CommitId targetCommitId, Actor actor, Reason reason, String correlationId);

    default CommitId revert(CommitId targetCommitId, Actor actor, Reason reason, String correlationId) {
        return revert(currentBranch(), targetCommitId, actor, reason, correlationId);
    }

    default CommitId revert(CommitId targetCommitId, Actor actor, Reason reason) {
        return revert(currentBranch(), targetCommitId, actor, reason, null);
    }

    CommitId findCommonAncestor(CommitId commitA, CommitId commitB);

    MergeResult merge(String sourceBranch, String targetBranch, Actor actor, Reason reason, MergePolicy policy);

    default MergeResult merge(String sourceBranch, String targetBranch, Actor actor, Reason reason) {
        return merge(sourceBranch, targetBranch, actor, reason, MergePolicy.developmentDefault());
    }

    CommitId resolveMerge(
            String sourceBranch,
            String targetBranch,
            Actor actor,
            Reason reason,
            Consumer<GraphSession> conflictResolver
    );

    // --- Stage 5: Branch restrictions & authorized time-travel reads (S-05, T13, T14) ---

    void restrictBranchReadAccess(String branchName, java.util.Set<String> allowedPrincipalIds);

    AuthorizedGraphReader authorizedCurrentSnapshot(String principalId);

    default AuthorizedGraphReader authorizedCurrentSnapshot(Actor actor) {
        return authorizedCurrentSnapshot(java.util.Objects.requireNonNull(actor, "actor").id());
    }

    AuthorizedGraphReader authorizedBranchSnapshot(String branchName, String principalId);

    default AuthorizedGraphReader authorizedBranchSnapshot(String branchName, Actor actor) {
        return authorizedBranchSnapshot(branchName, java.util.Objects.requireNonNull(actor, "actor").id());
    }

    AuthorizedGraphReader authorizedAsOf(CommitId commitId, String principalId);

    default AuthorizedGraphReader authorizedAsOf(CommitId commitId, Actor actor) {
        return authorizedAsOf(commitId, java.util.Objects.requireNonNull(actor, "actor").id());
    }
}
