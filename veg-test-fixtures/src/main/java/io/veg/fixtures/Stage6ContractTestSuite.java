package io.veg.fixtures;

import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.GraphQuery;
import io.veg.engine.GraphQueryDsl;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.QuerySyntaxException;
import io.veg.engine.RdfGraphExporter;
import io.veg.engine.RdfGraphImporter;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.CommitId;
import io.veg.model.ComparisonOperator;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.Permission;
import io.veg.model.QueryRow;
import io.veg.model.RdfExportOptions;
import io.veg.model.RdfExportResult;
import io.veg.model.RdfFormat;
import io.veg.model.RdfImportResult;
import io.veg.model.Reason;
import io.veg.model.SortDirection;
import io.veg.model.Value;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 6 contract tests covering storage-independent fluent queries,
 * the declarative {@link GraphQueryDsl}, authorized & historical queries, RDF/Turtle & N-Triples
 * export/import round-trips ({@code S-04}, {@code S-06}, {@code S-07}, {@code T18}),
 * and benchmark query suites on bundled LUBM & BSBM datasets.
 */
public abstract class Stage6ContractTestSuite {

    private static final Actor ADMIN = Actor.of("admin", "Admin");
    private static final Actor ALICE = Actor.of("alice", "Alice");

    protected abstract VersionedGraphDatabase createDatabase();

    @Test
    void fluentQueryFiltersTraversesProjectsSortsAndPages() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed catalog"))) {
            EntityId deptType = tx.defineObjectType("Department");
            EntityId empType = tx.defineObjectType("Employee");
            EntityId nameType = tx.defineValueType("Name", ValueType.STRING);
            EntityId ageType = tx.defineValueType("Age", ValueType.INT);
            EntityId salaryType = tx.defineValueType("Salary", ValueType.DECIMAL);

            EntityId empName = tx.definePredicate("name", empType, nameType);
            EntityId empAge = tx.definePredicate("age", empType, ageType);
            EntityId empSalary = tx.definePredicate("salary", empType, salaryType);
            EntityId deptName = tx.definePredicate("deptName", deptType, nameType);
            EntityId worksIn = tx.definePredicate("worksIn", empType, deptType);

            EntityId eng = tx.createObject(deptType);
            tx.connect(eng, deptName, tx.createValue(nameType, Value.of("Engineering")));
            EntityId sales = tx.createObject(deptType);
            tx.connect(sales, deptName, tx.createValue(nameType, Value.of("Sales")));

            addEmployee(tx, empType, empName, empAge, empSalary, worksIn, nameType, ageType, salaryType,
                    "Alice", 34, "95000.00", eng);
            addEmployee(tx, empType, empName, empAge, empSalary, worksIn, nameType, ageType, salaryType,
                    "Clara", 41, "112000.50", eng);
            addEmployee(tx, empType, empName, empAge, empSalary, worksIn, nameType, ageType, salaryType,
                    "Bob", 29, "72000.00", eng);
            addEmployee(tx, empType, empName, empAge, empSalary, worksIn, nameType, ageType, salaryType,
                    "David", 38, "88000.00", sales);
            tx.commit();
        }

        GraphReader reader = db.currentSnapshot();
        List<QueryRow> rows = reader.query()
                .match("e", "Employee")
                .traverse("e", "worksIn", "d", "Department")
                .whereAttribute("d", "deptName", ComparisonOperator.EQ, Value.of("Engineering"))
                .whereAttribute("e", "age", ComparisonOperator.GTE, Value.of(30L))
                .select("e.name", "e.age", "e.salary", "d.deptName")
                .orderBy("e", "age", SortDirection.DESC)
                .limit(2)
                .toList();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).stringValue("e.name")).contains("Clara");
        assertThat(rows.get(0).intValue("e.age")).contains(41L);
        assertThat(rows.get(0).decimalValue("e.salary")).contains(new BigDecimal("112000.50"));
        assertThat(rows.get(1).stringValue("e.name")).contains("Alice");

        // Offset paging check
        List<QueryRow> secondPage = reader.query()
                .match("e", "Employee")
                .select("e.name")
                .orderBy("e.name", SortDirection.ASC)
                .offset(1)
                .limit(2)
                .toList();
        assertThat(secondPage).extracting(r -> r.stringValue("e.name").orElseThrow())
                .containsExactly("Bob", "Clara");
    }

    @Test
    void fluentQuerySupportsStringOperatorsNotEqualAndWhereConnected() {
        VersionedGraphDatabase db = createDatabase();
        EntityId p1;
        EntityId p2;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed people"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId nameType = tx.defineValueType("Name", ValueType.STRING);
            EntityId codeType = tx.defineValueType("Code", ValueType.STRING);
            EntityId name = tx.definePredicate("name", personType, nameType);
            EntityId code = tx.definePredicate("code", personType, codeType);
            EntityId mentors = tx.definePredicate("mentors", personType, personType);

            p1 = tx.createObject(personType);
            p2 = tx.createObject(personType);
            EntityId p3 = tx.createObject(personType);

            tx.connect(p1, name, tx.createValue(nameType, Value.of("Dr. Ada Lovelace")));
            tx.connect(p1, code, tx.createValue(codeType, Value.of("ENG-01")));
            tx.connect(p2, name, tx.createValue(nameType, Value.of("Alan Turing")));
            tx.connect(p2, code, tx.createValue(codeType, Value.of("ENG-02")));
            tx.connect(p3, name, tx.createValue(nameType, Value.of("Eve External")));
            tx.connect(p3, code, tx.createValue(codeType, Value.of("EXT-99")));
            tx.connect(p1, mentors, p2);
            tx.commit();
        }

        GraphReader reader = db.currentSnapshot();
        long startsEngNotEng02 = reader.query()
                .match("p", "Person")
                .whereAttribute("p", "code", ComparisonOperator.STARTS_WITH, Value.of("ENG"))
                .whereAttribute("p", "code", ComparisonOperator.NEQ, Value.of("ENG-02"))
                .whereAttribute("p", "name", ComparisonOperator.CONTAINS, Value.of("Ada"))
                .count();
        assertThat(startsEngNotEng02).isEqualTo(1L);

        List<QueryRow> connected = reader.query()
                .matchEntity("a", p1)
                .match("b", "Person")
                .whereConnected("a", "mentors", "b")
                .select("b.name")
                .toList();
        assertThat(connected).singleElement().satisfies(r -> {
            assertThat(r.entity("b")).contains(p2);
            assertThat(r.stringValue("b.name")).contains("Alan Turing");
        });
    }

    @Test
    void dslQueryParsesJoinsConditionsSortingAndRejectsInvalidSyntax() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed projects"))) {
            EntityId projectType = tx.defineObjectType("Project");
            EntityId taskType = tx.defineObjectType("Task");
            EntityId strType = tx.defineValueType("Str", ValueType.STRING);
            EntityId intType = tx.defineValueType("Int", ValueType.INT);
            EntityId boolType = tx.defineValueType("Bool", ValueType.BOOLEAN);

            EntityId title = tx.definePredicate("title", BootstrapIds.ANY_OBJECT, strType);
            EntityId priority = tx.definePredicate("priority", taskType, intType);
            EntityId active = tx.definePredicate("active", projectType, boolType);
            EntityId hasTask = tx.definePredicate("hasTask", projectType, taskType);

            EntityId prj = tx.createObject(projectType);
            tx.connect(prj, title, tx.createValue(strType, Value.of("VEG Core")));
            tx.connect(prj, active, tx.createValue(boolType, Value.of(true)));

            EntityId t1 = tx.createObject(taskType);
            tx.connect(t1, title, tx.createValue(strType, Value.of("Query DSL")));
            tx.connect(t1, priority, tx.createValue(intType, Value.of(1L)));

            EntityId t2 = tx.createObject(taskType);
            tx.connect(t2, title, tx.createValue(strType, Value.of("RDF Export")));
            tx.connect(t2, priority, tx.createValue(intType, Value.of(2L)));

            tx.connect(prj, hasTask, t1);
            tx.connect(prj, hasTask, t2);
            tx.commit();
        }

        List<QueryRow> dslRows = db.currentSnapshot().executeQuery("""
                MATCH (p:Project)
                JOIN (p)-[hasTask]->(t:Task)
                WHERE p.active = true AND t.priority >= 1 AND t.title CONTAINS "Export"
                SELECT p.title, t.title, t.priority
                ORDER BY t.priority DESC
                LIMIT 5 OFFSET 0
                """);

        assertThat(dslRows).singleElement().satisfies(row -> {
            assertThat(row.stringValue("p.title")).contains("VEG Core");
            assertThat(row.stringValue("t.title")).contains("RDF Export");
            assertThat(row.intValue("t.priority")).contains(2L);
        });

        assertThatThrownBy(() -> GraphQueryDsl.parse(db.currentSnapshot(), "SELECT p.name"))
                .isInstanceOf(QuerySyntaxException.class);
        assertThatThrownBy(() -> GraphQueryDsl.parse(db.currentSnapshot(), "MATCH (p:Person) WHERE broken"))
                .isInstanceOf(QuerySyntaxException.class);
        assertThatThrownBy(() -> GraphQueryDsl.parse(db.currentSnapshot(), "MATCH (p:Person) LIMIT -1"))
                .isInstanceOf(QuerySyntaxException.class);
    }

    @Test
    void queryReflectsConsistentBranchAndHistoricalAsOfSnapshots() {
        VersionedGraphDatabase db = createDatabase();
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("c1"))) {
            EntityId itemType = tx.defineObjectType("Item");
            EntityId priceType = tx.defineValueType("Price", ValueType.INT);
            EntityId nameType = tx.defineValueType("ItemName", ValueType.STRING);
            EntityId name = tx.definePredicate("name", itemType, nameType);
            EntityId price = tx.definePredicate("price", itemType, priceType);

            EntityId i1 = tx.createObject(itemType);
            tx.connect(i1, name, tx.createValue(nameType, Value.of("Widget")));
            tx.connect(i1, price, tx.createValue(priceType, Value.of(100L)));
            c1 = tx.commit();
        }

        db.createBranch("discount-branch", c1);
        try (GraphTransaction tx = db.beginTransaction("discount-branch", ADMIN, Reason.of("add discounted item"))) {
            EntityId itemType = tx.findTypeByName("Item").orElseThrow();
            EntityId priceType = tx.findTypeByName("Price").orElseThrow();
            EntityId nameType = tx.findTypeByName("ItemName").orElseThrow();
            EntityId name = tx.findPredicateByName("name").orElseThrow();
            EntityId price = tx.findPredicateByName("price").orElseThrow();

            EntityId i2 = tx.createObject(itemType);
            tx.connect(i2, name, tx.createValue(nameType, Value.of("Gadget")));
            tx.connect(i2, price, tx.createValue(priceType, Value.of(50L)));
            tx.commit();
        }

        String q = "MATCH (i:Item) SELECT i.name, i.price ORDER BY i.price ASC";
        assertThat(db.asOf(c1).executeQuery(q)).hasSize(1);
        assertThat(db.branchSnapshot("main").executeQuery(q)).hasSize(1);
        assertThat(db.branchSnapshot("discount-branch").executeQuery(q))
                .extracting(r -> r.stringValue("i.name").orElseThrow())
                .containsExactly("Gadget", "Widget");
    }

    @Test
    void authorizedQueriesHideUnauthorizedFieldsAndHonorTimeTravelAndBranchRestrictions() {
        VersionedGraphDatabase db = createDatabase();
        EntityId salaryVal;
        EntityId salaryType;
        CommitId c1;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("hr setup"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId firstNameType = tx.defineValueType("FirstName", ValueType.STRING);
            salaryType = tx.defineValueType("Salary", ValueType.INT);
            EntityId firstName = tx.definePredicate("firstName", personType, firstNameType);
            EntityId salary = tx.definePredicate("salary", personType, salaryType);

            EntityId emp = tx.createObject(personType);
            EntityId fnVal = tx.createValue(firstNameType, Value.of("Clara"));
            salaryVal = tx.createValue(salaryType, Value.of(130_000L));
            tx.connect(emp, firstName, fnVal);
            tx.connect(emp, salary, salaryVal);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, emp);
            tx.grantPermission(alice, Permission.READ, firstNameType);
            tx.denyPermission(alice, Permission.READ, salary);
            c1 = tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        List<QueryRow> aliceRows = aliceReader.executeQuery("""
                MATCH (p:Person)
                SELECT p.firstName, p.salary
                """);
        assertThat(aliceRows).singleElement().satisfies(row -> {
            assertThat(row.stringValue("p.firstName")).contains("Clara");
            assertThat(row.intValue("p.salary")).isEmpty(); // Hidden salary never projected!
        });
        // Alice cannot probe hidden salary values via WHERE either!
        assertThat(aliceReader.executeQuery("MATCH (p:Person) WHERE p.salary >= 100000 SELECT p.firstName")).isEmpty();
        assertThat(salaryVal).isNotNull();

        // T14: Revoke Alice at HEAD, verify authorizedAsOf(c1) returns empty query results
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("revoke alice"))) {
            EntityId alice = tx.findPrincipalByName("alice").orElseThrow();
            tx.denyPermission(alice, Permission.READ, tx.findTypeByName("Person").orElseThrow());
            tx.commit();
        }
        assertThat(db.authorizedAsOf(c1, ALICE).executeQuery("MATCH (p:Person) SELECT p.firstName")).isEmpty();

        // S-05: Branch restriction blocks query completely
        db.restrictBranchReadAccess("main", Set.of("admin"));
        assertThat(db.authorizedBranchSnapshot("main", ALICE).executeQuery("MATCH (p:Person) SELECT p.firstName")).isEmpty();
        assertThat(salaryType).isNotNull();
    }

    @Test
    void lubmUniversityDatasetImportsAndSupportsMultiHopAndDerivedQueries() {
        VersionedGraphDatabase db = createDatabase();
        RdfImportResult result;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Import LUBM Turtle dataset"))) {
            result = tx.importTurtle(RdfBenchmarkDatasets.lubmUniversityTurtle());
            tx.commit();
        }

        assertThat(result.importedObjectCount()).isEqualTo(42); // 2 univ + 4 dept + 8 prof + 6 course + 12 student + 10 pub
        assertThat(result.importedAnnotationCount()).isZero();

        GraphReader snap = db.currentSnapshot();

        // 1. Multi-hop join across 4 types: Publication -> Professor -> Department -> University
        List<QueryRow> univ1Pubs = snap.executeQuery("""
                MATCH (pub:Publication)
                JOIN (pub)-[publicationAuthor]->(prof:Professor)
                JOIN (prof)-[affiliatedDepartment]->(dept:Department)
                JOIN (dept)-[subOrganizationOf]->(u:University)
                WHERE u.country = "DE" AND pub.year >= 2024
                SELECT pub.title, pub.year, prof.name, dept.name, u.name
                ORDER BY pub.year DESC, pub.title ASC
                """);
        assertThat(univ1Pubs).hasSize(3);
        assertThat(univ1Pubs.getFirst().stringValue("pub.title")).contains("Algorithmic Foundations of Triple Export");

        // 2. M-12 transitive sub-property inference (chairOf -> headOf -> worksFor -> memberOf)
        List<QueryRow> explicitMemberOf = snap.executeQuery(
                "MATCH (p:Professor) JOIN (p)-[memberOf]->(u:University) SELECT p.name");
        List<QueryRow> derivedMemberOf = snap.executeQuery("""
                USING DERIVED
                MATCH (p:Professor)
                JOIN (p)-[memberOf]->(u:University)
                WHERE u.name CONTAINS "Lehigh"
                SELECT p.name, p.tenureYears
                ORDER BY p.tenureYears DESC
                """);
        assertThat(explicitMemberOf).hasSize(1); // Only Prof4 had explicit memberOf edge
        assertThat(derivedMemberOf).hasSize(5); // Prof0..Prof4 all infer memberOf Lehigh via chairOf/headOf/worksFor/memberOf!
        assertThat(derivedMemberOf.getFirst().stringValue("p.name")).contains("Prof. Grace Hopper");

        // 3. M-12 inverse predicate inference (hasAlumnus owl:inverseOf degreeFrom)
        List<QueryRow> alumni = snap.executeQuery("""
                USING DERIVED
                MATCH (u:University)
                JOIN (u)-[hasAlumnus]->(s:Student)
                WHERE u.country = "DE" AND s.gpa >= 3.80
                SELECT s.name, s.gpa
                ORDER BY s.gpa DESC
                """);
        assertThat(alumni).hasSize(2)
                .extracting(r -> r.stringValue("s.name").orElseThrow())
                .containsExactly("Hannah Wolf", "Lena Braun");
    }

    @Test
    void bsbmEcommerceDatasetImportsSupportsAnalyticalQueriesAndT18RdfRoundTrip() {
        VersionedGraphDatabase db = createDatabase();
        RdfImportResult imported;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Import BSBM Turtle dataset"))) {
            imported = tx.importTurtle(RdfBenchmarkDatasets.bsbmEcommerceTurtle());
            tx.commit();
        }

        assertThat(imported.importedObjectCount()).isEqualTo(32); // 3 producers + 2 audit sources + 6 products + 3 vendors + 10 offers + 8 reviews
        assertThat(imported.importedAnnotationCount()).isEqualTo(6); // 2 reified statements * 3 annotations each

        GraphReader snap = db.currentSnapshot();

        // Analytical e-commerce offer query sorted by decimal price ASC with paging
        List<QueryRow> topInStockOffers = snap.executeQuery("""
                MATCH (o:Offer)
                JOIN (o)-[product]->(p:Product)
                JOIN (o)-[vendor]->(v:Vendor)
                WHERE o.inStock = true AND o.price <= 500.00 AND p.rating >= 4.0
                SELECT p.productName, v.name, o.price, o.validDays
                ORDER BY o.price ASC
                LIMIT 4 OFFSET 0
                """);
        assertThat(topInStockOffers).hasSize(4);
        assertThat(topInStockOffers.getFirst().stringValue("p.productName")).contains("Industrial Sensor Hub X4");
        assertThat(topInStockOffers.getFirst().decimalValue("o.price")).contains(new BigDecimal("315.90"));

        // Verify imported S-07 edge-to-edge provenance on Review1 --reviewFor--> Product1
        EntityId review1 = imported.resourceEntities().get("http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/Review1");
        EntityId reviewForPred = snap.findPredicateByName("reviewFor").orElseThrow();
        Edge reviewForEdge = snap.findOutgoingEdges(review1, reviewForPred).getFirst();
        List<Edge> provenanceEdges = snap.findOutgoingEdges(reviewForEdge.id());
        assertThat(provenanceEdges).hasSize(3);

        // T18: Export with reified provenance -> zero warnings, exports reified statements
        RdfExportResult ttlExport = snap.exportTurtle();
        assertThat(ttlExport.warnings()).isEmpty();
        assertThat(ttlExport.reifiedAnnotationCount()).isEqualTo(2);
        assertThat(ttlExport.content()).contains("rdf:Statement", "hasConfidence", "hasWeight", "supportedBy");

        // T18: Export omitting reified provenance -> must emit explicit warning (never silent loss!)
        RdfExportOptions noProvOptions = new RdfExportOptions("http://veg.io/data#", RdfFormat.TURTLE, true, false, false);
        RdfExportResult suppressedExport = snap.exportRdf(noProvOptions);
        assertThat(suppressedExport.reifiedAnnotationCount()).isZero();
        assertThat(suppressedExport.warnings()).hasSize(2)
                .allSatisfy(w -> assertThat(w).contains("includeReifiedProvenance=false"));

        // T18 Round-Trip: re-import exported Turtle + N-Triples into fresh databases and assert identical analytical results
        VersionedGraphDatabase roundTripDb = createDatabase();
        RdfImportResult rtImport;
        try (GraphTransaction tx = roundTripDb.beginTransaction(ADMIN, Reason.of("Re-import exported BSBM Turtle"))) {
            rtImport = tx.importTurtle(ttlExport.content());
            tx.commit();
        }
        assertThat(rtImport.importedAnnotationCount()).isEqualTo(6);
        List<QueryRow> roundTripOffers = roundTripDb.currentSnapshot().executeQuery("""
                MATCH (o:Offer)
                JOIN (o)-[product]->(p:Product)
                JOIN (o)-[vendor]->(v:Vendor)
                WHERE o.inStock = true AND o.price <= 500.00 AND p.rating >= 4.0
                SELECT p.productName, v.name, o.price, o.validDays
                ORDER BY o.price ASC
                LIMIT 4 OFFSET 0
                """);
        assertThat(roundTripOffers)
                .extracting(r -> r.decimalValue("o.price").orElseThrow())
                .containsExactlyElementsOf(topInStockOffers.stream().map(r -> r.decimalValue("o.price").orElseThrow()).toList());

        // Verify N-Triples export + import round-trip as well
        RdfExportResult ntExport = snap.exportRdf(RdfExportOptions.nTriplesDefault());
        VersionedGraphDatabase ntDb = createDatabase();
        RdfImportResult ntImport;
        try (GraphTransaction tx = ntDb.beginTransaction(ADMIN, Reason.of("Import N-Triples"))) {
            ntImport = tx.importRdf(ntExport.content(), RdfFormat.N_TRIPLES);
            tx.commit();
        }
        assertThat(ntImport.importedAnnotationCount()).isEqualTo(6);
        assertThat(ntDb.currentSnapshot().query().match("p", "Product").count()).isEqualTo(6L);
    }

    @Test
    void rdfExportWithDerivedAssertionsPreservesM12OriginAcrossRoundTrip() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed inverse predicate"))) {
            EntityId person = tx.defineObjectType("Person");
            EntityId nameType = tx.defineValueType("PersonName", ValueType.STRING);
            EntityId name = tx.definePredicate("personName", person, nameType);
            EntityId parentOf = tx.definePredicate("parentOf", person, person);
            EntityId childOf = tx.definePredicate("childOf", person, person);
            tx.defineInversePredicate(parentOf, childOf);

            EntityId alice = tx.createObject(person);
            EntityId bob = tx.createObject(person);
            tx.connect(alice, name, tx.createValue(nameType, Value.of("Alice")));
            tx.connect(bob, name, tx.createValue(nameType, Value.of("Bob")));
            tx.connect(alice, parentOf, bob);
            tx.commit();
        }

        RdfExportResult derivedExport = RdfGraphExporter.export(db.currentSnapshot(), RdfExportOptions.turtleWithDerived());
        assertThat(derivedExport.content())
                .contains("veg:assertionOrigin", "DERIVED_INVERSE", "owl:inverseOf");

        VersionedGraphDatabase reimported = createDatabase();
        try (GraphTransaction tx = reimported.beginTransaction(ADMIN, Reason.of("reimport M-12"))) {
            tx.importTurtle(derivedExport.content());
            tx.commit();
        }

        // Bob must NOT receive a materialized explicit childOf edge — derived assertions stay purely derived (M-12)!
        List<QueryRow> bobExplicit = reimported.currentSnapshot().executeQuery(
                "MATCH (p:Person) JOIN (p)-[childOf]->(parent:Person) WHERE p.personName = \"Bob\" SELECT parent.personName");
        List<QueryRow> bobDerived = reimported.currentSnapshot().executeQuery(
                "USING DERIVED MATCH (p:Person) JOIN (p)-[childOf]->(parent:Person) WHERE p.personName = \"Bob\" SELECT parent.personName");
        assertThat(bobExplicit).isEmpty();
        assertThat(bobDerived).singleElement()
                .satisfies(r -> assertThat(r.stringValue("parent.personName")).contains("Alice"));
    }

    @Test
    void authorizedRdfExportExcludesHiddenAttributesAndBranchesWithoutLeaking() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed sensitive profile"))) {
            EntityId person = tx.defineObjectType("Person");
            EntityId nameType = tx.defineValueType("Name", ValueType.STRING);
            EntityId secretType = tx.defineValueType("SecretNote", ValueType.STRING);
            EntityId name = tx.definePredicate("name", person, nameType);
            EntityId secret = tx.definePredicate("secret", person, secretType);

            EntityId emp = tx.createObject(person);
            tx.connect(emp, name, tx.createValue(nameType, Value.of("Clara")));
            tx.connect(emp, secret, tx.createValue(secretType, Value.of("TOP_SECRET_PAYLOAD")));

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, emp);
            tx.grantPermission(alice, Permission.READ, nameType);
            tx.denyPermission(alice, Permission.READ, secretType);
            tx.commit();
        }

        RdfExportResult aliceTtl = db.authorizedCurrentSnapshot(ALICE).exportTurtle();
        assertThat(aliceTtl.content()).contains("Clara");
        assertThat(aliceTtl.content()).doesNotContain("TOP_SECRET_PAYLOAD");
    }

    @Test
    void missingSortAttributesAlwaysSortLastAndMissingTypeReturnsEmpty() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed partial attrs"))) {
            EntityId nodeType = tx.defineObjectType("Item");
            EntityId strType = tx.defineValueType("Label", ValueType.STRING);
            EntityId scoreType = tx.defineValueType("Score", ValueType.INT);
            EntityId label = tx.definePredicate("label", nodeType, strType);
            EntityId score = tx.definePredicate("score", nodeType, scoreType);

            EntityId a = tx.createObject(nodeType);
            tx.connect(a, label, tx.createValue(strType, Value.of("NoScore")));
            EntityId b = tx.createObject(nodeType);
            tx.connect(b, label, tx.createValue(strType, Value.of("Low")));
            tx.connect(b, score, tx.createValue(scoreType, Value.of(10L)));
            EntityId c = tx.createObject(nodeType);
            tx.connect(c, label, tx.createValue(strType, Value.of("High")));
            tx.connect(c, score, tx.createValue(scoreType, Value.of(90L)));
            tx.commit();
        }

        List<QueryRow> descRows = db.currentSnapshot().query()
                .match("i", "Item")
                .select("i.label", "i.score")
                .orderBy("i.score", SortDirection.DESC)
                .toList();
        assertThat(descRows).extracting(r -> r.stringValue("i.label").orElseThrow())
                .containsExactly("High", "Low", "NoScore");

        assertThat(db.currentSnapshot().executeQuery("MATCH (x:NonExistent) SELECT x.foo")).isEmpty();
    }

    private static void addEmployee(
            GraphTransaction tx,
            EntityId empType,
            EntityId empNamePred,
            EntityId empAgePred,
            EntityId empSalaryPred,
            EntityId worksInPred,
            EntityId nameType,
            EntityId ageType,
            EntityId salaryType,
            String name,
            long age,
            String salary,
            EntityId dept
    ) {
        EntityId emp = tx.createObject(empType);
        tx.connect(emp, empNamePred, tx.createValue(nameType, Value.of(name)));
        tx.connect(emp, empAgePred, tx.createValue(ageType, Value.of(age)));
        tx.connect(emp, empSalaryPred, tx.createValue(salaryType, Value.of(new BigDecimal(salary))));
        tx.connect(emp, worksInPred, dept);
    }
}
