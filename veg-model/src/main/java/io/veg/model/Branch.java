package io.veg.model;

import java.util.Objects;

/**
 * Named branch pointer referencing a consistent {@link CommitId} head in the commit DAG (V-05).
 */
public record Branch(String name, CommitId headCommitId) {

    public Branch {
        Objects.requireNonNull(name, "branch name must not be null");
        Objects.requireNonNull(headCommitId, "headCommitId must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("branch name must not be blank");
        }
    }
}
