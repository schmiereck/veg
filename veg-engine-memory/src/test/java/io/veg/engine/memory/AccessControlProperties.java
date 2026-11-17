package io.veg.engine.memory;

import io.veg.engine.GraphTransaction;
import io.veg.model.AccessDecision;
import io.veg.model.Actor;
import io.veg.model.EntityId;
import io.veg.model.Permission;
import io.veg.model.PropagationDirection;
import io.veg.model.Reason;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AccessControlProperties {

    private static final Actor ADMIN = Actor.of("admin", "Admin");

    @Property(tries = 60)
    void cyclicGraphsAlwaysTerminateAndReplayIdentically(
            @ForAll long seed,
            @ForAll @IntRange(min = 2, max = 7) int nodeCount,
            @ForAll @IntRange(min = 1, max = 14) int edgeCount,
            @ForAll PropagationDirection direction,
            @ForAll Permission permission
    ) {
        Random rng = new Random(seed);
        InMemoryVersionedDatabase db = new InMemoryVersionedDatabase();
        List<EntityId> nodes = new ArrayList<>(nodeCount);
        EntityId alice;

        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("random cyclic graph"))) {
            EntityId nodeType = tx.defineObjectType("Node");
            EntityId link = tx.definePredicate("link", nodeType, nodeType);
            alice = tx.definePrincipal("alice");
            tx.definePermissionPropagation(link, direction, permission);

            for (int i = 0; i < nodeCount; i++) {
                nodes.add(tx.createObject(nodeType));
            }
            for (int i = 0; i < edgeCount; i++) {
                EntityId s = nodes.get(rng.nextInt(nodeCount));
                EntityId t = nodes.get(rng.nextInt(nodeCount));
                tx.connect(s, link, t);
            }
            for (EntityId node : nodes) {
                int roll = rng.nextInt(4);
                if (roll == 0) {
                    tx.grantPermission(alice, permission, node);
                } else if (roll == 1) {
                    tx.denyPermission(alice, permission, node);
                }
            }
            tx.commit();
        }

        var live = db.authorizedCurrentSnapshot("alice");
        var replayed = db.replayAsOf(db.headCommitId());
        for (EntityId node : nodes) {
            AccessDecision d1 = live.explainAccess(permission, node);
            AccessDecision d2 = live.explainAccess(permission, node);
            AccessDecision dReplay = replayed.evaluateAccess("alice", permission, node);
            assertThat(d1).isEqualTo(d2);
            assertThat(d1.allowed()).isEqualTo(dReplay.allowed());
            assertThat(d1.reason()).isEqualTo(dReplay.reason());
        }
    }

    @Property(tries = 60)
    void denyAlwaysWinsAndAddingAllowNeverOverridesExistingDeny(
            @ForAll long seed,
            @ForAll @IntRange(min = 2, max = 6) int chainLength,
            @ForAll Permission permission
    ) {
        Random rng = new Random(seed);
        InMemoryVersionedDatabase db = new InMemoryVersionedDatabase();
        List<EntityId> chain = new ArrayList<>(chainLength);
        EntityId alice;
        EntityId group;

        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("chain + allow/deny"))) {
            EntityId nodeType = tx.defineObjectType("Node");
            EntityId next = tx.definePredicate("next", nodeType, nodeType);
            alice = tx.definePrincipal("alice");
            group = tx.definePrincipal("group");
            tx.addGroupMember(alice, group);
            tx.definePermissionPropagation(next, PropagationDirection.OUTGOING, permission);

            for (int i = 0; i < chainLength; i++) {
                chain.add(tx.createObject(nodeType));
                if (i > 0) {
                    tx.connect(chain.get(i - 1), next, chain.get(i));
                }
            }
            // Start by granting ALLOW on root, then add DENY on a chosen ancestor
            tx.grantPermission(alice, permission, chain.getFirst());
            int denyIdx = rng.nextInt(chainLength);
            tx.denyPermission(rng.nextBoolean() ? alice : group, permission, chain.get(denyIdx));
            tx.commit();

            EntityId leaf = chain.getLast();
            AccessDecision deniedBefore = db.authorizedCurrentSnapshot("alice").explainAccess(permission, leaf);
            assertThat(deniedBefore.allowed()).isFalse();

            // Even after adding direct ALLOW on leaf and group ALLOW on leaf, DENY must still win!
            try (GraphTransaction tx2 = db.beginTransaction(ADMIN, Reason.of("extra allows"))) {
                tx2.grantPermission(alice, permission, leaf);
                tx2.grantPermission(group, permission, leaf);
                tx2.commit();
            }
            AccessDecision deniedAfter = db.authorizedCurrentSnapshot("alice").explainAccess(permission, leaf);
            assertThat(deniedAfter.allowed()).isFalse();
        }
    }
}
