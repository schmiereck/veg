package io.veg.model;

/**
 * Classification of why an {@link AccessDecision} granted or denied access.
 */
public enum AccessReason {
    DIRECT_ALLOW,
    DIRECT_DENY,
    INHERITED_ALLOW,
    INHERITED_DENY,
    DEFAULT_DENY,
    BRANCH_RESTRICTED,
    HISTORICAL_HEAD_DENY
}
