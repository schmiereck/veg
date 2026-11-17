package io.veg.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityAndInferenceModelTest {

    @Test
    void accessDecisionValidatesReasonConsistencyAndCopiesWinningPath() {
        EntityId doc = new EntityId(1001L);
        EntityId project = new EntityId(1002L);
        AccessDecision allow = AccessDecision.allow(
                "alice",
                Permission.READ,
                doc,
                AccessReason.INHERITED_ALLOW,
                List.of(doc, project),
                "Inherited READ via Project"
        );
        assertThat(allow.allowed()).isTrue();
        assertThat(allow.winningPath()).containsExactly(doc, project);
        assertThatThrownBy(() -> allow.winningPath().add(new EntityId(9999L)))
                .isInstanceOf(UnsupportedOperationException.class);

        AccessDecision deny = AccessDecision.deny(
                "alice",
                Permission.WRITE,
                doc,
                AccessReason.DEFAULT_DENY,
                List.of(),
                "No matching ACL entry"
        );
        assertThat(deny.allowed()).isFalse();

        assertThatThrownBy(() -> new AccessDecision(
                "alice",
                Permission.READ,
                doc,
                true,
                AccessReason.DIRECT_DENY,
                List.of(),
                "contradiction"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AccessDecision(
                "alice",
                Permission.READ,
                doc,
                false,
                AccessReason.DIRECT_ALLOW,
                List.of(),
                "contradiction"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AccessDecision.deny(
                " ",
                Permission.READ,
                doc,
                AccessReason.DEFAULT_DENY,
                List.of(),
                "blank actor"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivedAssertionFactoriesPreserveOriginSemantics() {
        EntityId edgeId = new EntityId(2000L);
        EntityId alice = new EntityId(2001L);
        EntityId bob = new EntityId(2002L);
        EntityId parentOf = new EntityId(2003L);
        EntityId childOf = new EntityId(2004L);

        Edge explicitEdge = new Edge(edgeId, alice, parentOf, bob);
        DerivedAssertion explicit = DerivedAssertion.explicit(explicitEdge);
        assertThat(explicit.origin()).isEqualTo(AssertionOrigin.EXPLICIT);
        assertThat(explicit.backingEdgeId()).contains(edgeId);
        assertThat(explicit.viaPredicateId()).isEmpty();

        DerivedAssertion inverse = DerivedAssertion.derived(
                bob,
                childOf,
                alice,
                AssertionOrigin.DERIVED_INVERSE,
                edgeId,
                parentOf
        );
        assertThat(inverse.origin()).isEqualTo(AssertionOrigin.DERIVED_INVERSE);
        assertThat(inverse.backingEdgeId()).contains(edgeId);
        assertThat(inverse.viaPredicateId()).contains(parentOf);

        assertThatThrownBy(() -> DerivedAssertion.derived(
                alice,
                parentOf,
                bob,
                AssertionOrigin.EXPLICIT,
                edgeId,
                parentOf
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stage5BootstrapIdsRemainBelowReservedBoundary() {
        assertThat(BootstrapIds.META_PRINCIPAL_TYPE.value()).isEqualTo(50L);
        assertThat(BootstrapIds.META_PROPAGATION_RULE_TYPE.value()).isEqualTo(52L);
        assertThat(BootstrapIds.HAS_SUPER_PREDICATE.value()).isEqualTo(69L);
        assertThat(BootstrapIds.META_ACL_ENTRY_TYPE.isBootstrap()).isTrue();
        assertThat(BootstrapIds.HAS_INVERSE_PREDICATE.isBootstrap()).isTrue();
        assertThat(EntityId.USER_MIN_ID).isEqualTo(1000L);
    }
}
