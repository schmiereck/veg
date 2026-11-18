package io.veg.cli.kb;

import io.veg.cli.json.JsonCodec;
import io.veg.cli.kb.GraphTabularService.SnapshotContext;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-database persistence model for VEG Access Studio's graphical diagram editor.
 *
 * <p>Stores named diagram views ({@code StudioDiagramView}), explicitly added table placements
 * ({@code StudioDiagramTable}), curved relation routing handles ({@code StudioDiagramEdge}), and
 * callout infoboxes ({@code StudioDiagramNote}) with typed graph relations directly inside the
 * versioned VEG database.</p>
 */
public final class DiagramModelService {

    public static final String TYPE_VIEW = "StudioDiagramView";
    public static final String TYPE_TABLE = "StudioDiagramTable";
    public static final String TYPE_EDGE = "StudioDiagramEdge";
    public static final String TYPE_NOTE = "StudioDiagramNote";

    private static final String VAL_TEXT = "StudioDiagramText";
    private static final String VAL_COORD = "StudioDiagramCoord";

    private static final String PRED_VIEW_ID = "diagramViewId";
    private static final String PRED_TITLE = "diagramTitle";
    private static final String PRED_SUMMARY = "diagramSummary";
    private static final String PRED_VIEW_MODE = "diagramViewMode";
    private static final String PRED_VIEW_STATUS = "diagramViewStatus";
    private static final String PRED_DEFAULT_DSL = "diagramDefaultDsl";
    private static final String PRED_TABLE_TYPE = "diagramTableType";
    private static final String PRED_POS_X = "diagramPosX";
    private static final String PRED_POS_Y = "diagramPosY";
    private static final String PRED_WIDTH = "diagramWidth";
    private static final String PRED_EDGE_KEY = "diagramEdgeKey";
    private static final String PRED_PREDICATE_NAME = "diagramPredicateName";
    private static final String PRED_SOURCE_TYPE = "diagramSourceTypeName";
    private static final String PRED_TARGET_TYPE = "diagramTargetTypeName";
    private static final String PRED_CTRL_X = "diagramCtrlX";
    private static final String PRED_CTRL_Y = "diagramCtrlY";
    private static final String PRED_NOTE_ID = "diagramNoteId";
    private static final String PRED_NOTE_TEXT = "diagramNoteText";
    private static final String PRED_NOTE_COLOR = "diagramNoteColor";
    private static final String PRED_TARGET_REFS_JSON = "diagramTargetRefsJson";

    private static final String REL_HAS_TABLE = "hasTableNode";
    private static final String REL_HAS_EDGE = "hasEdgeLayout";
    private static final String REL_HAS_NOTE = "hasNoteBox";
    private static final String REL_ANNOTATES_TABLE = "annotatesTable";
    private static final String REL_ANNOTATES_EDGE = "annotatesEdgeLayout";

    private static final Set<String> SYSTEM_TYPES = Set.of(TYPE_VIEW, TYPE_TABLE, TYPE_EDGE, TYPE_NOTE);

    private record DiagramSchema(
            EntityId viewType,
            EntityId tableType,
            EntityId edgeType,
            EntityId noteType,
            EntityId textVal,
            EntityId coordVal,
            EntityId viewIdPred,
            EntityId titlePred,
            EntityId summaryPred,
            EntityId viewModePred,
            EntityId viewStatusPred,
            EntityId defaultDslPred,
            EntityId tableTypePred,
            EntityId posXPred,
            EntityId posYPred,
            EntityId widthPred,
            EntityId edgeKeyPred,
            EntityId predicateNamePred,
            EntityId sourceTypePred,
            EntityId targetTypePred,
            EntityId ctrlXPred,
            EntityId ctrlYPred,
            EntityId noteIdPred,
            EntityId noteTextPred,
            EntityId noteColorPred,
            EntityId targetRefsJsonPred,
            EntityId hasTableRel,
            EntityId hasEdgeRel,
            EntityId hasNoteRel,
            EntityId annotatesTableRel,
            EntityId annotatesEdgeRel
    ) {
    }

    private DiagramModelService() {
    }

    public static boolean isDiagramSystemType(String typeName) {
        return typeName != null && SYSTEM_TYPES.contains(typeName);
    }

    public static Map<String, Object> listViews(VersionedGraphDatabase db, SnapshotContext ctx) {
        GraphReader reader = ctx.reader();
        Optional<EntityId> optViewType = reader.findTypeByName(TYPE_VIEW);
        List<Map<String, Object>> views = new ArrayList<>();
        if (optViewType.isPresent()) {
            DiagramSchema schema = findSchema(reader);
            for (EntityNode viewNode : reader.findEntitiesByType(optViewType.get())) {
                String status = readString(reader, viewNode.id(), schema.viewStatusPred(), "ACTIVE");
                if ("DELETED".equalsIgnoreCase(status)) {
                    continue;
                }
                views.add(readView(reader, viewNode.id(), schema));
            }
            views.sort(Comparator
                    .comparing((Map<String, Object> v) -> String.valueOf(v.getOrDefault("title", "")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(v -> String.valueOf(v.getOrDefault("viewId", ""))));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", ctx.branchName());
        out.put("commitId", ctx.commitId().toString());
        out.put("historical", ctx.historical());
        out.put("views", views);
        return out;
    }

    public static Map<String, Object> saveView(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            Map<String, Object> viewPayload
    ) {
        Objects.requireNonNull(db, "db must not be null");
        Objects.requireNonNull(viewPayload, "viewPayload must not be null");
        String targetBranch = resolveWritableBranch(db, branch);
        Actor safeActor = actor != null ? actor : Actor.user("studio-user");
        String viewId = JsonCodec.requireString(viewPayload, "viewId").trim();
        String title = JsonCodec.asOptionalString(viewPayload, "title").filter(s -> !s.isBlank()).orElse(viewId);
        String description = JsonCodec.asOptionalString(viewPayload, "description").orElse("");
        String viewMode = JsonCodec.asOptionalString(viewPayload, "viewMode").filter(s -> !s.isBlank()).orElse("DIAGRAM");
        String defaultDsl = JsonCodec.asOptionalString(viewPayload, "defaultDsl").orElse("");
        Reason safeReason = reason != null ? reason : Reason.of("Studio: Save diagram view " + title);

        ensureSchema(db, targetBranch, safeActor);

        CommitId commitId;
        EntityId viewEntityId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, safeActor, safeReason, null, DataCategory.REFERENCE)) {
            DiagramSchema s = findSchema(tx);
            viewEntityId = findViewEntityById(tx, s, viewId).orElseGet(() -> tx.createObject(s.viewType()));

            setString(tx, viewEntityId, s.viewIdPred(), s.textVal(), viewId);
            setString(tx, viewEntityId, s.titlePred(), s.textVal(), title);
            setString(tx, viewEntityId, s.summaryPred(), s.textVal(), description);
            setString(tx, viewEntityId, s.viewModePred(), s.textVal(), viewMode);
            setString(tx, viewEntityId, s.viewStatusPred(), s.textVal(), "ACTIVE");
            setString(tx, viewEntityId, s.defaultDslPred(), s.textVal(), defaultDsl);

            // Reconcile tables
            Map<String, EntityId> existingTablesByType = new LinkedHashMap<>();
            for (Edge e : tx.findOutgoingEdges(viewEntityId, s.hasTableRel())) {
                String tName = readString(tx, e.targetId(), s.tableTypePred(), "");
                if (!tName.isBlank()) {
                    existingTablesByType.putIfAbsent(tName, e.targetId());
                }
            }
            Map<String, EntityId> activeTablesByType = new LinkedHashMap<>();
            for (Object rawTable : JsonCodec.asArray(viewPayload.get("tables"))) {
                Map<String, Object> tMap = JsonCodec.asObject(rawTable);
                String tName = JsonCodec.requireString(tMap, "typeName").trim();
                EntityId tableEnt = existingTablesByType.get(tName);
                if (tableEnt == null) {
                    tableEnt = tx.createObject(s.tableType());
                    tx.connect(viewEntityId, s.hasTableRel(), tableEnt);
                }
                setString(tx, tableEnt, s.tableTypePred(), s.textVal(), tName);
                setInt(tx, tableEnt, s.posXPred(), s.coordVal(), JsonCodec.asOptionalLong(tMap, "x").orElse(40L));
                setInt(tx, tableEnt, s.posYPred(), s.coordVal(), JsonCodec.asOptionalLong(tMap, "y").orElse(40L));
                setInt(tx, tableEnt, s.widthPred(), s.coordVal(), JsonCodec.asOptionalLong(tMap, "width").orElse(230L));
                activeTablesByType.put(tName, tableEnt);
            }
            disconnectRemovedChildren(tx, viewEntityId, s.hasTableRel(), activeTablesByType.values());

            // Collect normalized edge layouts (plus ensure any edge targeted by a note has an entity)
            Map<String, Map<String, Object>> normalizedEdges = new LinkedHashMap<>();
            for (Object rawEdge : JsonCodec.asArray(viewPayload.get("edges"))) {
                Map<String, Object> eMap = JsonCodec.asObject(rawEdge);
                String edgeKey = deriveEdgeKey(eMap);
                normalizedEdges.put(edgeKey, eMap);
            }
            List<Object> rawNotes = JsonCodec.asArray(viewPayload.get("notes"));
            for (Object rawNote : rawNotes) {
                Map<String, Object> nMap = JsonCodec.asObject(rawNote);
                for (Object rawTarget : JsonCodec.asArray(nMap.get("targets"))) {
                    Map<String, Object> tMap = JsonCodec.asObject(rawTarget);
                    if ("EDGE".equalsIgnoreCase(JsonCodec.asOptionalString(tMap, "kind").orElse(""))) {
                        String ref = JsonCodec.asOptionalString(tMap, "ref").orElse("");
                        if (!ref.isBlank() && !normalizedEdges.containsKey(ref)) {
                            String[] parts = ref.split("\\|", -1);
                            if (parts.length == 3) {
                                Map<String, Object> stub = new LinkedHashMap<>();
                                stub.put("edgeKey", ref);
                                stub.put("sourceType", parts[0]);
                                stub.put("predicate", parts[1]);
                                stub.put("targetType", parts[2]);
                                normalizedEdges.put(ref, stub);
                            }
                        }
                    }
                }
            }

            // Reconcile edge layouts
            Map<String, EntityId> existingEdgesByKey = new LinkedHashMap<>();
            for (Edge e : tx.findOutgoingEdges(viewEntityId, s.hasEdgeRel())) {
                String k = readString(tx, e.targetId(), s.edgeKeyPred(), "");
                if (!k.isBlank()) {
                    existingEdgesByKey.putIfAbsent(k, e.targetId());
                }
            }
            Map<String, EntityId> activeEdgesByKey = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : normalizedEdges.entrySet()) {
                String edgeKey = entry.getKey();
                Map<String, Object> eMap = entry.getValue();
                String[] parts = edgeKey.split("\\|", -1);
                String sourceType = JsonCodec.asOptionalString(eMap, "sourceType")
                        .filter(v -> !v.isBlank()).orElse(parts.length == 3 ? parts[0] : "");
                String predName = JsonCodec.asOptionalString(eMap, "predicate")
                        .filter(v -> !v.isBlank()).orElse(parts.length == 3 ? parts[1] : "");
                String targetType = JsonCodec.asOptionalString(eMap, "targetType")
                        .filter(v -> !v.isBlank()).orElse(parts.length == 3 ? parts[2] : "");

                EntityId edgeEnt = existingEdgesByKey.get(edgeKey);
                if (edgeEnt == null) {
                    edgeEnt = tx.createObject(s.edgeType());
                    tx.connect(viewEntityId, s.hasEdgeRel(), edgeEnt);
                }
                setString(tx, edgeEnt, s.edgeKeyPred(), s.textVal(), edgeKey);
                setString(tx, edgeEnt, s.sourceTypePred(), s.textVal(), sourceType);
                setString(tx, edgeEnt, s.predicateNamePred(), s.textVal(), predName);
                setString(tx, edgeEnt, s.targetTypePred(), s.textVal(), targetType);
                setInt(tx, edgeEnt, s.ctrlXPred(), s.coordVal(), JsonCodec.asOptionalLong(eMap, "ctrlX").orElse(null));
                setInt(tx, edgeEnt, s.ctrlYPred(), s.coordVal(), JsonCodec.asOptionalLong(eMap, "ctrlY").orElse(null));
                activeEdgesByKey.put(edgeKey, edgeEnt);
            }
            disconnectRemovedChildren(tx, viewEntityId, s.hasEdgeRel(), activeEdgesByKey.values());

            // Reconcile infoboxes and their typed target edges
            Map<String, EntityId> existingNotesById = new LinkedHashMap<>();
            for (Edge e : tx.findOutgoingEdges(viewEntityId, s.hasNoteRel())) {
                String nId = readString(tx, e.targetId(), s.noteIdPred(), "");
                if (!nId.isBlank()) {
                    existingNotesById.putIfAbsent(nId, e.targetId());
                }
            }
            List<EntityId> activeNoteEntities = new ArrayList<>();
            int noteSeq = 1;
            for (Object rawNote : rawNotes) {
                Map<String, Object> nMap = JsonCodec.asObject(rawNote);
                String noteId = JsonCodec.asOptionalString(nMap, "noteId")
                        .filter(v -> !v.isBlank()).orElse("note-" + noteSeq);
                noteSeq++;

                EntityId noteEnt = existingNotesById.get(noteId);
                if (noteEnt == null) {
                    noteEnt = tx.createObject(s.noteType());
                    tx.connect(viewEntityId, s.hasNoteRel(), noteEnt);
                }
                setString(tx, noteEnt, s.noteIdPred(), s.textVal(), noteId);
                setString(tx, noteEnt, s.titlePred(), s.textVal(),
                        JsonCodec.asOptionalString(nMap, "title").filter(v -> !v.isBlank()).orElse("Infobox"));
                setString(tx, noteEnt, s.noteTextPred(), s.textVal(),
                        JsonCodec.asOptionalString(nMap, "text").orElse(""));
                setString(tx, noteEnt, s.noteColorPred(), s.textVal(),
                        JsonCodec.asOptionalString(nMap, "color").filter(v -> !v.isBlank()).orElse("info"));
                setInt(tx, noteEnt, s.posXPred(), s.coordVal(), JsonCodec.asOptionalLong(nMap, "x").orElse(60L));
                setInt(tx, noteEnt, s.posYPred(), s.coordVal(), JsonCodec.asOptionalLong(nMap, "y").orElse(60L));

                // Rebuild annotatesTable and annotatesEdgeLayout relations
                for (Edge oldAnn : tx.findOutgoingEdges(noteEnt, s.annotatesTableRel())) {
                    tx.disconnect(oldAnn.id());
                }
                for (Edge oldAnn : tx.findOutgoingEdges(noteEnt, s.annotatesEdgeRel())) {
                    tx.disconnect(oldAnn.id());
                }
                List<Map<String, String>> sanitizedTargets = new ArrayList<>();
                Set<String> seenTargets = new LinkedHashSet<>();
                for (Object rawTarget : JsonCodec.asArray(nMap.get("targets"))) {
                    Map<String, Object> tMap = JsonCodec.asObject(rawTarget);
                    String kind = JsonCodec.asOptionalString(tMap, "kind").orElse("").toUpperCase();
                    String ref = JsonCodec.asOptionalString(tMap, "ref").orElse("");
                    if (ref.isBlank() || !seenTargets.add(kind + ":" + ref)) {
                        continue;
                    }
                    if ("TABLE".equals(kind)) {
                        EntityId targetTable = activeTablesByType.get(ref);
                        if (targetTable != null) {
                            tx.connect(noteEnt, s.annotatesTableRel(), targetTable);
                            sanitizedTargets.add(Map.of("kind", "TABLE", "ref", ref));
                        }
                    } else if ("EDGE".equals(kind)) {
                        EntityId targetEdge = activeEdgesByKey.get(ref);
                        if (targetEdge != null) {
                            tx.connect(noteEnt, s.annotatesEdgeRel(), targetEdge);
                            sanitizedTargets.add(Map.of("kind", "EDGE", "ref", ref));
                        }
                    }
                }
                setString(tx, noteEnt, s.targetRefsJsonPred(), s.textVal(), JsonCodec.toJson(sanitizedTargets));
                activeNoteEntities.add(noteEnt);
            }
            disconnectRemovedChildren(tx, viewEntityId, s.hasNoteRel(), activeNoteEntities);

            commitId = tx.commit();
        }

        GraphReader afterReader = db.branchSnapshot(targetBranch);
        Map<String, Object> savedView = readView(afterReader, viewEntityId, findSchema(afterReader));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("commitId", commitId.toString());
        res.put("branch", targetBranch);
        res.put("viewId", viewId);
        res.put("viewEntityId", jsonId(viewEntityId));
        res.put("view", savedView);
        return res;
    }

    public static Map<String, Object> deleteView(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            String viewId
    ) {
        Objects.requireNonNull(db, "db must not be null");
        String targetBranch = resolveWritableBranch(db, branch);
        Actor safeActor = actor != null ? actor : Actor.user("studio-user");
        Reason safeReason = reason != null ? reason : Reason.of("Studio: Delete diagram view " + viewId);

        GraphReader current = db.branchSnapshot(targetBranch);
        if (current.findTypeByName(TYPE_VIEW).isEmpty()) {
            throw new IllegalArgumentException("Unknown diagram view: " + viewId);
        }

        CommitId commitId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, safeActor, safeReason, null, DataCategory.REFERENCE)) {
            DiagramSchema s = findSchema(tx);
            EntityId viewEntityId = findViewEntityById(tx, s, viewId.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown diagram view: " + viewId));
            setString(tx, viewEntityId, s.viewStatusPred(), s.textVal(), "DELETED");
            disconnectRemovedChildren(tx, viewEntityId, s.hasTableRel(), List.of());
            disconnectRemovedChildren(tx, viewEntityId, s.hasEdgeRel(), List.of());
            for (Edge noteEdge : tx.findOutgoingEdges(viewEntityId, s.hasNoteRel())) {
                for (Edge ann : tx.findOutgoingEdges(noteEdge.targetId(), s.annotatesTableRel())) {
                    tx.disconnect(ann.id());
                }
                for (Edge ann : tx.findOutgoingEdges(noteEdge.targetId(), s.annotatesEdgeRel())) {
                    tx.disconnect(ann.id());
                }
                tx.disconnect(noteEdge.id());
            }
            commitId = tx.commit();
        }

        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "deletedViewId", viewId.trim()
        );
    }

    private static Map<String, Object> readView(GraphReader reader, EntityId viewEntityId, DiagramSchema s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("entityId", jsonId(viewEntityId));
        view.put("viewId", readString(reader, viewEntityId, s.viewIdPred(), jsonId(viewEntityId).toString()));
        view.put("title", readString(reader, viewEntityId, s.titlePred(), "Ansicht"));
        view.put("description", readString(reader, viewEntityId, s.summaryPred(), ""));
        view.put("viewMode", readString(reader, viewEntityId, s.viewModePred(), "DIAGRAM"));
        view.put("defaultDsl", readString(reader, viewEntityId, s.defaultDslPred(), ""));

        List<Map<String, Object>> tables = new ArrayList<>();
        for (Edge e : reader.findOutgoingEdges(viewEntityId, s.hasTableRel())) {
            EntityId tId = e.targetId();
            Map<String, Object> tMap = new LinkedHashMap<>();
            tMap.put("entityId", jsonId(tId));
            tMap.put("typeName", readString(reader, tId, s.tableTypePred(), ""));
            tMap.put("x", readInt(reader, tId, s.posXPred()).orElse(40L));
            tMap.put("y", readInt(reader, tId, s.posYPred()).orElse(40L));
            tMap.put("width", readInt(reader, tId, s.widthPred()).orElse(230L));
            tables.add(tMap);
        }
        tables.sort(Comparator.comparing(m -> String.valueOf(m.get("typeName"))));

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Edge e : reader.findOutgoingEdges(viewEntityId, s.hasEdgeRel())) {
            EntityId eId = e.targetId();
            Map<String, Object> eMap = new LinkedHashMap<>();
            eMap.put("entityId", jsonId(eId));
            eMap.put("edgeKey", readString(reader, eId, s.edgeKeyPred(), ""));
            eMap.put("sourceType", readString(reader, eId, s.sourceTypePred(), ""));
            eMap.put("predicate", readString(reader, eId, s.predicateNamePred(), ""));
            eMap.put("targetType", readString(reader, eId, s.targetTypePred(), ""));
            eMap.put("ctrlX", readInt(reader, eId, s.ctrlXPred()).orElse(null));
            eMap.put("ctrlY", readInt(reader, eId, s.ctrlYPred()).orElse(null));
            edges.add(eMap);
        }
        edges.sort(Comparator.comparing(m -> String.valueOf(m.get("edgeKey"))));

        List<Map<String, Object>> notes = new ArrayList<>();
        for (Edge e : reader.findOutgoingEdges(viewEntityId, s.hasNoteRel())) {
            EntityId nId = e.targetId();
            Map<String, Object> nMap = new LinkedHashMap<>();
            nMap.put("entityId", jsonId(nId));
            nMap.put("noteId", readString(reader, nId, s.noteIdPred(), jsonId(nId).toString()));
            nMap.put("title", readString(reader, nId, s.titlePred(), "Infobox"));
            nMap.put("text", readString(reader, nId, s.noteTextPred(), ""));
            nMap.put("color", readString(reader, nId, s.noteColorPred(), "info"));
            nMap.put("x", readInt(reader, nId, s.posXPred()).orElse(60L));
            nMap.put("y", readInt(reader, nId, s.posYPred()).orElse(60L));

            List<Map<String, Object>> targets = new ArrayList<>();
            for (Edge tEdge : reader.findOutgoingEdges(nId, s.annotatesTableRel())) {
                String tName = readString(reader, tEdge.targetId(), s.tableTypePred(), "");
                if (!tName.isBlank()) {
                    targets.add(Map.of("kind", "TABLE", "ref", tName));
                }
            }
            for (Edge eEdge : reader.findOutgoingEdges(nId, s.annotatesEdgeRel())) {
                String k = readString(reader, eEdge.targetId(), s.edgeKeyPred(), "");
                if (!k.isBlank()) {
                    targets.add(Map.of("kind", "EDGE", "ref", k));
                }
            }
            nMap.put("targets", targets);
            notes.add(nMap);
        }
        notes.sort(Comparator.comparing(m -> String.valueOf(m.get("noteId"))));

        view.put("tables", tables);
        view.put("edges", edges);
        view.put("notes", notes);
        return view;
    }

    private static void ensureSchema(VersionedGraphDatabase db, String branch, Actor actor) {
        GraphReader reader = db.branchSnapshot(branch);
        if (reader.findTypeByName(TYPE_VIEW).isPresent()
                && reader.findPredicateByName(REL_ANNOTATES_EDGE).isPresent()) {
            return;
        }
        try (GraphTransaction tx = db.beginTransaction(
                branch, actor, Reason.of("Studio: Initialize diagram editor graph schema"), null, DataCategory.SCHEMA)) {
            EntityId viewType = ensureObjectType(tx, TYPE_VIEW);
            EntityId tableType = ensureObjectType(tx, TYPE_TABLE);
            EntityId edgeType = ensureObjectType(tx, TYPE_EDGE);
            EntityId noteType = ensureObjectType(tx, TYPE_NOTE);

            EntityId textVal = ensureValueType(tx, VAL_TEXT, ValueType.STRING);
            EntityId coordVal = ensureValueType(tx, VAL_COORD, ValueType.INT);

            PredicateConstraints single = PredicateConstraints.manyToOne();
            PredicateConstraints relSet = PredicateConstraints.uniqueSet();

            ensurePredicate(tx, PRED_VIEW_ID, viewType, textVal, single);
            ensurePredicate(tx, PRED_TITLE, BootstrapIds.ANY_OBJECT, textVal, single);
            ensurePredicate(tx, PRED_SUMMARY, viewType, textVal, single);
            ensurePredicate(tx, PRED_VIEW_MODE, viewType, textVal, single);
            ensurePredicate(tx, PRED_VIEW_STATUS, viewType, textVal, single);
            ensurePredicate(tx, PRED_DEFAULT_DSL, viewType, textVal, single);
            ensurePredicate(tx, PRED_TABLE_TYPE, tableType, textVal, single);
            ensurePredicate(tx, PRED_POS_X, BootstrapIds.ANY_OBJECT, coordVal, single);
            ensurePredicate(tx, PRED_POS_Y, BootstrapIds.ANY_OBJECT, coordVal, single);
            ensurePredicate(tx, PRED_WIDTH, tableType, coordVal, single);
            ensurePredicate(tx, PRED_EDGE_KEY, edgeType, textVal, single);
            ensurePredicate(tx, PRED_PREDICATE_NAME, edgeType, textVal, single);
            ensurePredicate(tx, PRED_SOURCE_TYPE, edgeType, textVal, single);
            ensurePredicate(tx, PRED_TARGET_TYPE, edgeType, textVal, single);
            ensurePredicate(tx, PRED_CTRL_X, edgeType, coordVal, single);
            ensurePredicate(tx, PRED_CTRL_Y, edgeType, coordVal, single);
            ensurePredicate(tx, PRED_NOTE_ID, noteType, textVal, single);
            ensurePredicate(tx, PRED_NOTE_TEXT, noteType, textVal, single);
            ensurePredicate(tx, PRED_NOTE_COLOR, noteType, textVal, single);
            ensurePredicate(tx, PRED_TARGET_REFS_JSON, noteType, textVal, single);

            ensurePredicate(tx, REL_HAS_TABLE, viewType, tableType, relSet);
            ensurePredicate(tx, REL_HAS_EDGE, viewType, edgeType, relSet);
            ensurePredicate(tx, REL_HAS_NOTE, viewType, noteType, relSet);
            ensurePredicate(tx, REL_ANNOTATES_TABLE, noteType, tableType, relSet);
            ensurePredicate(tx, REL_ANNOTATES_EDGE, noteType, edgeType, relSet);

            tx.commit();
        }
    }

    private static DiagramSchema findSchema(GraphReader reader) {
        return new DiagramSchema(
                requireType(reader, TYPE_VIEW),
                requireType(reader, TYPE_TABLE),
                requireType(reader, TYPE_EDGE),
                requireType(reader, TYPE_NOTE),
                requireType(reader, VAL_TEXT),
                requireType(reader, VAL_COORD),
                requirePredicate(reader, PRED_VIEW_ID),
                requirePredicate(reader, PRED_TITLE),
                requirePredicate(reader, PRED_SUMMARY),
                requirePredicate(reader, PRED_VIEW_MODE),
                requirePredicate(reader, PRED_VIEW_STATUS),
                requirePredicate(reader, PRED_DEFAULT_DSL),
                requirePredicate(reader, PRED_TABLE_TYPE),
                requirePredicate(reader, PRED_POS_X),
                requirePredicate(reader, PRED_POS_Y),
                requirePredicate(reader, PRED_WIDTH),
                requirePredicate(reader, PRED_EDGE_KEY),
                requirePredicate(reader, PRED_PREDICATE_NAME),
                requirePredicate(reader, PRED_SOURCE_TYPE),
                requirePredicate(reader, PRED_TARGET_TYPE),
                requirePredicate(reader, PRED_CTRL_X),
                requirePredicate(reader, PRED_CTRL_Y),
                requirePredicate(reader, PRED_NOTE_ID),
                requirePredicate(reader, PRED_NOTE_TEXT),
                requirePredicate(reader, PRED_NOTE_COLOR),
                requirePredicate(reader, PRED_TARGET_REFS_JSON),
                requirePredicate(reader, REL_HAS_TABLE),
                requirePredicate(reader, REL_HAS_EDGE),
                requirePredicate(reader, REL_HAS_NOTE),
                requirePredicate(reader, REL_ANNOTATES_TABLE),
                requirePredicate(reader, REL_ANNOTATES_EDGE)
        );
    }

    private static Optional<EntityId> findViewEntityById(GraphReader reader, DiagramSchema s, String viewId) {
        for (EntityNode node : reader.findEntitiesByType(s.viewType())) {
            String candidate = readString(reader, node.id(), s.viewIdPred(), "");
            if (viewId.equals(candidate)) {
                return Optional.of(node.id());
            }
        }
        return Optional.empty();
    }

    private static String deriveEdgeKey(Map<String, Object> edgeMap) {
        Optional<String> explicit = JsonCodec.asOptionalString(edgeMap, "edgeKey").filter(v -> !v.isBlank());
        if (explicit.isPresent()) {
            return explicit.get().trim();
        }
        String src = JsonCodec.requireString(edgeMap, "sourceType").trim();
        String pred = JsonCodec.requireString(edgeMap, "predicate").trim();
        String tgt = JsonCodec.requireString(edgeMap, "targetType").trim();
        return src + "|" + pred + "|" + tgt;
    }

    private static void disconnectRemovedChildren(
            GraphTransaction tx,
            EntityId viewEntityId,
            EntityId relPredId,
            Iterable<EntityId> keptTargets
    ) {
        Set<EntityId> keepSet = new LinkedHashSet<>();
        for (EntityId id : keptTargets) {
            keepSet.add(id);
        }
        for (Edge e : tx.findOutgoingEdges(viewEntityId, relPredId)) {
            if (!keepSet.contains(e.targetId())) {
                tx.disconnect(e.id());
            }
        }
    }

    private static void setString(GraphTransaction tx, EntityId subject, EntityId predId, EntityId valTypeId, String text) {
        for (Edge old : tx.findOutgoingEdges(subject, predId)) {
            tx.disconnect(old.id());
        }
        if (text != null) {
            EntityId vId = tx.createValue(valTypeId, Value.of(text));
            tx.connect(subject, predId, vId);
        }
    }

    private static void setInt(GraphTransaction tx, EntityId subject, EntityId predId, EntityId valTypeId, Long val) {
        for (Edge old : tx.findOutgoingEdges(subject, predId)) {
            tx.disconnect(old.id());
        }
        if (val != null) {
            EntityId vId = tx.createValue(valTypeId, Value.of(val));
            tx.connect(subject, predId, vId);
        }
    }

    private static String readString(GraphReader reader, EntityId subject, EntityId predId, String fallback) {
        return reader.findOutgoingEdges(subject, predId).stream()
                .findFirst()
                .flatMap(e -> reader.findEntity(e.targetId()))
                .flatMap(EntityNode::payload)
                .map(GraphTabularService::formatValue)
                .orElse(fallback);
    }

    private static Optional<Long> readInt(GraphReader reader, EntityId subject, EntityId predId) {
        return reader.findOutgoingEdges(subject, predId).stream()
                .findFirst()
                .flatMap(e -> reader.findEntity(e.targetId()))
                .flatMap(EntityNode::payload)
                .flatMap(v -> v instanceof Value.IntValue(long n) ? Optional.of(n) : Optional.empty());
    }

    private static EntityId ensureObjectType(GraphTransaction tx, String name) {
        return tx.findTypeByName(name).orElseGet(() -> tx.defineObjectType(name));
    }

    private static EntityId ensureValueType(GraphTransaction tx, String name, ValueType scalarType) {
        return tx.findTypeByName(name).orElseGet(() -> tx.defineValueType(name, scalarType, false));
    }

    private static EntityId ensurePredicate(
            GraphTransaction tx,
            String name,
            EntityId domainId,
            EntityId rangeId,
            PredicateConstraints constraints
    ) {
        return tx.findPredicateByName(name)
                .orElseGet(() -> tx.definePredicate(name, domainId, rangeId, constraints));
    }

    private static EntityId requireType(GraphReader reader, String name) {
        return reader.findTypeByName(name)
                .orElseThrow(() -> new IllegalStateException("Missing diagram schema type: " + name));
    }

    private static EntityId requirePredicate(GraphReader reader, String name) {
        return reader.findPredicateByName(name)
                .orElseThrow(() -> new IllegalStateException("Missing diagram schema predicate: " + name));
    }

    private static Object jsonId(EntityId id) {
        return id.isUuid() ? id.compactString() : id.value();
    }

    private static String resolveWritableBranch(VersionedGraphDatabase db, String branch) {
        String target = (branch != null && !branch.isBlank()) ? branch.trim() : db.currentBranch();
        if (db.findBranch(target).isEmpty()) {
            throw new IllegalArgumentException("Unknown branch: " + target);
        }
        return target;
    }
}
