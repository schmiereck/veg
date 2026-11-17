package io.veg.model;

/**
 * Effect of an access control entry. Under VEG semantics, explicit or inherited {@link #DENY}
 * always overrides any {@link #ALLOW} ("Verbieten gewinnt").
 */
public enum AccessEffect {
    ALLOW,
    DENY
}
