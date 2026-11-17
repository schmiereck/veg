package io.veg.model;

/**
 * Direction in which permissions propagate along edges of a given predicate.
 *
 * <p>For an edge {@code S --P--> T}:
 * <ul>
 *   <li>{@link #OUTGOING}: permissions on {@code S} propagate to {@code T}</li>
 *   <li>{@link #INCOMING}: permissions on {@code T} propagate to {@code S}</li>
 *   <li>{@link #BOTH}: permissions propagate in both directions</li>
 * </ul>
 */
public enum PropagationDirection {
    OUTGOING,
    INCOMING,
    BOTH
}
