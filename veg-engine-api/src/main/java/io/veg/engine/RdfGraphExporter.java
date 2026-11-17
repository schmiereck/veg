package io.veg.engine;

import io.veg.model.BootstrapIds;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.RdfExportOptions;
import io.veg.model.RdfExportResult;
import io.veg.model.RdfFormat;
import io.veg.model.Value;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage-independent RDF exporter serializing a {@link GraphReader} snapshot to Turtle ({@code .ttl})
 * or N-Triples ({@code .nt}), preserving edge-to-edge provenance, weights, confidence, and derived-assertion
 * markers via {@code rdf:Statement} reification ({@code T18}, {@code S-07}, {@code M-12}).
 */
public final class RdfGraphExporter {

    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    private static final String VEG_NS = "http://veg.io/meta#";

    private static final String PRED_IMPORTED_IRI = "veg_iri";

    private RdfGraphExporter() {
    }

    public static RdfExportResult export(GraphReader reader, RdfExportOptions options) {
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(options, "options must not be null");

        String baseNs = options.baseNamespace();
        RdfFormat format = options.format();
        StringBuilder out = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        int tripleCount = 0;
        int reifiedCount = 0;

        if (format == RdfFormat.TURTLE) {
            out.append("@prefix rdf: <").append(RDF_NS).append("> .\n");
            out.append("@prefix rdfs: <").append(RDFS_NS).append("> .\n");
            out.append("@prefix owl: <").append(OWL_NS).append("> .\n");
            out.append("@prefix xsd: <").append(XSD_NS).append("> .\n");
            out.append("@prefix veg: <").append(VEG_NS).append("> .\n");
            out.append("@prefix ex: <").append(baseNs).append("> .\n\n");
        }

        Optional<EntityId> iriPredicateOpt = reader.findPredicateByName(PRED_IMPORTED_IRI);
        List<EntityNode> allEntities = reader.allEntities();
        Map<EntityId, String> resourceIriById = new HashMap<>();

        for (EntityNode node : allEntities) {
            if (node.id().isBootstrap() || node.kind() == EntityKind.EDGE || node.kind() == EntityKind.VALUE) {
                continue;
            }
            if (isInternalAclNode(node)) {
                continue;
            }
            String iri = resolveResourceIri(reader, node, iriPredicateOpt, baseNs);
            resourceIriById.put(node.id(), iri);
        }

        // 1. Optional schema triples (ObjectTypes as rdfs:Class, Predicates as rdf:Property + semantic rules)
        if (options.includeSchema()) {
            List<EntityNode> schemaNodes = allEntities.stream()
                    .filter(n -> !n.id().isBootstrap() && n.kind() == EntityKind.META_TYPE)
                    .sorted(Comparator.comparing(EntityNode::id))
                    .toList();
            for (EntityNode schemaNode : schemaNodes) {
                String schemaIri = resourceIriById.get(schemaNode.id());
                String name = reader.resolveName(schemaNode.id()).orElse("schema_" + schemaNode.id().value());
                if (BootstrapIds.META_OBJECT_TYPE.equals(schemaNode.typeId())) {
                    appendTriple(out, format, baseNs, iriTerm(schemaIri), RDF_NS + "type", iriTerm(RDFS_NS + "Class"));
                    appendTriple(out, format, baseNs, iriTerm(schemaIri), RDFS_NS + "label", literalTerm(Value.of(name)));
                    tripleCount += 2;
                } else if (BootstrapIds.META_PREDICATE_TYPE.equals(schemaNode.typeId())) {
                    appendTriple(out, format, baseNs, iriTerm(schemaIri), RDF_NS + "type", iriTerm(RDF_NS + "Property"));
                    appendTriple(out, format, baseNs, iriTerm(schemaIri), RDFS_NS + "label", literalTerm(Value.of(name)));
                    tripleCount += 2;

                    for (Edge invEdge : reader.findOutgoingEdges(schemaNode.id(), BootstrapIds.HAS_INVERSE_PREDICATE)) {
                        String invIri = resourceIriById.get(invEdge.targetId());
                        if (invIri != null) {
                            appendTriple(out, format, baseNs, iriTerm(schemaIri), OWL_NS + "inverseOf", iriTerm(invIri));
                            tripleCount++;
                        }
                    }
                    for (Edge superEdge : reader.findOutgoingEdges(schemaNode.id(), BootstrapIds.HAS_SUPER_PREDICATE)) {
                        String supIri = resourceIriById.get(superEdge.targetId());
                        if (supIri != null) {
                            appendTriple(out, format, baseNs, iriTerm(schemaIri), RDFS_NS + "subPropertyOf", iriTerm(supIri));
                            tripleCount++;
                        }
                    }
                }
            }
        }

        // 2. Business objects, explicit outgoing edges, and edge-to-edge provenance reifications (T18)
        List<EntityNode> objectNodes = allEntities.stream()
                .filter(n -> !n.id().isBootstrap() && n.kind() == EntityKind.OBJECT && !isInternalAclNode(n))
                .sorted(Comparator.comparing(EntityNode::id))
                .toList();

        for (EntityNode obj : objectNodes) {
            String objIri = resourceIriById.get(obj.id());
            String typeIri = resolveTypeIri(reader, resourceIriById, obj.typeId(), baseNs);
            appendTriple(out, format, baseNs, iriTerm(objIri), RDF_NS + "type", iriTerm(typeIri));
            appendTriple(out, format, baseNs, iriTerm(objIri), VEG_NS + "entityId", literalTerm(Value.of(obj.id().value())));
            tripleCount += 2;

            List<Edge> outgoing = reader.findOutgoingEdges(obj.id()).stream()
                    .sorted(Comparator.comparing(Edge::id))
                    .toList();
            for (Edge edge : outgoing) {
                if (isInternalSystemPredicate(edge.predicateId(), iriPredicateOpt)) {
                    continue;
                }
                String predIri = resolvePredicateIri(reader, resourceIriById, edge.predicateId(), baseNs);
                String targetTerm = resolveTargetTerm(reader, resourceIriById, edge.targetId(), format, baseNs);
                if (targetTerm == null) {
                    continue;
                }
                appendTriple(out, format, baseNs, iriTerm(objIri), predIri, targetTerm);
                tripleCount++;

                List<Edge> edgeAnnotations = reader.findOutgoingEdges(edge.id()).stream()
                        .filter(e -> !isInternalSystemPredicate(e.predicateId(), iriPredicateOpt))
                        .sorted(Comparator.comparing(Edge::id))
                        .toList();
                if (!edgeAnnotations.isEmpty()) {
                    if (options.includeReifiedProvenance()) {
                        String stmtIri = baseNs + "stmt_edge_" + edge.id().value();
                        appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "type", iriTerm(RDF_NS + "Statement"));
                        appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "subject", iriTerm(objIri));
                        appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "predicate", iriTerm(predIri));
                        appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "object", targetTerm);
                        tripleCount += 4;
                        reifiedCount++;

                        for (Edge ann : edgeAnnotations) {
                            String annPredIri = resolvePredicateIri(reader, resourceIriById, ann.predicateId(), baseNs);
                            String annTargetTerm = resolveTargetTerm(reader, resourceIriById, ann.targetId(), format, baseNs);
                            if (annTargetTerm != null) {
                                appendTriple(out, format, baseNs, iriTerm(stmtIri), annPredIri, annTargetTerm);
                                tripleCount++;
                            }
                        }
                    } else {
                        warnings.add("Edge " + edge.id() + " has " + edgeAnnotations.size()
                                + " provenance/annotation edge(s) omitted because includeReifiedProvenance=false");
                    }
                }
            }

            // 3. Optional derived assertions cleanly tagged via veg:assertionOrigin (M-12)
            if (options.includeDerivedAssertions()) {
                List<DerivedAssertion> derived = reader.findDerivedOutgoingAssertions(obj.id());
                int derivedIdx = 0;
                for (DerivedAssertion da : derived) {
                    String predIri = resolvePredicateIri(reader, resourceIriById, da.predicateId(), baseNs);
                    String targetTerm = resolveTargetTerm(reader, resourceIriById, da.targetId(), format, baseNs);
                    if (targetTerm == null) {
                        continue;
                    }
                    appendTriple(out, format, baseNs, iriTerm(objIri), predIri, targetTerm);
                    String stmtIri = baseNs + "stmt_derived_" + obj.id().value() + "_" + (derivedIdx++);
                    appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "type", iriTerm(RDF_NS + "Statement"));
                    appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "subject", iriTerm(objIri));
                    appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "predicate", iriTerm(predIri));
                    appendTriple(out, format, baseNs, iriTerm(stmtIri), RDF_NS + "object", targetTerm);
                    appendTriple(out, format, baseNs, iriTerm(stmtIri), VEG_NS + "assertionOrigin", literalTerm(Value.of(da.origin().name())));
                    tripleCount += 6;
                    reifiedCount++;
                }
            }
        }

        return new RdfExportResult(out.toString(), tripleCount, reifiedCount, warnings);
    }

    private static boolean isInternalAclNode(EntityNode node) {
        return BootstrapIds.META_PRINCIPAL_TYPE.equals(node.typeId())
                || BootstrapIds.META_ACL_ENTRY_TYPE.equals(node.typeId())
                || BootstrapIds.META_PROPAGATION_RULE_TYPE.equals(node.typeId());
    }

    private static boolean isInternalSystemPredicate(EntityId predicateId, Optional<EntityId> iriPredicateOpt) {
        if (predicateId.isBootstrap()) {
            return true;
        }
        return iriPredicateOpt.isPresent() && iriPredicateOpt.get().equals(predicateId);
    }

    private static String resolveResourceIri(
            GraphReader reader,
            EntityNode node,
            Optional<EntityId> iriPredicateOpt,
            String baseNs
    ) {
        if (iriPredicateOpt.isPresent()) {
            List<Edge> iriEdges = reader.findOutgoingEdges(node.id(), iriPredicateOpt.get());
            if (!iriEdges.isEmpty()) {
                Optional<EntityNode> valNode = reader.findEntity(iriEdges.getFirst().targetId());
                if (valNode.isPresent() && valNode.get().payload().isPresent()
                        && valNode.get().payload().get() instanceof Value.StringValue(String storedIri)) {
                    return storedIri;
                }
            }
        }
        if (node.kind() == EntityKind.META_TYPE) {
            String name = reader.resolveName(node.id()).orElse("type_" + node.id().value());
            return baseNs + sanitizeLocalName(name);
        }
        return baseNs + "entity_" + node.id().value();
    }

    private static String resolveTypeIri(
            GraphReader reader,
            Map<EntityId, String> resourceIriById,
            EntityId typeId,
            String baseNs
    ) {
        String existing = resourceIriById.get(typeId);
        if (existing != null) {
            return existing;
        }
        String name = reader.resolveName(typeId).orElse("Resource");
        return baseNs + sanitizeLocalName(name);
    }

    private static String resolvePredicateIri(
            GraphReader reader,
            Map<EntityId, String> resourceIriById,
            EntityId predicateId,
            String baseNs
    ) {
        String existing = resourceIriById.get(predicateId);
        if (existing != null) {
            return existing;
        }
        String name = reader.resolveName(predicateId).orElse("pred_" + predicateId.value());
        return baseNs + sanitizeLocalName(name);
    }

    private static String resolveTargetTerm(
            GraphReader reader,
            Map<EntityId, String> resourceIriById,
            EntityId targetId,
            RdfFormat format,
            String baseNs
    ) {
        Optional<EntityNode> targetOpt = reader.findEntity(targetId);
        if (targetOpt.isEmpty()) {
            return null;
        }
        EntityNode target = targetOpt.get();
        if (target.kind() == EntityKind.VALUE && target.payload().isPresent()) {
            return literalTerm(target.payload().get());
        }
        String iri = resourceIriById.get(targetId);
        if (iri != null) {
            return abbreviateIfTurtle(iri, format, baseNs);
        }
        return abbreviateIfTurtle(baseNs + "entity_" + targetId.value(), format, baseNs);
    }

    private static String sanitizeLocalName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.isEmpty() ? "item" : sb.toString();
    }

    private static Term iriTerm(String iri) {
        return new Term(TermKind.IRI, iri);
    }

    private static String literalTerm(Value value) {
        return switch (value) {
            case Value.StringValue(String s) -> quoteString(s);
            case Value.IntValue(long n) -> "\"" + n + "\"^^<" + XSD_NS + "integer>";
            case Value.DecimalValue(var d) -> "\"" + d.toPlainString() + "\"^^<" + XSD_NS + "decimal>";
            case Value.BooleanValue(boolean b) -> "\"" + b + "\"^^<" + XSD_NS + "boolean>";
            case Value.TimestampValue(var ts) -> "\"" + ts + "\"^^<" + XSD_NS + "dateTime>";
            case Value.BinaryValue bin -> "\"" + Base64.getEncoder().encodeToString(bin.value()) + "\"^^<" + XSD_NS + "base64Binary>";
        };
    }

    private static void appendTriple(
            StringBuilder out,
            RdfFormat format,
            String baseNs,
            Term subject,
            String predicateIri,
            Term object
    ) {
        appendTriple(out, format, baseNs, subject, predicateIri, formatTerm(object, format, baseNs));
    }

    private static void appendTriple(
            StringBuilder out,
            RdfFormat format,
            String baseNs,
            Term subject,
            String predicateIri,
            String formattedObject
    ) {
        out.append(formatTerm(subject, format, baseNs))
                .append(' ')
                .append(formatPredicate(predicateIri, format, baseNs))
                .append(' ')
                .append(formatObjectForFormat(formattedObject, format, baseNs))
                .append(" .\n");
    }

    private static String formatTerm(Term term, RdfFormat format, String baseNs) {
        return abbreviateIfTurtle(term.value(), format, baseNs);
    }

    private static String formatPredicate(String predIri, RdfFormat format, String baseNs) {
        if (format == RdfFormat.TURTLE && (RDF_NS + "type").equals(predIri)) {
            return "a";
        }
        return abbreviateIfTurtle(predIri, format, baseNs);
    }

    private static String formatObjectForFormat(String term, RdfFormat format, String baseNs) {
        if (format == RdfFormat.TURTLE && term.startsWith("\"") && term.endsWith(">")) {
            int sep = term.lastIndexOf("\"^^<");
            if (sep > 0) {
                String dt = term.substring(sep + 4, term.length() - 1);
                if (dt.startsWith(XSD_NS)) {
                    return term.substring(0, sep + 3) + "xsd:" + dt.substring(XSD_NS.length());
                }
            }
        }
        return term;
    }

    private static String abbreviateIfTurtle(String iri, RdfFormat format, String baseNs) {
        if (format == RdfFormat.TURTLE) {
            if (iri.startsWith(RDF_NS)) {
                return "rdf:" + iri.substring(RDF_NS.length());
            }
            if (iri.startsWith(RDFS_NS)) {
                return "rdfs:" + iri.substring(RDFS_NS.length());
            }
            if (iri.startsWith(OWL_NS)) {
                return "owl:" + iri.substring(OWL_NS.length());
            }
            if (iri.startsWith(XSD_NS)) {
                return "xsd:" + iri.substring(XSD_NS.length());
            }
            if (iri.startsWith(VEG_NS)) {
                return "veg:" + iri.substring(VEG_NS.length());
            }
            if (iri.startsWith(baseNs) && isSimplePrefixedLocal(iri.substring(baseNs.length()))) {
                return "ex:" + iri.substring(baseNs.length());
            }
        }
        return "<" + iri + ">";
    }

    private static boolean isSimplePrefixedLocal(String local) {
        if (local.isEmpty()) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static String quoteString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private enum TermKind {
        IRI
    }

    private record Term(TermKind kind, String value) {
    }
}
