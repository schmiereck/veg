package io.veg.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedModelPrimitivesTest {

    @Test
    void commitIdValidatesBoundsAndOrdersDeterministically() {
        assertThatThrownBy(() -> new CommitId(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CommitId.BOOTSTRAP.isBootstrap()).isTrue();
        assertThat(CommitId.BOOTSTRAP.toString()).isEqualTo("c0");

        CommitId c1 = CommitId.BOOTSTRAP.next();
        assertThat(c1.isBootstrap()).isFalse();
        assertThat(c1.toString()).isEqualTo("c1");
        assertThat(CommitId.BOOTSTRAP.compareTo(c1)).isLessThan(0);
    }

    @Test
    void actorReasonAndSavepointRejectBlanksAndNulls() {
        assertThatThrownBy(() -> Actor.user("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.of("u1", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reason.of("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SavepointId(" ", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SavepointId("sp1", -1)).isInstanceOf(IllegalArgumentException.class);

        Actor u = Actor.user("u123");
        assertThat(u.id()).isEqualTo("u123");
        assertThat(u.displayName()).isEqualTo("u123");
        assertThat(Actor.system().id()).isEqualTo("system");
    }

    @Test
    void commitIsImmutableAndDetectsTouchedEntities() {
        EntityId p1 = new EntityId(1000L);
        EntityId p2 = new EntityId(1001L);
        EntityId val = new EntityId(1002L);
        EntityId edgeId = new EntityId(1003L);
        EntityId predId = new EntityId(1004L);

        EntityNode p1Node = new EntityNode(p1, new EntityId(1005L), EntityKind.OBJECT, Optional.empty());
        Edge edge = new Edge(edgeId, p1, predId, val);

        List<Change> mutable = new ArrayList<>();
        mutable.add(new Change.EntityCreated(p1Node));
        mutable.add(new Change.ValueUpdated(val, Value.of("old"), Value.of("new")));
        mutable.add(new Change.EdgeConnected(edge));

        Commit commit = new Commit(
                new CommitId(1L),
                Optional.of(CommitId.BOOTSTRAP),
                Actor.user("u1"),
                Reason.of("Initial import"),
                Optional.of("CRM-1"),
                Instant.EPOCH,
                mutable
        );
        mutable.clear();

        assertThat(commit.changes()).hasSize(3);
        assertThat(commit.touchesEntity(p1)).isTrue();
        assertThat(commit.touchesEntity(val)).isTrue();
        assertThat(commit.touchesEntity(edgeId)).isTrue();
        assertThat(commit.touchesEntity(p2)).isFalse();
    }

    @Test
    void graphDiffIsEmptyOnlyWhenAllListsAreEmpty() {
        CommitId c0 = CommitId.BOOTSTRAP;
        CommitId c1 = c0.next();

        GraphDiff empty = new GraphDiff(c0, c1, List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(empty.isEmpty()).isTrue();

        EntityNode added = new EntityNode(new EntityId(1000L), new EntityId(1001L), EntityKind.OBJECT, Optional.empty());
        GraphDiff nonEmpty = new GraphDiff(c0, c1, List.of(added), List.of(), List.of(), List.of(), List.of());
        assertThat(nonEmpty.isEmpty()).isFalse();
    }
}
