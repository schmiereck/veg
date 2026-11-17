package io.veg.engine;

import io.veg.model.AccessDecision;

import java.util.Objects;

/**
 * Thrown when an actor attempts an authorized operation without sufficient graph, predicate, or branch permissions.
 */
public class AccessDeniedException extends VegException {

    private final AccessDecision decision;

    public AccessDeniedException(AccessDecision decision) {
        super(formatMessage(Objects.requireNonNull(decision, "decision")));
        this.decision = decision;
    }

    public AccessDecision decision() {
        return decision;
    }

    private static String formatMessage(AccessDecision decision) {
        return "Access denied for principal '%s' (%s on %s): %s - %s"
                .formatted(
                        decision.principalId(),
                        decision.permission(),
                        decision.targetId(),
                        decision.reason(),
                        decision.explanation()
                );
    }
}
