package io.veg.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Cryptographic audit receipt produced when a {@link EntityKind#VALUE} payload is purged for
 * retention or data-protection compliance ({@code S-09}) while keeping commit/graph identity intact.
 */
public record RedactionReceipt(
        EntityId redactedEntityId,
        Instant redactedAt,
        Actor actor,
        Reason reason,
        String originalPayloadSha256,
        int affectedCommitCount
) {
    public RedactionReceipt {
        Objects.requireNonNull(redactedEntityId, "redactedEntityId must not be null");
        Objects.requireNonNull(redactedAt, "redactedAt must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(originalPayloadSha256, "originalPayloadSha256 must not be null");
        if (originalPayloadSha256.isBlank()) {
            throw new IllegalArgumentException("originalPayloadSha256 must not be blank");
        }
        if (affectedCommitCount < 0) {
            throw new IllegalArgumentException("affectedCommitCount must be >= 0");
        }
    }
}
