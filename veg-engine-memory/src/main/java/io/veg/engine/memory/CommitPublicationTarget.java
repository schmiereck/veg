package io.veg.engine.memory;

import io.veg.model.Actor;
import io.veg.model.Change;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Reason;

import java.util.List;
import java.util.Optional;

/**
 * Shared internal publication contract implemented by {@link InMemoryVersionedDatabase},
 * {@link CompactVersionedDatabase}, and {@link PersistentTreeVersionedDatabase} so transactions
 * and selective staging workspaces work identically across all three storage engines.
 */
interface CommitPublicationTarget {

    CommitId branchHead(String branchName);

    MutableGraphState stateCopyForBranch(String branchName);

    CommitId publishBranchCommit(
            String branchName,
            CommitId expectedBaseCommitId,
            DataCategory dataCategory,
            Actor actor,
            Reason reason,
            Optional<String> correlationId,
            MutableGraphState committedState,
            List<Change> changes
    );
}
