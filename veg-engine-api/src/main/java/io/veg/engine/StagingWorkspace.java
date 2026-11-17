package io.veg.engine;

import io.veg.model.Actor;
import io.veg.model.Change;
import io.veg.model.CommitId;
import io.veg.model.EntityId;
import io.veg.model.Reason;

import java.util.List;
import java.util.Set;

/**
 * Git-style selective staging workspace on a target branch (V-06).
 *
 * <p>Allows mutating schema and data freely in a branch-local workspace, staging individual
 * entities or relations by {@link EntityId}, and committing only staged changes after validating
 * that no required schema or endpoint dependencies remain unstaged.</p>
 */
public interface StagingWorkspace extends GraphSession {

    String branchName();

    CommitId baseCommitId();

    void stageEntity(EntityId entityId);

    void unstageEntity(EntityId entityId);

    void stageAll();

    void unstageAll();

    Set<EntityId> stagedEntityIds();

    List<Change> stagedChanges();

    List<Change> unstagedChanges();

    CommitId commitStaged(Actor actor, Reason reason, String correlationId);

    default CommitId commitStaged(Actor actor, Reason reason) {
        return commitStaged(actor, reason, null);
    }
}
