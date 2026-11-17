package io.veg.engine;

import io.veg.model.Actor;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.GraphDiff;
import io.veg.model.Reason;

import java.util.List;

/**
 * Versioned graph database supporting atomic transactions, immutable commit history,
 * historical snapshots ({@code asOf}), structural diffs, counter-commit reverts, and replay (V-01..V-04, V-09..V-11).
 */
public interface VersionedGraphDatabase {

    CommitId headCommitId();

    GraphReader currentSnapshot();

    GraphReader asOf(CommitId commitId);

    Commit getCommit(CommitId commitId);

    List<Commit> commitHistory();

    List<Commit> historyOf(EntityId entityId);

    GraphDiff diff(CommitId fromCommit, CommitId toCommit);

    GraphTransaction beginTransaction(Actor actor, Reason reason, String correlationId);

    default GraphTransaction beginTransaction(Actor actor, Reason reason) {
        return beginTransaction(actor, reason, null);
    }

    CommitId revert(CommitId targetCommitId, Actor actor, Reason reason, String correlationId);

    default CommitId revert(CommitId targetCommitId, Actor actor, Reason reason) {
        return revert(targetCommitId, actor, reason, null);
    }

    GraphReader replayAsOf(CommitId commitId);
}
