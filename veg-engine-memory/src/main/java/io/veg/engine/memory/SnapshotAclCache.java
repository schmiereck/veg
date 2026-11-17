package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.model.AccessDecision;
import io.veg.model.EntityId;
import io.veg.model.Permission;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-snapshot memoization table for repeated {@link PermissionEvaluator} queries ({@code P-05}).
 */
final class SnapshotAclCache {

    private record Key(String principalId, Permission permission, EntityId targetId) {
    }

    private final ConcurrentHashMap<Key, AccessDecision> cache = new ConcurrentHashMap<>();

    AccessDecision evaluate(GraphReader snapshot, String principalId, Permission permission, EntityId targetId) {
        if (principalId == null || principalId.isBlank() || permission == null || targetId == null) {
            return PermissionEvaluator.evaluate(snapshot, principalId, permission, targetId);
        }
        return cache.computeIfAbsent(
                new Key(principalId, permission, targetId),
                k -> PermissionEvaluator.evaluate(snapshot, k.principalId(), k.permission(), k.targetId())
        );
    }

    void clear() {
        cache.clear();
    }
}
