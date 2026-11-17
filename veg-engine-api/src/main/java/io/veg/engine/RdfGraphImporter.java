package io.veg.engine;

import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.PredicateConstraints;
import io.veg.model.RdfFormat;
import io.veg.model.RdfImportResult;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Storage-independent RDF importer supporting Turtle ({@code .ttl}) and N-Triples ({@code .nt})
 * datasets including prefixes, {@code ;}/{@code ,} continuations, {@code a} shorthand, typed literals,
 * RDFS/OWL predicate axioms ({@code rdfs:subPropertyOf}, {@code owl:inverseOf}), and {@code rdf:Statement}
 * reified edge-to-edge provenance annotations ({@code S-06}, {@code S-07}, {@code T18}).
 */
public final class RdfGraphImporter {

    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    private static final String VEG_NS = "http://veg.io/meta#";

    private static final String RDF_TYPE = RDF_NS + "type";
    private static final String RDF_STATEMENT = RDF_NS + "Statement";
    private static final String RDF_SUBJECT = RDF_NS + "subject";
    private static final String RDF_PREDICATE = RDF_NS + "predicate";
    private static final String RDF_OBJECT = RDF_NS + "object";
    private static final String RDFS_SUB_PROPERTY_OF = RDFS_NS + "subPropertyOf";
    private static final String OWL_INVERSE_OF = OWL_NS + "inverseOf";

    private static final Set<String> SCHEMA_CLASSES = Set.of(
            RDFS_NS + "Class",
            RDF_NS + "Property",
            OWL_NS + "Class",
            OWL_NS + "ObjectProperty",
            OWL_NS + "DatatypeProperty",
            OWL_NS + "Ontology"
    );

    private static final Set<String> SCHEMA_PREDICATES = Set.of(
            OWL_INVERSE_OF,
            RDFS_SUB_PROPERTY_OF,
            RDFS_NS + "domain",
            RDFS_NS + "range",
            RDFS_NS + "subClassOf"
    );

    private sealed interface RdfNode permits ResourceNode, LiteralNode {
    }

    private record ResourceNode(String iri) implements RdfNode {
    }

    private record LiteralNode(Value value) implements RdfNode {
    }

    private record ParsedTriple(String subjectIri, String predicateIri, RdfNode object) {
    }

    private static final class ReificationDraft {
        String subjectIri;
        String predicateIri;
        RdfNode object;
        String assertionOrigin;
        final List<Map.Entry<String, RdfNode>> annotations = new ArrayList<>();
    }

    private RdfGraphImporter() {
    }

    public static RdfImportResult importRdf(GraphSession session, String content, RdfFormat format) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(format, "format must not be null");

        List<ParsedTriple> triples = new TurtleParser(content, format).parse();
        Map<String, ReificationDraft> reifications = new LinkedHashMap<>();
        Set<String> schemaSubjects = new HashSet<>();
        Set<String> allResourceObjects = new LinkedHashSet<>();

        for (ParsedTriple triple : triples) {
            if (RDF_TYPE.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String typeIri)) {
                if (RDF_STATEMENT.equals(typeIri)) {
                    reifications.computeIfAbsent(triple.subjectIri(), ignored -> new ReificationDraft());
                } else if (SCHEMA_CLASSES.contains(typeIri)) {
                    schemaSubjects.add(triple.subjectIri());
                }
            }
            if (RDF_SUBJECT.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String subj)) {
                reifications.computeIfAbsent(triple.subjectIri(), ignored -> new ReificationDraft()).subjectIri = subj;
            } else if (RDF_PREDICATE.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String pred)) {
                reifications.computeIfAbsent(triple.subjectIri(), ignored -> new ReificationDraft()).predicateIri = pred;
            } else if (RDF_OBJECT.equals(triple.predicateIri())) {
                reifications.computeIfAbsent(triple.subjectIri(), ignored -> new ReificationDraft()).object = triple.object();
            } else if ((VEG_NS + "assertionOrigin").equals(triple.predicateIri())
                    && triple.object() instanceof LiteralNode(Value v)
                    && v instanceof Value.StringValue(String origin)) {
                reifications.computeIfAbsent(triple.subjectIri(), ignored -> new ReificationDraft()).assertionOrigin = origin;
            }
            if (triple.object() instanceof ResourceNode(String objIri)) {
                allResourceObjects.add(objIri);
            }
        }

        // Collect annotations on statement nodes & derived triple signatures (M-12 round-trip fidelity)
        Set<String> derivedTripleKeys = new HashSet<>();
        for (ParsedTriple triple : triples) {
            ReificationDraft draft = reifications.get(triple.subjectIri());
            if (draft != null) {
                if (!isReificationStructuralPred(triple.predicateIri())) {
                    draft.annotations.add(Map.entry(triple.predicateIri(), triple.object()));
                }
            }
        }
        for (ReificationDraft draft : reifications.values()) {
            if (draft.assertionOrigin != null && draft.assertionOrigin.startsWith("DERIVED_")
                    && draft.subjectIri != null && draft.predicateIri != null && draft.object != null) {
                derivedTripleKeys.add(tripleKey(draft.subjectIri, draft.predicateIri, draft.object));
            }
        }

        // Pass 1: Schema object types, predicates, subPropertyOf & inverseOf rules
        Map<String, EntityId> typeCache = new HashMap<>();
        Map<String, EntityId> predCache = new HashMap<>();
        Map<ValueType, EntityId> literalTypeCache = new HashMap<>();

        for (ParsedTriple triple : triples) {
            if (reifications.containsKey(triple.subjectIri())) {
                continue;
            }
            if (RDF_TYPE.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String typeIri)) {
                if ((RDFS_NS + "Class").equals(typeIri) || (OWL_NS + "Class").equals(typeIri)) {
                    ensureObjectType(session, typeCache, localName(triple.subjectIri()));
                } else if ((RDF_NS + "Property").equals(typeIri)
                        || (OWL_NS + "ObjectProperty").equals(typeIri)
                        || (OWL_NS + "DatatypeProperty").equals(typeIri)) {
                    ensurePredicate(session, predCache, localName(triple.subjectIri()));
                } else if (!SCHEMA_CLASSES.contains(typeIri) && !RDF_STATEMENT.equals(typeIri)) {
                    ensureObjectType(session, typeCache, localName(typeIri));
                }
            } else if (OWL_INVERSE_OF.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String invPredIri)) {
                EntityId p1 = ensurePredicate(session, predCache, localName(triple.subjectIri()));
                EntityId p2 = ensurePredicate(session, predCache, localName(invPredIri));
                session.defineInversePredicate(p1, p2);
            } else if (RDFS_SUB_PROPERTY_OF.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String superPredIri)) {
                EntityId sub = ensurePredicate(session, predCache, localName(triple.subjectIri()));
                EntityId sup = ensurePredicate(session, predCache, localName(superPredIri));
                session.defineSubPredicate(sub, sup);
            }
        }

        // Determine primary type of each instance resource
        Map<String, String> resourceTypes = new LinkedHashMap<>();
        Set<String> instanceResources = new LinkedHashSet<>();
        for (ParsedTriple triple : triples) {
            if (reifications.containsKey(triple.subjectIri()) || schemaSubjects.contains(triple.subjectIri())) {
                continue;
            }
            if (!isSchemaTriple(triple)) {
                instanceResources.add(triple.subjectIri());
            }
            if (RDF_TYPE.equals(triple.predicateIri()) && triple.object() instanceof ResourceNode(String typeIri)
                    && !SCHEMA_CLASSES.contains(typeIri) && !RDF_STATEMENT.equals(typeIri)) {
                resourceTypes.putIfAbsent(triple.subjectIri(), localName(typeIri));
            }
            if (triple.object() instanceof ResourceNode(String objIri)
                    && !RDF_TYPE.equals(triple.predicateIri())
                    && !SCHEMA_PREDICATES.contains(triple.predicateIri())
                    && !reifications.containsKey(objIri)
                    && !schemaSubjects.contains(objIri)
                    && !SCHEMA_CLASSES.contains(objIri)) {
                instanceResources.add(objIri);
            }
        }
        // Also ensure reification endpoints exist as instances if referenced directly
        for (ReificationDraft draft : reifications.values()) {
            if (draft.assertionOrigin == null && draft.subjectIri != null && !schemaSubjects.contains(draft.subjectIri)) {
                instanceResources.add(draft.subjectIri);
                if (draft.object instanceof ResourceNode(String objIri) && !schemaSubjects.contains(objIri)) {
                    instanceResources.add(objIri);
                }
            }
        }

        // Pass 2: Instantiate VEG objects and store external IRI attribute
        Map<String, EntityId> resourceEntities = new LinkedHashMap<>();
        EntityId iriPredicateId = ensurePredicate(session, predCache, "veg_iri");
        EntityId stringValueType = ensureLiteralValueType(session, literalTypeCache, ValueType.STRING);
        int importedObjects = 0;
        int importedLiterals = 0;
        int importedEdges = 0;
        int importedAnnotations = 0;

        for (String iri : instanceResources) {
            String typeName = resourceTypes.getOrDefault(iri, "Resource");
            EntityId objectTypeId = ensureObjectType(session, typeCache, typeName);
            EntityId objId = session.createObject(objectTypeId);
            resourceEntities.put(iri, objId);
            importedObjects++;

            EntityId iriValId = session.createValue(stringValueType, Value.of(iri));
            session.connect(objId, iriPredicateId, iriValId);
            importedLiterals++;
            importedEdges++;
        }

        // Pass 3: Connect explicit edges and literal values (skipping derived markers and rdf:Statement triples)
        Map<String, EntityId> createdEdgeByTripleKey = new HashMap<>();
        for (ParsedTriple triple : triples) {
            if (reifications.containsKey(triple.subjectIri()) || schemaSubjects.contains(triple.subjectIri())) {
                continue;
            }
            if (isSchemaTriple(triple) || RDF_TYPE.equals(triple.predicateIri())) {
                continue;
            }
            if ((VEG_NS + "entityId").equals(triple.predicateIri())
                    || (VEG_NS + "assertionOrigin").equals(triple.predicateIri())) {
                continue;
            }
            if (derivedTripleKeys.contains(tripleKey(triple.subjectIri(), triple.predicateIri(), triple.object()))) {
                continue;
            }

            EntityId sourceId = resourceEntities.get(triple.subjectIri());
            if (sourceId == null) {
                continue;
            }
            EntityId predId = ensurePredicate(session, predCache, localName(triple.predicateIri()));
            EntityId targetId;
            if (triple.object() instanceof ResourceNode(String objIri)) {
                targetId = resourceEntities.get(objIri);
                if (targetId == null) {
                    continue;
                }
            } else {
                LiteralNode lit = (LiteralNode) triple.object();
                EntityId litTypeId = ensureLiteralValueType(session, literalTypeCache, lit.value().valueType());
                targetId = session.createValue(litTypeId, lit.value());
                importedLiterals++;
            }

            EntityId edgeId = session.connect(sourceId, predId, targetId);
            importedEdges++;
            createdEdgeByTripleKey.putIfAbsent(tripleKey(triple.subjectIri(), triple.predicateIri(), triple.object()), edgeId);
        }

        // Pass 4: Attach rdf:Statement provenance annotations onto matching VEG edges (S-07, T18)
        for (ReificationDraft draft : reifications.values()) {
            if (draft.assertionOrigin != null && draft.assertionOrigin.startsWith("DERIVED_")) {
                continue;
            }
            if (draft.subjectIri == null || draft.predicateIri == null || draft.object == null || draft.annotations.isEmpty()) {
                continue;
            }
            String key = tripleKey(draft.subjectIri, draft.predicateIri, draft.object);
            EntityId edgeId = createdEdgeByTripleKey.get(key);
            if (edgeId == null) {
                EntityId sourceId = resourceEntities.get(draft.subjectIri);
                EntityId predId = ensurePredicate(session, predCache, localName(draft.predicateIri));
                if (sourceId != null) {
                    EntityId targetId;
                    if (draft.object instanceof ResourceNode(String objIri)) {
                        targetId = resourceEntities.get(objIri);
                    } else {
                        LiteralNode lit = (LiteralNode) draft.object;
                        EntityId litTypeId = ensureLiteralValueType(session, literalTypeCache, lit.value().valueType());
                        targetId = session.createValue(litTypeId, lit.value());
                        importedLiterals++;
                    }
                    if (targetId != null) {
                        edgeId = session.connect(sourceId, predId, targetId);
                        createdEdgeByTripleKey.put(key, edgeId);
                        importedEdges++;
                    }
                }
            }
            if (edgeId == null) {
                continue;
            }

            for (var ann : draft.annotations) {
                EntityId annPredId = ensurePredicate(session, predCache, localName(ann.getKey()));
                EntityId annTargetId;
                if (ann.getValue() instanceof ResourceNode(String objIri)) {
                    annTargetId = resourceEntities.get(objIri);
                    if (annTargetId == null) {
                        EntityId docType = ensureObjectType(session, typeCache, "Resource");
                        annTargetId = session.createObject(docType);
                        resourceEntities.put(objIri, annTargetId);
                        importedObjects++;
                    }
                } else {
                    LiteralNode lit = (LiteralNode) ann.getValue();
                    EntityId litTypeId = ensureLiteralValueType(session, literalTypeCache, lit.value().valueType());
                    annTargetId = session.createValue(litTypeId, lit.value());
                    importedLiterals++;
                }
                session.connect(edgeId, annPredId, annTargetId);
                importedEdges++;
                importedAnnotations++;
            }
        }

        return new RdfImportResult(
                resourceEntities,
                importedObjects,
                importedLiterals,
                importedEdges,
                importedAnnotations
        );
    }

    private static boolean isReificationStructuralPred(String predicateIri) {
        return RDF_TYPE.equals(predicateIri)
                || RDF_SUBJECT.equals(predicateIri)
                || RDF_PREDICATE.equals(predicateIri)
                || RDF_OBJECT.equals(predicateIri)
                || (VEG_NS + "assertionOrigin").equals(predicateIri);
    }

    private static boolean isSchemaTriple(ParsedTriple triple) {
        return SCHEMA_PREDICATES.contains(triple.predicateIri())
                || (RDF_TYPE.equals(triple.predicateIri())
                && triple.object() instanceof ResourceNode(String typeIri)
                && SCHEMA_CLASSES.contains(typeIri));
    }

    private static EntityId ensureObjectType(GraphSession session, Map<String, EntityId> cache, String name) {
        EntityId cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Optional<EntityId> existing = session.findTypeByName(name);
        EntityId id = existing.orElseGet(() -> session.defineObjectType(name));
        cache.put(name, id);
        return id;
    }

    private static EntityId ensurePredicate(GraphSession session, Map<String, EntityId> cache, String name) {
        EntityId cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Optional<EntityId> existing = session.findPredicateByName(name);
        EntityId id = existing.orElseGet(() -> session.definePredicate(
                name, BootstrapIds.ANY_ENTITY, BootstrapIds.ANY_ENTITY, PredicateConstraints.unconstrained()));
        cache.put(name, id);
        return id;
    }

    private static EntityId ensureLiteralValueType(
            GraphSession session,
            Map<ValueType, EntityId> cache,
            ValueType scalarType
    ) {
        EntityId cached = cache.get(scalarType);
        if (cached != null) {
            return cached;
        }
        String name = "rdf.literal." + scalarType.name();
        Optional<EntityId> existing = session.findTypeByName(name);
        EntityId id = existing.orElseGet(() -> session.defineValueType(name, scalarType, false));
        cache.put(scalarType, id);
        return id;
    }

    private static String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int idx = Math.max(hash, slash);
        if (idx >= 0 && idx + 1 < iri.length()) {
            return iri.substring(idx + 1);
        }
        int colon = iri.lastIndexOf(':');
        return colon >= 0 && colon + 1 < iri.length() ? iri.substring(colon + 1) : iri;
    }

    private static String tripleKey(String s, String p, RdfNode obj) {
        String objKey = switch (obj) {
            case ResourceNode(String iri) -> "R:" + iri;
            case LiteralNode(Value v) -> "L:" + v.valueType() + ":" + (v instanceof Value.DecimalValue(var d) ? d.stripTrailingZeros().toPlainString() : v);
        };
        return s + "\u0000" + p + "\u0000" + objKey;
    }

    // --- Self-contained Turtle & N-Triples parser ---

    private static final class TurtleParser {
        private final String input;
        private final RdfFormat format;
        private final Map<String, String> prefixes = new HashMap<>();
        private final List<ParsedTriple> triples = new ArrayList<>();
        private String baseIri = "http://veg.io/data#";
        private int pos = 0;
        private int bnodeCounter = 0;

        TurtleParser(String input, RdfFormat format) {
            this.input = input;
            this.format = format;
            this.prefixes.put("rdf", RDF_NS);
            this.prefixes.put("rdfs", RDFS_NS);
            this.prefixes.put("owl", OWL_NS);
            this.prefixes.put("xsd", XSD_NS);
            this.prefixes.put("veg", VEG_NS);
        }

        List<ParsedTriple> parse() {
            while (skipWhitespaceAndComments()) {
                if (format == RdfFormat.TURTLE && (peekWordIgnoreCase("@prefix") || peekWordIgnoreCase("PREFIX"))) {
                    parsePrefixDirective();
                } else if (format == RdfFormat.TURTLE && (peekWordIgnoreCase("@base") || peekWordIgnoreCase("BASE"))) {
                    parseBaseDirective();
                } else {
                    parseStatement();
                }
            }
            return triples;
        }

        private void parsePrefixDirective() {
            boolean atStyle = peekChar('@');
            readWord(); // @prefix or PREFIX
            skipWhitespaceAndComments();
            String prefixToken = readUntilColonAndConsume();
            skipWhitespaceAndComments();
            String iri = readBracketedIri();
            prefixes.put(prefixToken, iri);
            skipWhitespaceAndComments();
            if (peekChar('.')) {
                pos++;
            } else if (atStyle) {
                throw new QuerySyntaxException("Expected '.' after @prefix directive");
            }
        }

        private void parseBaseDirective() {
            readWord();
            skipWhitespaceAndComments();
            this.baseIri = readBracketedIri();
            skipWhitespaceAndComments();
            if (peekChar('.')) {
                pos++;
            }
        }

        private void parseStatement() {
            String subjectIri = parseSubjectOrBlank();
            skipWhitespaceAndComments();
            parsePredicateObjectList(subjectIri);
            skipWhitespaceAndComments();
            expectChar('.');
        }

        private String parseSubjectOrBlank() {
            skipWhitespaceAndComments();
            if (format == RdfFormat.TURTLE && peekChar('[')) {
                pos++;
                String bnodeIri = "_:b" + (++bnodeCounter);
                skipWhitespaceAndComments();
                if (!peekChar(']')) {
                    parsePredicateObjectList(bnodeIri);
                    skipWhitespaceAndComments();
                }
                expectChar(']');
                return bnodeIri;
            }
            return parseResourceIri();
        }

        private void parsePredicateObjectList(String subjectIri) {
            while (true) {
                skipWhitespaceAndComments();
                if (peekChar('.') || peekChar(']')) {
                    return;
                }
                String predicateIri = parsePredicateIri();
                parseObjectList(subjectIri, predicateIri);
                skipWhitespaceAndComments();
                if (peekChar(';')) {
                    while (peekChar(';')) {
                        pos++;
                        skipWhitespaceAndComments();
                    }
                    if (peekChar('.') || peekChar(']')) {
                        return;
                    }
                } else {
                    return;
                }
            }
        }

        private void parseObjectList(String subjectIri, String predicateIri) {
            while (true) {
                skipWhitespaceAndComments();
                RdfNode obj = parseObject();
                triples.add(new ParsedTriple(subjectIri, predicateIri, obj));
                skipWhitespaceAndComments();
                if (peekChar(',')) {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private String parsePredicateIri() {
            if (format == RdfFormat.TURTLE && peekSingleLetterA()) {
                pos++;
                return RDF_TYPE;
            }
            return parseResourceIri();
        }

        private RdfNode parseObject() {
            skipWhitespaceAndComments();
            if (peekChar('"')) {
                return parseLiteral();
            }
            if (format == RdfFormat.TURTLE && peekChar('[')) {
                return new ResourceNode(parseSubjectOrBlank());
            }
            if (peekChar('<') || peekWordStartsBlankNode()) {
                return new ResourceNode(parseResourceIri());
            }
            if (format == RdfFormat.TURTLE && peekBareLiteral()) {
                return parseBareLiteral();
            }
            return new ResourceNode(parseResourceIri());
        }

        private String parseResourceIri() {
            skipWhitespaceAndComments();
            if (peekChar('<')) {
                String raw = readBracketedIri();
                if (!raw.contains("://") && !raw.startsWith("urn:")) {
                    return baseIri + raw;
                }
                return raw;
            }
            String token = readQNameOrBlank();
            if (token.startsWith("_:")) {
                return token;
            }
            int colon = token.indexOf(':');
            if (colon < 0) {
                throw new QuerySyntaxException("Expected IRI or prefixed name at offset " + pos + ", found: " + token);
            }
            String prefix = token.substring(0, colon);
            String local = token.substring(colon + 1);
            String ns = prefixes.get(prefix);
            if (ns == null) {
                throw new QuerySyntaxException("Undeclared RDF prefix: '" + prefix + "'");
            }
            return ns + local;
        }

        private LiteralNode parseLiteral() {
            String raw = readQuotedString();
            if (pos < input.length() && input.charAt(pos) == '@') {
                pos++;
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '-')) {
                    pos++;
                }
                return new LiteralNode(Value.of(raw));
            }
            if (pos + 1 < input.length() && input.charAt(pos) == '^' && input.charAt(pos + 1) == '^') {
                pos += 2;
                String datatypeIri = parseResourceIri();
                return new LiteralNode(convertTypedLiteral(raw, datatypeIri));
            }
            return new LiteralNode(Value.of(raw));
        }

        private LiteralNode parseBareLiteral() {
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c) || c == ',' || c == ';' || c == ']') {
                    break;
                }
                if (c == '.' && (pos + 1 >= input.length() || !Character.isDigit(input.charAt(pos + 1)))) {
                    break;
                }
                sb.append(c);
                pos++;
            }
            String token = sb.toString();
            if ("true".equals(token) || "false".equals(token)) {
                return new LiteralNode(Value.of(Boolean.parseBoolean(token)));
            }
            if (token.matches("[+-]?\\d+")) {
                return new LiteralNode(Value.of(Long.parseLong(token)));
            }
            if (token.matches("[+-]?(?:\\d+\\.\\d*|\\.\\d+)")) {
                return new LiteralNode(Value.of(new BigDecimal(token)));
            }
            throw new QuerySyntaxException("Invalid bare RDF literal: " + token);
        }

        private Value convertTypedLiteral(String raw, String datatypeIri) {
            if ((XSD_NS + "integer").equals(datatypeIri)
                    || (XSD_NS + "int").equals(datatypeIri)
                    || (XSD_NS + "long").equals(datatypeIri)) {
                return Value.of(Long.parseLong(raw.trim()));
            }
            if ((XSD_NS + "decimal").equals(datatypeIri)
                    || (XSD_NS + "double").equals(datatypeIri)
                    || (XSD_NS + "float").equals(datatypeIri)) {
                return Value.of(new BigDecimal(raw.trim()));
            }
            if ((XSD_NS + "boolean").equals(datatypeIri)) {
                return Value.of(Boolean.parseBoolean(raw.trim()));
            }
            if ((XSD_NS + "dateTime").equals(datatypeIri)) {
                return Value.of(Instant.parse(raw.trim()));
            }
            if ((XSD_NS + "base64Binary").equals(datatypeIri)) {
                return Value.of(Base64.getDecoder().decode(raw.trim()));
            }
            return Value.of(raw);
        }

        private boolean peekBareLiteral() {
            if (pos >= input.length()) {
                return false;
            }
            char c = input.charAt(pos);
            if (Character.isDigit(c) || c == '+' || c == '-') {
                return true;
            }
            return (input.startsWith("true", pos) && isTermEnd(pos + 4))
                    || (input.startsWith("false", pos) && isTermEnd(pos + 5));
        }

        private boolean peekSingleLetterA() {
            return pos < input.length() && input.charAt(pos) == 'a'
                    && pos + 1 < input.length() && Character.isWhitespace(input.charAt(pos + 1));
        }

        private boolean peekWordStartsBlankNode() {
            return pos + 1 < input.length() && input.charAt(pos) == '_' && input.charAt(pos + 1) == ':';
        }

        private boolean isTermEnd(int idx) {
            if (idx >= input.length()) {
                return true;
            }
            char c = input.charAt(idx);
            return Character.isWhitespace(c) || c == '.' || c == ';' || c == ',' || c == ']';
        }

        private boolean skipWhitespaceAndComments() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '#') {
                    while (pos < input.length() && input.charAt(pos) != '\n') {
                        pos++;
                    }
                } else {
                    return true;
                }
            }
            return false;
        }

        private boolean peekChar(char expected) {
            return pos < input.length() && input.charAt(pos) == expected;
        }

        private void expectChar(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new QuerySyntaxException("Expected '" + expected + "' at offset " + pos);
            }
            pos++;
        }

        private boolean peekWordIgnoreCase(String word) {
            if (pos + word.length() > input.length()) {
                return false;
            }
            if (!input.regionMatches(true, pos, word, 0, word.length())) {
                return false;
            }
            return pos + word.length() == input.length() || Character.isWhitespace(input.charAt(pos + word.length()));
        }

        private String readWord() {
            int start = pos;
            while (pos < input.length() && !Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
            return input.substring(start, pos);
        }

        private String readUntilColonAndConsume() {
            int start = pos;
            while (pos < input.length() && input.charAt(pos) != ':') {
                if (Character.isWhitespace(input.charAt(pos))) {
                    throw new QuerySyntaxException("Invalid @prefix name at offset " + pos);
                }
                pos++;
            }
            expectChar(':');
            return input.substring(start, pos - 1);
        }

        private String readBracketedIri() {
            expectChar('<');
            int start = pos;
            while (pos < input.length() && input.charAt(pos) != '>') {
                pos++;
            }
            expectChar('>');
            return input.substring(start, pos - 1);
        }

        private String readQNameOrBlank() {
            int start = pos;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c) || c == ',' || c == ';' || c == ']' || c == '<') {
                    break;
                }
                if (c == '.' && (pos + 1 >= input.length()
                        || Character.isWhitespace(input.charAt(pos + 1))
                        || input.charAt(pos + 1) == '#'
                        || input.charAt(pos + 1) == ']'
                        || input.charAt(pos + 1) == ';'
                        || input.charAt(pos + 1) == ',')) {
                    break;
                }
                pos++;
            }
            return input.substring(start, pos);
        }

        private String readQuotedString() {
            expectChar('"');
            if (pos + 1 < input.length() && input.charAt(pos) == '"' && input.charAt(pos + 1) == '"') {
                pos += 2;
                StringBuilder sb = new StringBuilder();
                while (pos < input.length()) {
                    if (input.startsWith("\"\"\"", pos)) {
                        pos += 3;
                        return sb.toString();
                    }
                    sb.append(readEscapedChar());
                }
                throw new QuerySyntaxException("Unterminated long Turtle string literal");
            }
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                sb.append(readEscapedChar());
            }
            throw new QuerySyntaxException("Unterminated string literal at offset " + pos);
        }

        private char readEscapedChar() {
            char c = input.charAt(pos++);
            if (c == '\\' && pos < input.length()) {
                char next = input.charAt(pos++);
                return switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> next;
                };
            }
            return c;
        }
    }
}
