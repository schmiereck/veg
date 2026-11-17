package io.veg.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchAndMergeModelTest {

    @Test
    void branchAndTagValidateBlankNamesAndPreservePointers() {
        CommitId c1 = new CommitId(1L);
        Branch b = new Branch("feature/crm", c1);
        Tag t = new Tag("v1.0.0", c1);

        assertThat(b.name()).isEqualTo("feature/crm");
        assertThat(b.headCommitId()).isEqualTo(c1);
        assertThat(t.name()).isEqualTo("v1.0.0");
        assertThat(t.commitId()).isEqualTo(c1);

        assertThatThrownBy(() -> new Branch("  ", c1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tag("", c1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergePolicyFiltersDataCategoriesWhileAlwaysIncludingSchema() {
        MergePolicy dev = MergePolicy.developmentDefault();
        assertThat(dev.includes(DataCategory.SCHEMA)).isTrue();
        assertThat(dev.includes(DataCategory.REFERENCE)).isTrue();
        assertThat(dev.includes(DataCategory.OPERATIONAL)).isFalse();
        assertThat(dev.includes(DataCategory.TEST)).isFalse();

        MergePolicy schemaOnly = MergePolicy.schemaOnly();
        assertThat(schemaOnly.includes(DataCategory.SCHEMA)).isTrue();
        assertThat(schemaOnly.includes(DataCategory.REFERENCE)).isFalse();

        MergePolicy all = MergePolicy.includeAll();
        for (DataCategory cat : DataCategory.values()) {
            assertThat(all.includes(cat)).isTrue();
        }
    }

    @Test
    void mergeResultAndCommitDagValidateInvariants() {
        CommitId c0 = CommitId.BOOTSTRAP;
        CommitId c1 = new CommitId(1L);
        CommitId c2 = new CommitId(2L);
        CommitId c3 = new CommitId(3L);

        Commit mergeCommit = new Commit(
                c3,
                List.of(c1, c2),
                "main",
                DataCategory.REFERENCE,
                Actor.user("u1"),
                Reason.of("Merge feature"),
                Optional.empty(),
                Instant.EPOCH,
                List.of()
        );
        assertThat(mergeCommit.isMergeCommit()).isTrue();
        assertThat(mergeCommit.parentId()).contains(c1);
        assertThat(mergeCommit.parentIds()).containsExactly(c1, c2);

        MergeResult ok = new MergeResult(true, Optional.of(c3), c0, c2, c1, List.of());
        assertThat(ok.succeeded()).isTrue();

        MergeConflict conflict = new MergeConflict(MergeConflictType.SHARED_VALUE_MODIFIED, new EntityId(1000L), "Conflict");
        MergeResult fail = new MergeResult(false, Optional.empty(), c0, c2, c1, List.of(conflict));
        assertThat(fail.succeeded()).isFalse();

        assertThatThrownBy(() -> new MergeResult(true, Optional.empty(), c0, c2, c1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MergeResult(false, Optional.of(c3), c0, c2, c1, List.of(conflict)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
