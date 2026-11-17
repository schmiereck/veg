package io.veg.engine.memory;

import io.veg.engine.EntityNotFoundException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.model.Actor;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.Value;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared GDPR / retention payload redaction helper ({@code S-09}).
 *
 * <p>Replaces sensitive payloads on a {@link EntityKind#VALUE} entity across commits and snapshots
 * with a type-preserving placeholder while keeping graph structure, commit IDs, edges, and an
 * immutable SHA-256 {@link RedactionReceipt}.</p>
 */
public final class RedactionEngine {

    public record RedactionOutcome(
            RedactionReceipt receipt,
            List<Commit> rewrittenCommits,
            int earliestAffectedIndex
    ) {
    }

    private RedactionEngine() {
    }

    public static RedactionOutcome redactAcrossHistory(
            List<Commit> commits,
            EntityId valueEntityId,
            Actor actor,
            Reason reason
    ) {
        Objects.requireNonNull(commits, "commits must not be null");
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (valueEntityId.isBootstrap()) {
            throw new SchemaViolationException("Cannot redact bootstrap entity: " + valueEntityId);
        }

        EntityNode locatedNode = null;
        Value latestObservedValue = null;
        for (Commit commit : commits) {
            for (Change change : commit.changes()) {
                switch (change) {
                    case Change.EntityCreated(EntityNode n) when n.id().equals(valueEntityId) -> {
                        locatedNode = n;
                        if (n.payload().isPresent()) {
                            latestObservedValue = n.payload().get();
                        }
                    }
                    case Change.ValueUpdated(EntityId id, _, Value next) when id.equals(valueEntityId) ->
                            latestObservedValue = next;
                    default -> {
                    }
                }
            }
        }

        if (locatedNode == null) {
            throw new EntityNotFoundException("Entity not found in commit history: " + valueEntityId);
        }
        if (locatedNode.kind() != EntityKind.VALUE) {
            throw new TypeMismatchException(
                    "Only VALUE entities may be payload-redacted, but " + valueEntityId + " is " + locatedNode.kind());
        }
        if (locatedNode.typeId().isBootstrap()) {
            throw new SchemaViolationException(
                    "Cannot redact internal schema/metadata value entity: " + valueEntityId);
        }

        Value sampleValue = latestObservedValue != null ? latestObservedValue : locatedNode.payload().orElseThrow();
        String sha256 = sha256OfValue(sampleValue);
        Value placeholder = redactedPlaceholder(sampleValue);

        List<Commit> rewritten = new ArrayList<>(commits.size());
        int earliestAffectedIndex = -1;
        int affectedCount = 0;

        for (int i = 0; i < commits.size(); i++) {
            Commit commit = commits.get(i);
            boolean modified = false;
            List<Change> newChanges = new ArrayList<>(commit.changes().size());

            for (Change change : commit.changes()) {
                Change rewrittenChange = rewriteChangeIfTarget(change, valueEntityId, placeholder);
                if (rewrittenChange != change) {
                    modified = true;
                }
                newChanges.add(rewrittenChange);
            }

            if (modified) {
                if (earliestAffectedIndex < 0) {
                    earliestAffectedIndex = i;
                }
                affectedCount++;
                rewritten.add(new Commit(
                        commit.id(),
                        commit.parentIds(),
                        commit.branchName(),
                        commit.dataCategory(),
                        commit.actor(),
                        commit.reason(),
                        commit.correlationId(),
                        commit.timestamp(),
                        newChanges
                ));
            } else {
                rewritten.add(commit);
            }
        }

        RedactionReceipt receipt = new RedactionReceipt(
                valueEntityId,
                Instant.now(),
                actor,
                reason,
                sha256,
                affectedCount
        );
        return new RedactionOutcome(receipt, List.copyOf(rewritten), earliestAffectedIndex);
    }

    private static Change rewriteChangeIfTarget(Change change, EntityId targetId, Value placeholder) {
        return switch (change) {
            case Change.EntityCreated(EntityNode n) when n.id().equals(targetId) ->
                    new Change.EntityCreated(new EntityNode(n.id(), n.typeId(), n.kind(), Optional.of(placeholder)));
            case Change.EntityRemoved(EntityNode n) when n.id().equals(targetId) ->
                    new Change.EntityRemoved(new EntityNode(n.id(), n.typeId(), n.kind(), Optional.of(placeholder)));
            case Change.ValueUpdated(EntityId id, Value prev, Value next) when id.equals(targetId) ->
                    new Change.ValueUpdated(id, redactedPlaceholder(prev), redactedPlaceholder(next));
            default -> change;
        };
    }

    static Value redactedPlaceholder(Value value) {
        return switch (value) {
            case Value.StringValue _ -> Value.of("[REDACTED]");
            case Value.IntValue _ -> Value.of(0L);
            case Value.DecimalValue _ -> Value.of(BigDecimal.ZERO);
            case Value.BooleanValue _ -> Value.of(false);
            case Value.BinaryValue _ -> Value.of(new byte[0]);
            case Value.TimestampValue _ -> Value.of(Instant.EPOCH);
        };
    }

    private static String sha256OfValue(Value value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(value.valueType().name().getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            switch (value) {
                case Value.StringValue(String s) -> md.update(s.getBytes(StandardCharsets.UTF_8));
                case Value.IntValue(long v) -> md.update(Long.toString(v).getBytes(StandardCharsets.UTF_8));
                case Value.DecimalValue(BigDecimal bd) ->
                        md.update(bd.toPlainString().getBytes(StandardCharsets.UTF_8));
                case Value.BooleanValue(boolean b) ->
                        md.update((b ? "true" : "false").getBytes(StandardCharsets.UTF_8));
                case Value.BinaryValue(byte[] bytes) -> md.update(bytes);
                case Value.TimestampValue(Instant inst) ->
                        md.update(inst.toString().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
