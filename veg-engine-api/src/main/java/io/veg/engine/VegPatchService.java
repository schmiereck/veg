package io.veg.engine;

import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.Branch;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.GraphDiff;
import io.veg.model.MergeConflict;
import io.veg.model.MergePolicy;
import io.veg.model.MergeResult;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git-friendly plain-text patch ({@code .vegpatch}) exporter and 3-way merge applier.
 *
 * <p>Exports commit diffs into a deterministic, line-oriented human- and Git-readable format
 * using symbolic schema references ({@code @Type:Name}, {@code @Pred:Name}) alongside stable
 * 128-bit UUID or sequential {@link EntityId}s, and applies or three-way merges incoming
 * patches back into a target branch with idempotency tracking via {@code patch-id}.</p>
 */
public final class VegPatchService {

    public enum PatchApplyMode {
        APPLY,
        MERGE
    }

    public record PatchResult(
            String patchId,
            PatchApplyMode mode,
            boolean alreadyApplied,
            boolean merged,
            CommitId resultCommitId,
            String targetBranch,
            String incomingBranch,
            List<MergeConflict> conflicts
    ) {
    }

    private static final String FORMAT_HEADER = "VEG-PATCH v1";

    private static final Pattern EDGE_LINE_PATTERN = Pattern.compile(
            "^([+-]EDGE)\\s+(\\S+)\\s+\\(([^)]+)\\)\\s+-\\[(.+?)]->\\s+\\(([^)]+)\\)$"
    );

    private VegPatchService() {
    }

    public static String exportPatch(VersionedGraphDatabase db, CommitId fromCommit, CommitId toCommit) {
        Objects.requireNonNull(db, "db must not be null");
        Objects.requireNonNull(fromCommit, "fromCommit must not be null");
        Objects.requireNonNull(toCommit, "toCommit must not be null");

        GraphDiff diff = db.diff(fromCommit, toCommit);
        GraphReader fromReader = db.asOf(fromCommit);
        GraphReader toReader = db.asOf(toCommit);
        Commit targetCommit = db.getCommit(toCommit);

        // Identify newly introduced schema entities and collect their internal bootstrap metadata nodes/edges
        Set<EntityId> addedSchemaIds = new HashSet<>();
        for (EntityNode node : diff.addedEntities()) {
            if (BootstrapIds.META_OBJECT_TYPE.equals(node.typeId())
                    || BootstrapIds.META_VALUE_TYPE.equals(node.typeId())
                    || BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
                addedSchemaIds.add(node.id());
            }
        }

        Set<EntityId> suppressedEntities = new HashSet<>(addedSchemaIds);
        Set<EntityId> suppressedEdges = new HashSet<>();
        for (Edge edge : diff.addedEdges()) {
            if (addedSchemaIds.contains(edge.sourceId()) && isSchemaStructuralPredicate(edge.predicateId())) {
                suppressedEdges.add(edge.id());
                toReader.findEntity(edge.targetId())
                        .filter(t -> t.kind() == EntityKind.VALUE)
                        .ifPresent(t -> suppressedEntities.add(t.id()));
            } else if (BootstrapIds.HAS_MUTABILITY.equals(edge.predicateId())) {
                suppressedEdges.add(edge.id());
            }
        }

        List<String> schemaLines = new ArrayList<>();
        for (EntityId schemaId : addedSchemaIds.stream().sorted().toList()) {
            EntityNode schemaNode = toReader.findEntity(schemaId).orElseThrow();
            String name = toReader.resolveName(schemaId).orElse(schemaId.compactString());
            if (BootstrapIds.META_OBJECT_TYPE.equals(schemaNode.typeId())) {
                schemaLines.add("+OBJECT_TYPE " + schemaId + " name=" + quoteString(name));
            } else if (BootstrapIds.META_VALUE_TYPE.equals(schemaNode.typeId())) {
                ValueType scalar = resolveValueTypeScalar(toReader, schemaId);
                boolean unique = toReader.isUniqueValueType(schemaId);
                schemaLines.add("+VALUE_TYPE " + schemaId + " name=" + quoteString(name)
                        + " scalar=" + scalar.name() + " unique=" + unique);
            } else if (BootstrapIds.META_PREDICATE_TYPE.equals(schemaNode.typeId())) {
                EntityId domainId = singleTarget(toReader, schemaId, BootstrapIds.HAS_DOMAIN).orElse(BootstrapIds.ANY_ENTITY);
                EntityId rangeId = singleTarget(toReader, schemaId, BootstrapIds.HAS_RANGE).orElse(BootstrapIds.ANY_ENTITY);
                PredicateConstraints c = toReader.resolvePredicateConstraints(schemaId)
                        .orElse(PredicateConstraints.unconstrained());
                String maxOut = c.maxOutgoing().isPresent() ? Integer.toString(c.maxOutgoing().getAsInt()) : "*";
                String maxIn = c.maxIncoming().isPresent() ? Integer.toString(c.maxIncoming().getAsInt()) : "*";
                schemaLines.add("+PREDICATE " + schemaId + " name=" + quoteString(name)
                        + " domain=" + formatSchemaRef(toReader, domainId)
                        + " range=" + formatSchemaRef(toReader, rangeId)
                        + " maxOut=" + maxOut + " maxIn=" + maxIn + " uniqueTriple=" + c.uniqueTriple());
            }
        }

        List<String> entityLines = new ArrayList<>();
        diff.addedEntities().stream()
                .filter(n -> n.kind() != EntityKind.EDGE && !suppressedEntities.contains(n.id()))
                .sorted(Comparator.comparing(EntityNode::id))
                .forEach(node -> {
                    String typeRef = formatSchemaRef(toReader, node.typeId());
                    if (node.kind() == EntityKind.OBJECT) {
                        entityLines.add("+OBJECT " + node.id() + " type=" + typeRef);
                    } else if (node.kind() == EntityKind.VALUE) {
                        ValueMutability mut = toReader.resolveValueMutability(node.id()).orElse(ValueMutability.IMMUTABLE);
                        Value payload = node.payload().orElseThrow();
                        entityLines.add("+VALUE " + node.id() + " type=" + typeRef
                                + " mutability=" + mut.name() + " value=" + formatTypedValue(payload));
                    }
                });

        diff.updatedValues().stream()
                .filter(u -> !suppressedEntities.contains(u.entityId()))
                .sorted(Comparator.comparing(Change.ValueUpdated::entityId))
                .forEach(u -> entityLines.add("~VALUE " + u.entityId()
                        + " old=" + formatTypedValue(u.previousValue())
                        + " new=" + formatTypedValue(u.newValue())));

        diff.removedEntities().stream()
                .filter(n -> n.kind() != EntityKind.EDGE)
                .sorted(Comparator.comparing(EntityNode::id))
                .forEach(n -> entityLines.add("-ENTITY " + n.id()
                        + " kind=" + n.kind().name()
                        + " type=" + formatSchemaRef(fromReader, n.typeId())));

        List<String> edgeLines = new ArrayList<>();
        diff.addedEdges().stream()
                .filter(e -> !suppressedEdges.contains(e.id()))
                .sorted(Comparator.comparing(Edge::id))
                .forEach(e -> edgeLines.add("+EDGE " + e.id()
                        + " (" + e.sourceId() + ") -[" + formatPredRef(toReader, e.predicateId()) + "]-> (" + e.targetId() + ")"));

        diff.removedEdges().stream()
                .filter(e -> !BootstrapIds.HAS_MUTABILITY.equals(e.predicateId()))
                .sorted(Comparator.comparing(Edge::id))
                .forEach(e -> edgeLines.add("-EDGE " + e.id()
                        + " (" + e.sourceId() + ") -[" + formatPredRef(fromReader, e.predicateId()) + "]-> (" + e.targetId() + ")"));

        StringBuilder body = new StringBuilder();
        if (!schemaLines.isEmpty()) {
            body.append("# --- SCHEMA ---\n");
            schemaLines.forEach(l -> body.append(l).append('\n'));
            body.append('\n');
        }
        if (!entityLines.isEmpty()) {
            body.append("# --- ENTITIES ---\n");
            entityLines.forEach(l -> body.append(l).append('\n'));
            body.append('\n');
        }
        if (!edgeLines.isEmpty()) {
            body.append("# --- EDGES ---\n");
            edgeLines.forEach(l -> body.append(l).append('\n'));
        }

        String bodyText = body.toString().trim();
        String patchId = "sha256:" + sha256Hex(fromCommit + "->" + toCommit + "\n" + bodyText);

        StringBuilder full = new StringBuilder();
        full.append(FORMAT_HEADER).append('\n');
        full.append("patch-id: ").append(patchId).append('\n');
        full.append("base-commit: ").append(fromCommit).append('\n');
        full.append("target-commit: ").append(toCommit).append('\n');
        full.append("source-branch: ").append(targetCommit.branchName()).append('\n');
        full.append("actor: ").append(targetCommit.actor().id()).append('\n');
        full.append("timestamp: ").append(targetCommit.timestamp()).append('\n');
        full.append("category: ").append(targetCommit.dataCategory().name()).append('\n');
        full.append("reason: ").append(targetCommit.reason().text()).append('\n');
        if (!bodyText.isEmpty()) {
            full.append('\n').append(bodyText).append('\n');
        }
        return full.toString();
    }

    public static PatchResult applyOrMergePatch(
            VersionedGraphDatabase db,
            String patchText,
            String targetBranch,
            PatchApplyMode mode,
            Actor overrideActor,
            Reason overrideReason
    ) {
        Objects.requireNonNull(db, "db must not be null");
        Objects.requireNonNull(patchText, "patchText must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        ParsedPatch patch = parsePatch(patchText);
        String effectiveBranch = (targetBranch != null && !targetBranch.isBlank()) ? targetBranch.trim() : db.currentBranch();
        Actor effectiveActor = overrideActor != null ? overrideActor : Actor.user(patch.actorId);
        Reason effectiveReason = overrideReason != null ? overrideReason : Reason.of(patch.reason);

        String incomingCorrelation = patch.patchId + ":incoming";
        String incomingBranch = "patch/" + shortPatchHash(patch.patchId);

        // Idempotency check across direct apply commits and already-merged incoming patch branches
        List<Commit> directMatches = db.commitsByCorrelationId(patch.patchId);
        if (!directMatches.isEmpty()) {
            Commit existing = directMatches.getLast();
            return new PatchResult(patch.patchId, mode, true, true, existing.id(), effectiveBranch, null, List.of());
        }
        Optional<Branch> existingIncomingBranch = db.findBranch(incomingBranch);
        if (existingIncomingBranch.isPresent()) {
            CommitId incomingHead = existingIncomingBranch.get().headCommitId();
            CommitId targetHead = db.branchHead(effectiveBranch);
            if (isAncestor(db, incomingHead, targetHead)) {
                return new PatchResult(patch.patchId, mode, true, true, targetHead, effectiveBranch, incomingBranch, List.of());
            }
        }

        boolean canThreeWayMerge = mode == PatchApplyMode.MERGE
                && patch.baseCommitId != null
                && hasCommit(db, patch.baseCommitId)
                && !patch.baseCommitId.equals(db.branchHead(effectiveBranch));

        if (canThreeWayMerge) {
            if (existingIncomingBranch.isEmpty()) {
                db.createBranch(incomingBranch, patch.baseCommitId);
            }
            if (db.commitsByCorrelationId(incomingCorrelation).isEmpty()) {
                applyOperations(db, incomingBranch, patch, effectiveActor, effectiveReason, incomingCorrelation);
            }
            MergeResult mergeResult = db.merge(incomingBranch, effectiveBranch, effectiveActor, effectiveReason, MergePolicy.includeAll());
            return new PatchResult(
                    patch.patchId,
                    PatchApplyMode.MERGE,
                    false,
                    mergeResult.succeeded(),
                    mergeResult.mergeCommitId().orElse(null),
                    effectiveBranch,
                    incomingBranch,
                    mergeResult.conflicts()
            );
        }

        CommitId commitId = applyOperations(db, effectiveBranch, patch, effectiveActor, effectiveReason, patch.patchId);
        return new PatchResult(patch.patchId, mode, false, true, commitId, effectiveBranch, null, List.of());
    }

    private static CommitId applyOperations(
            VersionedGraphDatabase db,
            String branchName,
            ParsedPatch patch,
            Actor actor,
            Reason reason,
            String correlationId
    ) {
        try (GraphTransaction tx = db.beginTransaction(branchName, actor, reason, correlationId, patch.category)) {
            Map<EntityId, EntityId> idMap = new HashMap<>();

            // 1. Schema definitions
            for (PatchOp op : patch.schemaOps) {
                executeSchemaOp(tx, op, idMap);
            }
            // 2. Entity creations and value updates
            for (PatchOp op : patch.entityOps) {
                executeEntityOp(tx, op, idMap);
            }
            // 3. Edge additions & removals (removals first cleanly handles cardinality=1 attribute replacement)
            for (PatchOp op : patch.edgeOps) {
                if (!op.addEdge) {
                    executeEdgeRemoval(tx, op, idMap);
                }
            }
            for (PatchOp op : patch.edgeOps) {
                if (op.addEdge) {
                    executeEdgeAddition(tx, op, idMap);
                }
            }
            return tx.commit();
        }
    }

    private static void executeSchemaOp(GraphTransaction tx, PatchOp op, Map<EntityId, EntityId> idMap) {
        switch (op.kind) {
            case ADD_OBJECT_TYPE -> {
                Optional<EntityId> existing = tx.findTypeByName(op.name);
                if (existing.isPresent()) {
                    idMap.put(op.entityId, existing.get());
                } else if (tx.findEntity(op.entityId).isPresent()) {
                    idMap.put(op.entityId, tx.defineObjectType(op.name));
                } else {
                    idMap.put(op.entityId, tx.defineObjectTypeWithId(op.entityId, op.name));
                }
            }
            case ADD_VALUE_TYPE -> {
                Optional<EntityId> existing = tx.findTypeByName(op.name);
                if (existing.isPresent()) {
                    idMap.put(op.entityId, existing.get());
                } else if (tx.findEntity(op.entityId).isPresent()) {
                    idMap.put(op.entityId, tx.defineValueType(op.name, op.scalarType, op.uniqueFlag));
                } else {
                    idMap.put(op.entityId, tx.defineValueTypeWithId(op.entityId, op.name, op.scalarType, op.uniqueFlag));
                }
            }
            case ADD_PREDICATE -> {
                Optional<EntityId> existing = tx.findPredicateByName(op.name);
                if (existing.isPresent()) {
                    idMap.put(op.entityId, existing.get());
                } else {
                    EntityId domainId = resolveSchemaEndpoint(tx, op.domainRef, idMap);
                    EntityId rangeId = resolveSchemaEndpoint(tx, op.rangeRef, idMap);
                    EntityId created = tx.findEntity(op.entityId).isPresent()
                            ? tx.definePredicate(op.name, domainId, rangeId, op.constraints)
                            : tx.definePredicateWithId(op.entityId, op.name, domainId, rangeId, op.constraints);
                    idMap.put(op.entityId, created);
                }
            }
            default -> throw new IllegalStateException("Unexpected schema op: " + op.kind);
        }
    }

    private static void executeEntityOp(GraphTransaction tx, PatchOp op, Map<EntityId, EntityId> idMap) {
        switch (op.kind) {
            case ADD_OBJECT -> {
                EntityId typeId = resolveSchemaEndpoint(tx, op.typeRef, idMap);
                EntityId created = tx.findEntity(op.entityId).isPresent()
                        ? tx.createObject(typeId)
                        : tx.createObjectWithId(op.entityId, typeId);
                idMap.put(op.entityId, created);
            }
            case ADD_VALUE -> {
                EntityId typeId = resolveSchemaEndpoint(tx, op.typeRef, idMap);
                EntityId created = tx.findEntity(op.entityId).isPresent()
                        ? tx.createValue(typeId, op.newValue, op.mutability)
                        : tx.createValueWithId(op.entityId, typeId, op.newValue, op.mutability);
                idMap.put(op.entityId, created);
            }
            case UPDATE_VALUE -> {
                EntityId targetId = idMap.getOrDefault(op.entityId, op.entityId);
                tx.updateValue(targetId, op.newValue);
            }
            case REMOVE_ENTITY -> {
                // Entity deletion occurs implicitly when disconnected or via snapshot diff; no-op if absent
            }
            default -> throw new IllegalStateException("Unexpected entity op: " + op.kind);
        }
    }

    private static void executeEdgeRemoval(GraphTransaction tx, PatchOp op, Map<EntityId, EntityId> idMap) {
        EntityId mappedEdgeId = idMap.getOrDefault(op.entityId, op.entityId);
        if (tx.findEdge(mappedEdgeId).isPresent()) {
            tx.disconnect(mappedEdgeId);
            return;
        }
        EntityId srcId = idMap.getOrDefault(op.sourceId, op.sourceId);
        EntityId tgtId = idMap.getOrDefault(op.targetId, op.targetId);
        EntityId predId = resolvePredicateEndpoint(tx, op.predRef, idMap);
        for (Edge candidate : tx.findOutgoingEdges(srcId, predId)) {
            if (candidate.targetId().equals(tgtId)) {
                tx.disconnect(candidate.id());
                return;
            }
        }
    }

    private static void executeEdgeAddition(GraphTransaction tx, PatchOp op, Map<EntityId, EntityId> idMap) {
        EntityId srcId = idMap.getOrDefault(op.sourceId, op.sourceId);
        EntityId tgtId = idMap.getOrDefault(op.targetId, op.targetId);
        EntityId predId = resolvePredicateEndpoint(tx, op.predRef, idMap);
        EntityId edgeId = tx.findEntity(op.entityId).isPresent()
                ? tx.connect(srcId, predId, tgtId)
                : tx.connectWithId(op.entityId, srcId, predId, tgtId);
        idMap.put(op.entityId, edgeId);
    }

    // --- Parsing & Formatting Helpers ---

    private enum OpKind {
        ADD_OBJECT_TYPE, ADD_VALUE_TYPE, ADD_PREDICATE,
        ADD_OBJECT, ADD_VALUE, UPDATE_VALUE, REMOVE_ENTITY,
        EDGE_OP
    }

    private record PatchOp(
            OpKind kind,
            EntityId entityId,
            String name,
            ValueType scalarType,
            boolean uniqueFlag,
            String domainRef,
            String rangeRef,
            PredicateConstraints constraints,
            String typeRef,
            ValueMutability mutability,
            Value newValue,
            EntityId sourceId,
            String predRef,
            EntityId targetId,
            boolean addEdge
    ) {
    }

    private record ParsedPatch(
            String patchId,
            CommitId baseCommitId,
            String actorId,
            DataCategory category,
            String reason,
            List<PatchOp> schemaOps,
            List<PatchOp> entityOps,
            List<PatchOp> edgeOps
    ) {
    }

    private static ParsedPatch parsePatch(String patchText) {
        List<String> lines = patchText.lines().toList();
        if (lines.isEmpty() || !FORMAT_HEADER.equals(lines.getFirst().trim())) {
            throw new IllegalArgumentException("Expected header '" + FORMAT_HEADER + "'");
        }

        String patchId = null;
        CommitId baseCommitId = null;
        String actorId = "patch-applier";
        DataCategory category = DataCategory.REFERENCE;
        String reason = "Apply .vegpatch";

        List<PatchOp> schemaOps = new ArrayList<>();
        List<PatchOp> entityOps = new ArrayList<>();
        List<PatchOp> edgeOps = new ArrayList<>();

        int i = 1;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            i++;
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Invalid patch header line: " + line);
            }
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            switch (key) {
                case "patch-id" -> patchId = val;
                case "base-commit" -> baseCommitId = parseCommitId(val);
                case "actor" -> actorId = val;
                case "category" -> category = DataCategory.valueOf(val);
                case "reason" -> reason = val;
                default -> {
                    // ignore optional metadata headers like target-commit, source-branch, timestamp
                }
            }
        }
        if (patchId == null || patchId.isBlank()) {
            patchId = "sha256:" + sha256Hex(patchText);
        }

        for (; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("+OBJECT_TYPE ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                Map<String, String> kv = parseKeyValues(tokens.subList(2, tokens.size()));
                schemaOps.add(new PatchOp(OpKind.ADD_OBJECT_TYPE, id, unquote(kv.get("name")),
                        null, false, null, null, null, null, null, null, null, null, null, false));
            } else if (line.startsWith("+VALUE_TYPE ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                Map<String, String> kv = parseKeyValues(tokens.subList(2, tokens.size()));
                schemaOps.add(new PatchOp(OpKind.ADD_VALUE_TYPE, id, unquote(kv.get("name")),
                        ValueType.valueOf(kv.get("scalar")), Boolean.parseBoolean(kv.get("unique")),
                        null, null, null, null, null, null, null, null, null, false));
            } else if (line.startsWith("+PREDICATE ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                Map<String, String> kv = parseKeyValues(tokens.subList(2, tokens.size()));
                OptionalInt maxOut = "*".equals(kv.get("maxOut")) ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(kv.get("maxOut")));
                OptionalInt maxIn = "*".equals(kv.get("maxIn")) ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(kv.get("maxIn")));
                PredicateConstraints c = new PredicateConstraints(maxOut, maxIn, Boolean.parseBoolean(kv.get("uniqueTriple")));
                schemaOps.add(new PatchOp(OpKind.ADD_PREDICATE, id, unquote(kv.get("name")),
                        null, false, kv.get("domain"), kv.get("range"), c, null, null, null, null, null, null, false));
            } else if (line.startsWith("+OBJECT ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                Map<String, String> kv = parseKeyValues(tokens.subList(2, tokens.size()));
                entityOps.add(new PatchOp(OpKind.ADD_OBJECT, id, null, null, false, null, null, null,
                        kv.get("type"), null, null, null, null, null, false));
            } else if (line.startsWith("+VALUE ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                Map<String, String> kv = parseKeyValues(tokens.subList(2, tokens.size()));
                ValueMutability mut = ValueMutability.valueOf(kv.getOrDefault("mutability", "IMMUTABLE"));
                Value val = parseTypedValue(kv.get("value"));
                entityOps.add(new PatchOp(OpKind.ADD_VALUE, id, null, null, false, null, null, null,
                        kv.get("type"), mut, val, null, null, null, false));
            } else if (line.startsWith("~VALUE ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                Map<String, String> kv = parseKeyValues(tokens.subList(2, tokens.size()));
                entityOps.add(new PatchOp(OpKind.UPDATE_VALUE, id, null, null, false, null, null, null,
                        null, null, parseTypedValue(kv.get("new")), null, null, null, false));
            } else if (line.startsWith("-ENTITY ")) {
                List<String> tokens = splitShellTokens(line);
                EntityId id = EntityId.parse(tokens.get(1));
                entityOps.add(new PatchOp(OpKind.REMOVE_ENTITY, id, null, null, false, null, null, null,
                        null, null, null, null, null, null, false));
            } else if (line.startsWith("+EDGE ") || line.startsWith("-EDGE ")) {
                Matcher m = EDGE_LINE_PATTERN.matcher(line);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Invalid edge patch line: " + line);
                }
                boolean add = "+EDGE".equals(m.group(1));
                EntityId edgeId = EntityId.parse(m.group(2));
                EntityId srcId = EntityId.parse(m.group(3));
                String predRef = m.group(4).trim();
                EntityId tgtId = EntityId.parse(m.group(5));
                edgeOps.add(new PatchOp(OpKind.EDGE_OP, edgeId, null, null, false, null, null, null,
                        null, null, null, srcId, predRef, tgtId, add));
            } else {
                throw new IllegalArgumentException("Unrecognized patch operation: " + line);
            }
        }
        return new ParsedPatch(patchId, baseCommitId, actorId, category, reason, schemaOps, entityOps, edgeOps);
    }

    private static EntityId resolveSchemaEndpoint(GraphReader reader, String ref, Map<EntityId, EntityId> idMap) {
        if (ref.startsWith("@Wildcard:")) {
            return switch (ref.substring("@Wildcard:".length())) {
                case "ANY_ENTITY" -> BootstrapIds.ANY_ENTITY;
                case "ANY_OBJECT" -> BootstrapIds.ANY_OBJECT;
                case "ANY_VALUE" -> BootstrapIds.ANY_VALUE;
                case "ANY_EDGE" -> BootstrapIds.ANY_EDGE;
                default -> throw new IllegalArgumentException("Unknown wildcard ref: " + ref);
            };
        }
        if (ref.startsWith("@Sys:")) {
            return switch (ref.substring("@Sys:".length())) {
                case "SystemIdentifier" -> BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE;
                case "SystemInt" -> BootstrapIds.SYSTEM_INT_VALUE_TYPE;
                case "SystemBoolean" -> BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE;
                case "SystemTimestamp" -> BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE;
                default -> throw new IllegalArgumentException("Unknown system type ref: " + ref);
            };
        }
        if (ref.startsWith("@Type:")) {
            String name = unquote(ref.substring("@Type:".length()));
            return reader.findTypeByName(name)
                    .orElseThrow(() -> new SchemaViolationException("Target DB missing required schema type: " + name));
        }
        EntityId raw = EntityId.parse(ref);
        return idMap.getOrDefault(raw, raw);
    }

    private static EntityId resolvePredicateEndpoint(GraphReader reader, String ref, Map<EntityId, EntityId> idMap) {
        if (ref.startsWith("@Bootstrap:")) {
            return switch (ref.substring("@Bootstrap:".length())) {
                case "HAS_NAME" -> BootstrapIds.HAS_NAME;
                case "HAS_INVERSE_PREDICATE" -> BootstrapIds.HAS_INVERSE_PREDICATE;
                case "HAS_SUPER_PREDICATE" -> BootstrapIds.HAS_SUPER_PREDICATE;
                case "VALID_FROM" -> BootstrapIds.VALID_FROM;
                case "VALID_TO" -> BootstrapIds.VALID_TO;
                case "MEMBER_OF_GROUP" -> BootstrapIds.MEMBER_OF_GROUP;
                default -> throw new IllegalArgumentException("Unknown bootstrap predicate ref: " + ref);
            };
        }
        if (ref.startsWith("@Pred:")) {
            String name = unquote(ref.substring("@Pred:".length()));
            return reader.findPredicateByName(name)
                    .orElseThrow(() -> new SchemaViolationException("Target DB missing required predicate: " + name));
        }
        EntityId raw = EntityId.parse(ref);
        return idMap.getOrDefault(raw, raw);
    }

    private static String formatSchemaRef(GraphReader reader, EntityId id) {
        if (BootstrapIds.ANY_ENTITY.equals(id)) return "@Wildcard:ANY_ENTITY";
        if (BootstrapIds.ANY_OBJECT.equals(id)) return "@Wildcard:ANY_OBJECT";
        if (BootstrapIds.ANY_VALUE.equals(id)) return "@Wildcard:ANY_VALUE";
        if (BootstrapIds.ANY_EDGE.equals(id)) return "@Wildcard:ANY_EDGE";
        if (BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE.equals(id)) return "@Sys:SystemIdentifier";
        if (BootstrapIds.SYSTEM_INT_VALUE_TYPE.equals(id)) return "@Sys:SystemInt";
        if (BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE.equals(id)) return "@Sys:SystemBoolean";
        if (BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE.equals(id)) return "@Sys:SystemTimestamp";
        return reader.resolveName(id).map(n -> "@Type:" + quoteIfNeeded(n)).orElse(id.toString());
    }

    private static String formatPredRef(GraphReader reader, EntityId predId) {
        if (BootstrapIds.VALID_FROM.equals(predId)) return "@Bootstrap:VALID_FROM";
        if (BootstrapIds.VALID_TO.equals(predId)) return "@Bootstrap:VALID_TO";
        if (BootstrapIds.HAS_NAME.equals(predId)) return "@Bootstrap:HAS_NAME";
        if (BootstrapIds.HAS_INVERSE_PREDICATE.equals(predId)) return "@Bootstrap:HAS_INVERSE_PREDICATE";
        if (BootstrapIds.HAS_SUPER_PREDICATE.equals(predId)) return "@Bootstrap:HAS_SUPER_PREDICATE";
        if (BootstrapIds.MEMBER_OF_GROUP.equals(predId)) return "@Bootstrap:MEMBER_OF_GROUP";
        return reader.resolveName(predId).map(n -> "@Pred:" + quoteIfNeeded(n)).orElse(predId.toString());
    }

    private static boolean isSchemaStructuralPredicate(EntityId predId) {
        return BootstrapIds.HAS_NAME.equals(predId)
                || BootstrapIds.HAS_SCALAR_TYPE.equals(predId)
                || BootstrapIds.HAS_DOMAIN.equals(predId)
                || BootstrapIds.HAS_RANGE.equals(predId)
                || BootstrapIds.HAS_MAX_OUTGOING.equals(predId)
                || BootstrapIds.HAS_MAX_INCOMING.equals(predId)
                || BootstrapIds.HAS_UNIQUE_TRIPLE.equals(predId)
                || BootstrapIds.HAS_UNIQUE_VALUES.equals(predId);
    }

    private static ValueType resolveValueTypeScalar(GraphReader reader, EntityId valueTypeId) {
        return singleTarget(reader, valueTypeId, BootstrapIds.HAS_SCALAR_TYPE)
                .flatMap(BootstrapIds::toValueType)
                .orElse(ValueType.STRING);
    }

    private static Optional<EntityId> singleTarget(GraphReader reader, EntityId sourceId, EntityId predId) {
        return reader.findOutgoingEdges(sourceId, predId).stream().findFirst().map(Edge::targetId);
    }

    private static boolean hasCommit(VersionedGraphDatabase db, CommitId id) {
        try {
            db.getCommit(id);
            return true;
        } catch (CommitNotFoundException ignored) {
            return false;
        }
    }

    private static boolean isAncestor(VersionedGraphDatabase db, CommitId candidate, CommitId descendant) {
        if (candidate.equals(descendant)) {
            return true;
        }
        Set<CommitId> visited = new HashSet<>();
        Queue<CommitId> queue = new ArrayDeque<>();
        queue.add(descendant);
        while (!queue.isEmpty()) {
            CommitId cur = queue.poll();
            if (cur.equals(candidate)) {
                return true;
            }
            if (!visited.add(cur)) {
                continue;
            }
            Commit c = db.getCommit(cur);
            queue.addAll(c.parentIds());
        }
        return false;
    }

    private static CommitId parseCommitId(String s) {
        String norm = s.trim();
        if (norm.startsWith("c") || norm.startsWith("C")) {
            norm = norm.substring(1);
        }
        return new CommitId(Long.parseLong(norm));
    }

    private static String shortPatchHash(String patchId) {
        int idx = patchId.indexOf(':');
        String hex = idx >= 0 ? patchId.substring(idx + 1) : patchId;
        return hex.length() > 10 ? hex.substring(0, 10) : hex;
    }

    private static String formatTypedValue(Value v) {
        return switch (v) {
            case Value.StringValue(String s) -> "STRING:" + quoteString(s);
            case Value.IntValue(long n) -> "INT:" + n;
            case Value.DecimalValue(BigDecimal d) -> "DECIMAL:" + d.toPlainString();
            case Value.BooleanValue(boolean b) -> "BOOLEAN:" + b;
            case Value.TimestampValue(Instant t) -> "TIMESTAMP:" + t;
            case Value.BinaryValue(byte[] bytes) -> "BINARY:" + Base64.getEncoder().encodeToString(bytes);
        };
    }

    private static Value parseTypedValue(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Expected typed value (<TYPE>:<val>), got: " + token);
        }
        ValueType vt = ValueType.valueOf(token.substring(0, colon));
        String raw = token.substring(colon + 1);
        return switch (vt) {
            case STRING -> Value.of(unquote(raw));
            case INT -> Value.of(Long.parseLong(raw));
            case DECIMAL -> Value.of(new BigDecimal(raw));
            case BOOLEAN -> Value.of(Boolean.parseBoolean(raw));
            case TIMESTAMP -> Value.of(Instant.parse(raw));
            case BINARY -> Value.of(Base64.getDecoder().decode(raw));
        };
    }

    private static String quoteIfNeeded(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\\' || c == ']') {
                return quoteString(s);
            }
        }
        return s;
    }

    private static String quoteString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unquote(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        } else {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"', '\\' -> sb.append(next);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> splitShellTokens(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                current.append(c);
                if (c == '\\' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else if (c == '"') {
                    inQuotes = false;
                }
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
                if (c == '"') {
                    inQuotes = true;
                }
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static Map<String, String> parseKeyValues(List<String> tokens) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                kv.put(t.substring(0, eq), t.substring(eq + 1));
            }
        }
        return kv;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
