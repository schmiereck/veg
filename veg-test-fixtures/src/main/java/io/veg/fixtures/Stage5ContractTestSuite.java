package io.veg.fixtures;

import io.veg.engine.AccessDeniedException;
import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.DuplicateSchemaElementException;
import io.veg.engine.EntityNotFoundException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.ReferentialIntegrityException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.TypeMismatchException;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.AccessDecision;
import io.veg.model.AccessReason;
import io.veg.model.Actor;
import io.veg.model.AssertionOrigin;
import io.veg.model.BootstrapIds;
import io.veg.model.CommitId;
import io.veg.model.DerivedAssertion;
import io.veg.model.EntityId;
import io.veg.model.MergeResult;
import io.veg.model.Permission;
import io.veg.model.PropagationDirection;
import io.veg.model.Reason;
import io.veg.model.SavepointId;
import io.veg.model.Value;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 5 contract tests covering graph-based ACLs (S-01..S-03),
 * "Verbieten gewinnt" (Deny Wins) & Default Deny, cyclic-safe permission propagation,
 * branch read restrictions (S-05), multi-actor permissions (T13), time-travel security (T14),
 * and explicit vs. derived semantic predicate assertions (M-12).
 */
public abstract class Stage5ContractTestSuite {

    private static final Actor ADMIN = Actor.of("admin", "Admin");
    private static final Actor ALICE = Actor.of("alice", "Alice");
    private static final Actor BOB = Actor.of("bob", "Bob");
    private static final Actor EVE = Actor.of("eve", "Eve");

    protected abstract VersionedGraphDatabase createDatabase();

    @Test
    void defaultDenyAppliesToUnknownPrincipalsAndUnpermittedEntities() {
        VersionedGraphDatabase db = createDatabase();
        EntityId aliceId;
        EntityId docId;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed"))) {
            EntityId docType = tx.defineObjectType("Document");
            docId = tx.createObject(docType);
            aliceId = tx.definePrincipal("alice");
            tx.commit();
        }

        assertThat(aliceId).isNotNull();
        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision aliceDecision = aliceReader.explainAccess(Permission.READ, docId);
        assertThat(aliceDecision.allowed()).isFalse();
        assertThat(aliceDecision.reason()).isEqualTo(AccessReason.DEFAULT_DENY);
        assertThat(aliceReader.findEntity(docId)).isEmpty();
        assertThatThrownBy(() -> aliceReader.getEntityOrThrow(docId))
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(ex -> assertThat(((AccessDeniedException) ex).decision().reason())
                        .isEqualTo(AccessReason.DEFAULT_DENY));

        AuthorizedGraphReader strangerReader = db.authorizedCurrentSnapshot("unregistered-user");
        assertThat(strangerReader.explainAccess(Permission.READ, docId).reason()).isEqualTo(AccessReason.DEFAULT_DENY);
        assertThat(strangerReader.allEntities()).isEmpty();
    }

    @Test
    void principalNamesDoNotCollideWithSchemaTypeNames() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("define type and same-named principal"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId principalPerson = tx.definePrincipal("Person");

            assertThat(tx.findTypeByName("Person")).contains(personType);
            assertThat(tx.findPrincipalByName("Person")).contains(principalPerson);
            assertThat(personType).isNotEqualTo(principalPerson);

            assertThatThrownBy(() -> tx.definePrincipal("Person"))
                    .isInstanceOf(DuplicateSchemaElementException.class);
            tx.commit();
        }
    }

    @Test
    void s01AttributeLevelAccessControlHidesSalaryWhileShowingFirstName() {
        VersionedGraphDatabase db = createDatabase();
        EntityId employee;
        EntityId firstNameVal;
        EntityId salaryVal;
        EntityId hasFirstName;
        EntityId hasSalary;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("S-01 setup"))) {
            EntityId personType = tx.defineObjectType("Person");
            EntityId firstNameType = tx.defineValueType("FirstName", ValueType.STRING);
            EntityId salaryType = tx.defineValueType("Salary", ValueType.INT);
            hasFirstName = tx.definePredicate("hasFirstName", personType, firstNameType);
            hasSalary = tx.definePredicate("hasSalary", personType, salaryType);

            employee = tx.createObject(personType);
            firstNameVal = tx.createValue(firstNameType, Value.of("Clara"));
            salaryVal = tx.createValue(salaryType, Value.of(125_000L));
            tx.connect(employee, hasFirstName, firstNameVal);
            tx.connect(employee, hasSalary, salaryVal);

            EntityId alicePrincipal = tx.definePrincipal("alice");
            tx.grantPermission(alicePrincipal, Permission.READ, employee);
            tx.grantPermission(alicePrincipal, Permission.READ, firstNameType);
            // Salary has neither ALLOW (default deny) and explicit predicate DENY
            tx.denyPermission(alicePrincipal, Permission.READ, hasSalary);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.findEntity(employee)).isPresent();
        assertThat(aliceReader.findEntity(firstNameVal)).isPresent();
        assertThat(aliceReader.findEntity(salaryVal)).isEmpty();
        assertThat(aliceReader.findOutgoingEdges(employee))
                .extracting(e -> e.predicateId())
                .containsExactly(hasFirstName);
        assertThat(aliceReader.allEntities())
                .filteredOn(n -> n.kind() != io.veg.model.EntityKind.EDGE && n.kind() != io.veg.model.EntityKind.META_TYPE)
                .extracting(n -> n.id())
                .containsExactlyInAnyOrder(employee, firstNameVal);
        assertThat(aliceReader.allEntities())
                .extracting(n -> n.id())
                .contains(employee, firstNameVal)
                .doesNotContain(salaryVal);
        assertThat(aliceReader.explainAccess(Permission.READ, salaryVal).allowed()).isFalse();
    }

    @Test
    void s02DeclarativePropagationGrantsReadOnDocumentsWithoutGrantingWriteOrDelete() {
        VersionedGraphDatabase db = createDatabase();
        EntityId project;
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("S-02 setup"))) {
            EntityId projectType = tx.defineObjectType("Project");
            EntityId docType = tx.defineObjectType("Document");
            EntityId containsDoc = tx.definePredicate("containsDocument", projectType, docType);

            project = tx.createObject(projectType);
            doc = tx.createObject(docType);
            tx.connect(project, containsDoc, doc);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, project);
            tx.definePermissionPropagation(containsDoc, PropagationDirection.OUTGOING, Permission.READ);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision readDoc = aliceReader.explainAccess(Permission.READ, doc);
        assertThat(readDoc.allowed()).isTrue();
        assertThat(readDoc.reason()).isEqualTo(AccessReason.INHERITED_ALLOW);
        assertThat(readDoc.winningPath()).startsWith(doc, project);
        assertThat(aliceReader.findEntity(doc)).isPresent();

        assertThat(aliceReader.explainAccess(Permission.WRITE, doc).allowed()).isFalse();
        assertThat(aliceReader.explainAccess(Permission.DELETE, doc).allowed()).isFalse();
        assertThat(aliceReader.explainAccess(Permission.DELETE, doc).reason()).isEqualTo(AccessReason.DEFAULT_DENY);
    }

    @Test
    void incomingPropagationDirectionPropagatesFromTargetToSource() {
        VersionedGraphDatabase db = createDatabase();
        EntityId project;
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("INCOMING propagation"))) {
            EntityId docType = tx.defineObjectType("Document");
            EntityId projectType = tx.defineObjectType("Project");
            EntityId belongsTo = tx.definePredicate("belongsTo", docType, projectType);

            project = tx.createObject(projectType);
            doc = tx.createObject(docType);
            tx.connect(doc, belongsTo, project);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, project);
            tx.definePermissionPropagation(belongsTo, PropagationDirection.INCOMING, Permission.READ);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision decision = aliceReader.explainAccess(Permission.READ, doc);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AccessReason.INHERITED_ALLOW);
        assertThat(decision.winningPath()).startsWith(doc, project);
    }

    @Test
    void bothPropagationDirectionPropagatesBidirectionally() {
        VersionedGraphDatabase db = createDatabase();
        EntityId n1;
        EntityId n2;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("BOTH propagation"))) {
            EntityId folderType = tx.defineObjectType("Folder");
            EntityId linked = tx.definePredicate("linked", folderType, folderType);
            n1 = tx.createObject(folderType);
            n2 = tx.createObject(folderType);
            tx.connect(n1, linked, n2);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, n2);
            tx.definePermissionPropagation(linked, PropagationDirection.BOTH, Permission.READ);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.explainAccess(Permission.READ, n1).allowed()).isTrue();
    }

    @Test
    void denyWinsOverAllowWhenSiblingGroupsConflict() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Deny wins across groups"))) {
            EntityId docType = tx.defineObjectType("Document");
            doc = tx.createObject(docType);

            EntityId alice = tx.definePrincipal("alice");
            EntityId engineering = tx.definePrincipal("engineering");
            EntityId suspended = tx.definePrincipal("suspended-reviewers");
            tx.addGroupMember(alice, engineering);
            tx.addGroupMember(alice, suspended);

            tx.grantPermission(engineering, Permission.READ, doc);
            tx.denyPermission(suspended, Permission.READ, doc);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision decision = aliceReader.explainAccess(Permission.READ, doc);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AccessReason.DIRECT_DENY);
        assertThat(aliceReader.findEntity(doc)).isEmpty();
    }

    @Test
    void denyWinsEvenWhenDirectAllowConflictsWithInheritedParentDeny() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Inherited deny vs direct allow"))) {
            EntityId folderType = tx.defineObjectType("Folder");
            EntityId docType = tx.defineObjectType("Document");
            EntityId contains = tx.definePredicate("contains", folderType, docType);

            EntityId lockedFolder = tx.createObject(folderType);
            doc = tx.createObject(docType);
            tx.connect(lockedFolder, contains, doc);

            EntityId alice = tx.definePrincipal("alice");
            tx.definePermissionPropagation(contains, PropagationDirection.OUTGOING, Permission.READ);
            // Direct ALLOW on leaf, DENY on ancestor folder -> "Verbieten gewinnt" must deny!
            tx.grantPermission(alice, Permission.READ, doc);
            tx.denyPermission(alice, Permission.READ, lockedFolder);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision decision = aliceReader.explainAccess(Permission.READ, doc);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AccessReason.INHERITED_DENY);
        assertThat(aliceReader.findEntity(doc)).isEmpty();
    }

    @Test
    void denyWinsAcrossDiamondMultiPathPropagation() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Diamond propagation"))) {
            EntityId projectType = tx.defineObjectType("Project");
            EntityId docType = tx.defineObjectType("Document");
            EntityId includes = tx.definePredicate("includes", projectType, docType);

            EntityId openProject = tx.createObject(projectType);
            EntityId classifiedProject = tx.createObject(projectType);
            doc = tx.createObject(docType);
            tx.connect(openProject, includes, doc);
            tx.connect(classifiedProject, includes, doc);

            EntityId alice = tx.definePrincipal("alice");
            tx.definePermissionPropagation(includes, PropagationDirection.OUTGOING, Permission.READ);
            tx.grantPermission(alice, Permission.READ, openProject);
            tx.denyPermission(alice, Permission.READ, classifiedProject);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision decision = aliceReader.explainAccess(Permission.READ, doc);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AccessReason.INHERITED_DENY);
    }

    @Test
    void hierarchicalGroupMembershipTransitivelyResolvesAndStopsOnCycles() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Cyclic groups"))) {
            EntityId docType = tx.defineObjectType("Document");
            doc = tx.createObject(docType);

            EntityId alice = tx.definePrincipal("alice");
            EntityId g1 = tx.definePrincipal("g1");
            EntityId g2 = tx.definePrincipal("g2");
            tx.addGroupMember(alice, g1);
            tx.addGroupMember(g1, g2);
            tx.addGroupMember(g2, g1); // Cycle

            tx.grantPermission(g2, Permission.READ, doc);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.explainAccess(Permission.READ, doc).allowed()).isTrue();
        assertThat(aliceReader.explainAccess(Permission.WRITE, doc).allowed()).isFalse();
    }

    @Test
    void cyclicDataPropagationGraphTerminatesDeterministically() {
        VersionedGraphDatabase db = createDatabase();
        EntityId n1;
        EntityId n2;
        EntityId n3;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Cyclic propagation"))) {
            EntityId nodeType = tx.defineObjectType("Node");
            EntityId next = tx.definePredicate("next", nodeType, nodeType);
            n1 = tx.createObject(nodeType);
            n2 = tx.createObject(nodeType);
            n3 = tx.createObject(nodeType);
            tx.connect(n1, next, n2);
            tx.connect(n2, next, n3);
            tx.connect(n3, next, n1);

            EntityId alice = tx.definePrincipal("alice");
            EntityId bob = tx.definePrincipal("bob");
            tx.definePermissionPropagation(next, PropagationDirection.OUTGOING, Permission.READ);
            tx.grantPermission(alice, Permission.READ, n1);
            tx.grantPermission(bob, Permission.READ, n1);
            tx.denyPermission(bob, Permission.READ, n2);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.explainAccess(Permission.READ, n3).allowed()).isTrue();

        AuthorizedGraphReader bobReader = db.authorizedCurrentSnapshot(BOB);
        assertThat(bobReader.explainAccess(Permission.READ, n3).allowed()).isFalse();
        assertThat(bobReader.explainAccess(Permission.READ, n3).reason()).isEqualTo(AccessReason.INHERITED_DENY);
    }

    @Test
    void perEdgeAclRestrictsSingleEdgeWithoutHidingSharedEndpoints() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc1;
        EntityId doc2;
        EntityId secretEdge;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("Edge-level deny"))) {
            EntityId docType = tx.defineObjectType("Document");
            EntityId cites = tx.definePredicate("cites", docType, docType);
            doc1 = tx.createObject(docType);
            doc2 = tx.createObject(docType);
            secretEdge = tx.connect(doc1, cites, doc2);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, doc1);
            tx.grantPermission(alice, Permission.READ, doc2);
            tx.denyPermission(alice, Permission.READ, secretEdge);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.findEntity(doc1)).isPresent();
        assertThat(aliceReader.findEntity(doc2)).isPresent();
        assertThat(aliceReader.findEdge(secretEdge)).isEmpty();
        assertThat(aliceReader.explainAccess(Permission.READ, secretEdge).reason()).isEqualTo(AccessReason.DIRECT_DENY);
    }

    @Test
    void adminPermissionGrantsAllOperationsAndExplicitDenyWriteStillBlocksWrite() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("ADMIN vs DENY WRITE"))) {
            EntityId docType = tx.defineObjectType("Document");
            doc = tx.createObject(docType);
            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.ADMIN, doc);
            tx.denyPermission(alice, Permission.WRITE, doc);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.explainAccess(Permission.READ, doc).allowed()).isTrue();
        assertThat(aliceReader.explainAccess(Permission.DELETE, doc).allowed()).isTrue();
        assertThat(aliceReader.explainAccess(Permission.ADMIN, doc).allowed()).isTrue();
        assertThat(aliceReader.explainAccess(Permission.WRITE, doc).allowed()).isFalse();
        assertThat(aliceReader.explainAccess(Permission.WRITE, doc).reason()).isEqualTo(AccessReason.DIRECT_DENY);
    }

    @Test
    void s03ExplainAccessReportsReasonWinningPathAndAuthorizedCountsFilterSecretNodes() {
        VersionedGraphDatabase db = createDatabase();
        EntityId org;
        EntityId team;
        EntityId secretDoc;
        EntityId aclGrant;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("S-03 explainability"))) {
            EntityId orgType = tx.defineObjectType("Org");
            EntityId teamType = tx.defineObjectType("Team");
            EntityId docType = tx.defineObjectType("Document");
            EntityId hasTeam = tx.definePredicate("hasTeam", orgType, teamType);

            org = tx.createObject(orgType);
            team = tx.createObject(teamType);
            secretDoc = tx.createObject(docType);
            tx.connect(org, hasTeam, team);

            EntityId alice = tx.definePrincipal("alice");
            tx.definePermissionPropagation(hasTeam, PropagationDirection.OUTGOING, Permission.READ);
            aclGrant = tx.grantPermission(alice, Permission.READ, org);
            tx.commit();
        }

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        AccessDecision decision = aliceReader.explainAccess(Permission.READ, team);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AccessReason.INHERITED_ALLOW);
        assertThat(decision.winningPath()).containsExactly(team, org, aclGrant);
        assertThat(decision.explanation()).isNotBlank();

        assertThat(aliceReader.entityCount()).isEqualTo(3L); // org, team, and readable hasTeam edge
        assertThat(aliceReader.findEntity(secretDoc)).isEmpty();
        assertThat(aliceReader.edgeCount()).isEqualTo(1L);
    }

    @Test
    void s05BranchReadRestrictionBlocksUnauthorizedActorsAcrossSnapshotAsOfAndHistory() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        CommitId secretCommit;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("base commit on main"))) {
            tx.defineObjectType("Document");
            tx.definePrincipal("alice");
            tx.definePrincipal("bob");
            tx.commit();
        }

        db.createBranch("confidential", db.headCommitId());
        try (GraphTransaction tx = db.beginTransaction("confidential", ADMIN, Reason.of("confidential doc"))) {
            EntityId docType = tx.findTypeByName("Document").orElseThrow();
            doc = tx.createObject(docType);
            EntityId alice = tx.findPrincipalByName("alice").orElseThrow();
            EntityId bob = tx.findPrincipalByName("bob").orElseThrow();
            tx.grantPermission(alice, Permission.READ, doc);
            tx.grantPermission(bob, Permission.READ, doc);
            secretCommit = tx.commit();
        }

        db.restrictBranchReadAccess("confidential", Set.of("alice"));

        AuthorizedGraphReader aliceSecret = db.authorizedBranchSnapshot("confidential", ALICE);
        assertThat(aliceSecret.findEntity(doc)).isPresent();

        AuthorizedGraphReader bobSecret = db.authorizedBranchSnapshot("confidential", BOB);
        assertThat(bobSecret.findEntity(doc)).isEmpty();
        assertThat(bobSecret.allEntities()).isEmpty();
        assertThat(bobSecret.entityCount()).isZero();
        assertThat(bobSecret.explainAccess(Permission.READ, doc).reason()).isEqualTo(AccessReason.BRANCH_RESTRICTED);
        assertThatThrownBy(() -> bobSecret.getEntityOrThrow(doc))
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(ex -> assertThat(((AccessDeniedException) ex).decision().reason())
                        .isEqualTo(AccessReason.BRANCH_RESTRICTED));

        AuthorizedGraphReader bobHistorical = db.authorizedAsOf(secretCommit, BOB);
        assertThat(bobHistorical.findEntity(doc)).isEmpty();
        assertThat(bobHistorical.explainAccess(Permission.READ, doc).reason()).isEqualTo(AccessReason.BRANCH_RESTRICTED);
    }

    @Test
    void t13MultiActorAclMatrixAndGroupRoleCheck() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("T13 matrix"))) {
            EntityId docType = tx.defineObjectType("Document");
            doc = tx.createObject(docType);

            EntityId alice = tx.definePrincipal("alice");
            EntityId bob = tx.definePrincipal("bob");
            tx.definePrincipal("eve");
            EntityId writers = tx.definePrincipal("writers");
            EntityId readers = tx.definePrincipal("readers");
            tx.addGroupMember(alice, writers);
            tx.addGroupMember(writers, readers);
            tx.addGroupMember(bob, readers);

            tx.grantPermission(readers, Permission.READ, doc);
            tx.grantPermission(writers, Permission.WRITE, doc);
            tx.commit();
        }

        assertThat(db.authorizedCurrentSnapshot(ALICE).explainAccess(Permission.READ, doc).allowed()).isTrue();
        assertThat(db.authorizedCurrentSnapshot(ALICE).explainAccess(Permission.WRITE, doc).allowed()).isTrue();
        assertThat(db.authorizedCurrentSnapshot(ALICE).explainAccess(Permission.DELETE, doc).allowed()).isFalse();

        assertThat(db.authorizedCurrentSnapshot(BOB).explainAccess(Permission.READ, doc).allowed()).isTrue();
        assertThat(db.authorizedCurrentSnapshot(BOB).explainAccess(Permission.WRITE, doc).allowed()).isFalse();

        assertThat(db.authorizedCurrentSnapshot(EVE).explainAccess(Permission.READ, doc).allowed()).isFalse();
        assertThat(db.authorizedCurrentSnapshot(EVE).explainAccess(Permission.WRITE, doc).allowed()).isFalse();
    }

    @Test
    void t14TimeTravelSecurityBlocksHistoricalReadAfterRevocationAtHead() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        EntityId aliceMembershipEdge;
        CommitId historicalCommitWithAccess;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("grant in past"))) {
            EntityId docType = tx.defineObjectType("Document");
            doc = tx.createObject(docType);

            EntityId alice = tx.definePrincipal("alice");
            EntityId bob = tx.definePrincipal("bob");
            EntityId engineering = tx.definePrincipal("engineering");
            aliceMembershipEdge = tx.addGroupMember(alice, engineering);
            tx.addGroupMember(bob, engineering);
            tx.grantPermission(engineering, Permission.READ, doc);
            historicalCommitWithAccess = tx.commit();
        }

        // Bob and Alice can both read at c1 while c1 is HEAD
        assertThat(db.authorizedAsOf(historicalCommitWithAccess, ALICE).findEntity(doc)).isPresent();
        assertThat(db.authorizedAsOf(historicalCommitWithAccess, BOB).findEntity(doc)).isPresent();

        // Commit 2: remove Alice from engineering group AND explicitly deny Alice READ
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("revoke Alice access"))) {
            tx.disconnect(aliceMembershipEdge);
            EntityId alice = tx.findPrincipalByName("alice").orElseThrow();
            tx.denyPermission(alice, Permission.READ, doc);
            tx.commit();
        }

        // T14: Alice must NOT bypass current revocation by querying authorizedAsOf(historicalCommitWithAccess)!
        AuthorizedGraphReader aliceHistorical = db.authorizedAsOf(historicalCommitWithAccess, ALICE);
        AccessDecision historicalDecision = aliceHistorical.explainAccess(Permission.READ, doc);
        assertThat(historicalDecision.allowed()).isFalse();
        assertThat(historicalDecision.reason()).isEqualTo(AccessReason.HISTORICAL_HEAD_DENY);
        assertThat(aliceHistorical.findEntity(doc)).isEmpty();
        assertThatThrownBy(() -> aliceHistorical.getEntityOrThrow(doc))
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(ex -> assertThat(((AccessDeniedException) ex).decision().reason())
                        .isEqualTo(AccessReason.HISTORICAL_HEAD_DENY));

        // Bob still holds access at HEAD, so his authorizedAsOf(c1) succeeds!
        assertThat(db.authorizedAsOf(historicalCommitWithAccess, BOB).findEntity(doc)).isPresent();
    }

    @Test
    void aclRulesSupportSavepointsRevertAndThreeWayMergeAcrossBranches() {
        VersionedGraphDatabase db = createDatabase();
        EntityId doc;
        EntityId alice;
        EntityId bob;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("base schema + principals"))) {
            EntityId docType = tx.defineObjectType("Document");
            doc = tx.createObject(docType);
            alice = tx.definePrincipal("alice");
            bob = tx.definePrincipal("bob");

            SavepointId sp = tx.createSavepoint("before-temporary-grant");
            tx.grantPermission(bob, Permission.READ, doc);
            assertThat(tx.evaluateAccess("bob", Permission.READ, doc).allowed()).isTrue();
            tx.rollbackToSavepoint(sp);
            assertThat(tx.evaluateAccess("bob", Permission.READ, doc).allowed()).isFalse();
            tx.commit();
        }

        db.createBranch("acl-feature", db.headCommitId());
        try (GraphTransaction tx = db.beginTransaction("acl-feature", ADMIN, Reason.of("grant alice on feature"))) {
            tx.grantPermission(alice, Permission.READ, doc);
            tx.commit();
        }

        MergeResult merged = db.merge("acl-feature", "main", ADMIN, Reason.of("merge ACL feature"));
        assertThat(merged.succeeded()).isTrue();
        assertThat(db.authorizedCurrentSnapshot(ALICE).findEntity(doc)).isPresent();
        assertThat(db.authorizedCurrentSnapshot(BOB).findEntity(doc)).isEmpty();

        CommitId featureMergeCommit = db.headCommitId();
        db.revert(featureMergeCommit, ADMIN, Reason.of("revert ACL grant"));
        assertThat(db.authorizedCurrentSnapshot(ALICE).findEntity(doc)).isEmpty();

        // Verify deterministic replay reproduces principal index and ACL enforcement
        GraphReader replayed = db.replayAsOf(featureMergeCommit);
        assertThat(replayed.evaluateAccess("alice", Permission.READ, doc).allowed()).isTrue();
    }

    @Test
    void stagingWorkspaceSupportsStage5AclAndSemanticMutations() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("seed types"))) {
            EntityId person = tx.defineObjectType("Person");
            tx.definePredicate("parentOf", person, person);
            tx.definePredicate("childOf", person, person);
            tx.commit();
        }

        StagingWorkspace workspace = db.createStagingWorkspace("main");
        EntityId personType = workspace.findTypeByName("Person").orElseThrow();
        EntityId parentOf = workspace.findPredicateByName("parentOf").orElseThrow();
        EntityId childOf = workspace.findPredicateByName("childOf").orElseThrow();

        EntityId aliceEntity = workspace.createObject(personType);
        EntityId bobEntity = workspace.createObject(personType);
        workspace.connect(aliceEntity, parentOf, bobEntity);
        EntityId alicePrincipal = workspace.definePrincipal("alice");
        workspace.grantPermission(alicePrincipal, Permission.READ, aliceEntity);
        workspace.grantPermission(alicePrincipal, Permission.READ, bobEntity);
        workspace.defineInversePredicate(parentOf, childOf);

        workspace.stageEntity(aliceEntity);
        workspace.stageEntity(bobEntity);
        workspace.stageEntity(alicePrincipal);
        workspace.stageAll();
        workspace.commitStaged(ADMIN, Reason.of("staged entities, ACLs and inverse predicate"));

        assertThat(db.authorizedCurrentSnapshot(ALICE).findDerivedOutgoingAssertions(bobEntity))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.predicateId()).isEqualTo(childOf);
                    assertThat(a.targetId()).isEqualTo(aliceEntity);
                    assertThat(a.origin()).isEqualTo(AssertionOrigin.DERIVED_INVERSE);
                });
    }

    @Test
    void m12InversePredicateInferenceKeepsExplicitAndDerivedSeparated() {
        VersionedGraphDatabase db = createDatabase();
        EntityId alice;
        EntityId bob;
        EntityId parentOf;
        EntityId childOf;
        EntityId explicitEdgeId;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("M-12 inverse predicates"))) {
            EntityId personType = tx.defineObjectType("Person");
            parentOf = tx.definePredicate("parentOf", personType, personType);
            childOf = tx.definePredicate("childOf", personType, personType);
            tx.defineInversePredicate(parentOf, childOf);

            alice = tx.createObject(personType);
            bob = tx.createObject(personType);
            explicitEdgeId = tx.connect(alice, parentOf, bob);
            tx.commit();
        }

        GraphReader snap = db.currentSnapshot();
        // Explicit stored edges only on Alice -> Bob; Bob has zero stored outgoing edges!
        assertThat(snap.findOutgoingEdges(alice)).hasSize(1);
        assertThat(snap.findOutgoingEdges(bob)).isEmpty();

        // Derived inverse query on Bob yields (bob, childOf, alice) cleanly tagged DERIVED_INVERSE
        List<DerivedAssertion> bobDerived = snap.findDerivedOutgoingAssertions(bob);
        assertThat(bobDerived).singleElement().satisfies(assertion -> {
            assertThat(assertion.sourceId()).isEqualTo(bob);
            assertThat(assertion.predicateId()).isEqualTo(childOf);
            assertThat(assertion.targetId()).isEqualTo(alice);
            assertThat(assertion.origin()).isEqualTo(AssertionOrigin.DERIVED_INVERSE);
            assertThat(assertion.backingEdgeId()).contains(explicitEdgeId);
            assertThat(assertion.viaPredicateId()).contains(parentOf);
        });

        // Explicit + derived combined on Alice stays purely EXPLICIT when no incoming childOf exists
        assertThat(snap.findAllOutgoingAssertions(alice)).singleElement().satisfies(assertion -> {
            assertThat(assertion.origin()).isEqualTo(AssertionOrigin.EXPLICIT);
            assertThat(assertion.predicateId()).isEqualTo(parentOf);
            assertThat(assertion.backingEdgeId()).contains(explicitEdgeId);
        });
    }

    @Test
    void m12TransitiveSuperPredicateInferenceIsCycleSafeAndFilteredByAuthorization() {
        VersionedGraphDatabase db = createDatabase();
        EntityId manager;
        EntityId dev;
        EntityId secretExec;
        EntityId manages;
        EntityId supervises;
        EntityId oversees;
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("M-12 super-predicates + auth filtering"))) {
            EntityId personType = tx.defineObjectType("Person");
            manages = tx.definePredicate("manages", personType, personType);
            supervises = tx.definePredicate("supervises", personType, personType);
            oversees = tx.definePredicate("oversees", personType, personType);

            tx.defineSubPredicate(manages, supervises);
            tx.defineSubPredicate(supervises, oversees);
            tx.defineSubPredicate(oversees, manages); // Cycle intentionally tests loop safety

            manager = tx.createObject(personType);
            dev = tx.createObject(personType);
            secretExec = tx.createObject(personType);
            tx.connect(manager, manages, dev);
            tx.connect(manager, manages, secretExec);

            EntityId alice = tx.definePrincipal("alice");
            tx.grantPermission(alice, Permission.READ, manager);
            tx.grantPermission(alice, Permission.READ, dev);
            // secretExec is NOT allowed -> derived assertions must NOT leak secretExec!
            tx.commit();
        }

        GraphReader rawSnap = db.currentSnapshot();
        List<DerivedAssertion> rawAll = rawSnap.findAllOutgoingAssertions(manager);
        assertThat(rawAll).hasSize(6); // 2 explicit (manages) + 4 derived (supervises, oversees for each)

        List<DerivedAssertion> filteredByPred = rawSnap.findDerivedOutgoingAssertions(manager, oversees);
        assertThat(filteredByPred).hasSize(2)
                .allSatisfy(a -> assertThat(a.origin()).isEqualTo(AssertionOrigin.DERIVED_SUPER_PREDICATE));

        AuthorizedGraphReader aliceReader = db.authorizedCurrentSnapshot(ALICE);
        assertThat(aliceReader.findAllOutgoingAssertions(manager))
                .extracting(DerivedAssertion::targetId)
                .containsOnly(dev);
    }

    @Test
    void validationRejectsInvalidAclTargetsNonPrincipalsAndSelfSubPredicate() {
        VersionedGraphDatabase db = createDatabase();
        try (GraphTransaction tx = db.beginTransaction(ADMIN, Reason.of("validation checks"))) {
            EntityId docType = tx.defineObjectType("Document");
            EntityId rel = tx.definePredicate("rel", docType, docType);
            EntityId doc = tx.createObject(docType);
            EntityId alice = tx.definePrincipal("alice");
            EntityId edgeId = tx.connect(doc, rel, doc);
            tx.grantPermission(alice, Permission.READ, edgeId);

            assertThatThrownBy(() -> tx.grantPermission(doc, Permission.READ, doc))
                    .isInstanceOf(TypeMismatchException.class);
            assertThatThrownBy(() -> tx.grantPermission(alice, Permission.READ, new EntityId(999_999L)))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> tx.addGroupMember(doc, alice))
                    .isInstanceOf(TypeMismatchException.class);
            assertThatThrownBy(() -> tx.definePermissionPropagation(doc, PropagationDirection.OUTGOING, Permission.READ))
                    .isInstanceOf(TypeMismatchException.class);
            assertThatThrownBy(() -> tx.defineSubPredicate(rel, rel))
                    .isInstanceOf(SchemaViolationException.class);
            assertThatThrownBy(() -> tx.defineInversePredicate(rel, doc))
                    .isInstanceOf(TypeMismatchException.class);
            assertThatThrownBy(() -> tx.disconnect(edgeId))
                    .isInstanceOf(ReferentialIntegrityException.class);

            var aclTargetEdge = tx.findIncomingEdges(edgeId, BootstrapIds.ACL_TARGET).getFirst();
            assertThatThrownBy(() -> tx.disconnect(aclTargetEdge.id()))
                    .isInstanceOf(SchemaViolationException.class);
        }
    }
}
