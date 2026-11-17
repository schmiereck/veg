package io.veg.engine.memory;

import io.veg.model.EntityId;

import java.util.List;

/**
 * Optional fast-path interface implemented by raw graph snapshots so {@link PermissionEvaluator}
 * can resolve {@code META_PROPAGATION_RULE_TYPE} entities in O(rules) without scanning all graph entities.
 */
interface PropagationRuleSource {

    List<EntityId> propagationRuleIds();
}
