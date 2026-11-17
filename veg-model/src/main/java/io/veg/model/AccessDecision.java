package io.veg.model;

import java.util.List;
import java.util.Objects;

/**
 * Explainable outcome of evaluating whether an actor holds a {@link Permission} on a graph element.
 *
 * @param principalId external identifier of the evaluated actor/principal
 * @param permission  requested permission
 * @param targetId    entity or edge ID being accessed
 * @param allowed     {@code true} when access is granted; {@code false} under deny-wins or default-deny
 * @param reason      structured classification explaining the decision
 * @param winningPath propagation path (ordered from target to winning scope/ACL node) or empty if none
 * @param explanation human-readable diagnostic summary of the winning rule or denial cause
 */
public record AccessDecision(
        String principalId,
        Permission permission,
        EntityId targetId,
        boolean allowed,
        AccessReason reason,
        List<EntityId> winningPath,
        String explanation
) {
    public AccessDecision {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(reason, "reason");
        winningPath = List.copyOf(Objects.requireNonNull(winningPath, "winningPath"));
        Objects.requireNonNull(explanation, "explanation");
        if (principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }
        if (allowed && (reason != AccessReason.DIRECT_ALLOW && reason != AccessReason.INHERITED_ALLOW)) {
            throw new IllegalArgumentException("Allowed decision cannot have deny reason: " + reason);
        }
        if (!allowed && (reason == AccessReason.DIRECT_ALLOW || reason == AccessReason.INHERITED_ALLOW)) {
            throw new IllegalArgumentException("Denied decision cannot have allow reason: " + reason);
        }
    }

    public static AccessDecision allow(
            String principalId,
            Permission permission,
            EntityId targetId,
            AccessReason reason,
            List<EntityId> winningPath,
            String explanation
    ) {
        return new AccessDecision(principalId, permission, targetId, true, reason, winningPath, explanation);
    }

    public static AccessDecision deny(
            String principalId,
            Permission permission,
            EntityId targetId,
            AccessReason reason,
            List<EntityId> winningPath,
            String explanation
    ) {
        return new AccessDecision(principalId, permission, targetId, false, reason, winningPath, explanation);
    }
}
