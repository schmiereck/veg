package io.veg.engine;

import io.veg.model.AccessDecision;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.Permission;

/**
 * Actor-scoped {@link GraphReader} that enforces graph access control rules, branch restrictions,
 * and historical time-travel security checks on every read and traversal.
 */
public interface AuthorizedGraphReader extends GraphReader {

    String principalId();

    AccessDecision explainAccess(Permission permission, EntityId targetId);

    EntityNode getEntityOrThrow(EntityId id);

    Edge getEdgeOrThrow(EntityId edgeId);

    void requireAccess(Permission permission, EntityId targetId);
}
