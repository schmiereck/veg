package io.veg.engine.memory;

import io.veg.model.AccessDecision;
import io.veg.model.EntityId;
import io.veg.model.Permission;

/**
 * Immutable read-only snapshot of a graph state at a specific commit (V-04).
 */
public final class InMemoryGraphSnapshot extends AbstractGraphReader {

    private final MutableGraphState frozenState;
    private final SnapshotAclCache aclCache = new SnapshotAclCache();

    InMemoryGraphSnapshot(MutableGraphState frozenCopy) {
        this.frozenState = frozenCopy;
    }

    @Override
    protected MutableGraphState state() {
        return frozenState;
    }

    @Override
    public AccessDecision evaluateAccess(String principalId, Permission permission, EntityId targetId) {
        return aclCache.evaluate(this, principalId, permission, targetId);
    }

    MutableGraphState copyState() {
        return frozenState.copy();
    }
}
