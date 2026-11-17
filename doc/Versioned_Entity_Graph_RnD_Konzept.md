# Versioned Entity Graph (VEG) — R&D-Konzept und Entwicklungsplan

**Status:** Forschungs- und Architekturkonzept (keine abgeschlossene Spezifikation)  
**Stand:** 17. November 2026  
**Adressat:** Autonomer Coding-/Research-Agent  
**Arbeitstitel:** *Versioned Entity Graph (VEG)* — vorläufig, kein festgelegter Produktname  
**Sprache der Implementierung:** bevorzugt Java 21+ (experimentelle Komponenten dürfen in anderen Sprachen implementiert werden, wenn gut begründet)  
**Ziel:** Ein einfach zu verwendendes, streng typisiertes, universelles und versioniertes Persistenzsystem erforschen, dessen logisches Modell unabhängig von der physischen Storage-Engine ist.

> **Arbeitsauftrag an den Coding-Agenten:** Prüfe die Hypothesen durch ausführbare, reproduzierbare Experimente. Baue nicht sofort eine produktionsreife Datenbank. Implementiere in kleinen, testbaren Stufen, dokumentiere Entscheidungen einschließlich verworfener Alternativen und halte die zentralen Schnittstellen erweiterbar. Führe Messungen durch, bevor du größere Architekturentscheidungen festschreibst. Korrektheit, nachvollziehbare Semantik und Reproduzierbarkeit haben Vorrang vor frühzeitiger Optimierung.

---

## 1. Motivation und Ausgangslage

Der Initiator arbeitet seit Langem mit Anwendungsentwicklung und relationalen Datenbanken (insbesondere Oracle). Im Unternehmensumfeld sind Datenbanken häufig organisatorisch zentralisiert, zertifiziert und nicht beliebig austauschbar. Entwickler erhalten nicht immer eigene Kopien des Datenbestands. Ein bereits auf Oracle implementiertes Metamodell mit Schema-, Daten- und Relationsverwaltung hat sich für flexible Teilbereiche wie Produktdaten bewährt. Allerdings wird das Modell bei umfangreicher, durchgängiger Versionierung in SQL-Strukturen durch Datenmengen, Indizes und Abfragekomplexität zunehmend schwer handhabbar.

Gesucht wird **kein bloßer ORM**, kein separates Audit-Log und keine zusätzliche Liquibase-Schicht, sondern ein Persistenzmodell, bei dem fachliche Objekte, Attribute, Beziehungen, Typdefinitionen, Berechtigungen und ihre Historie konsistent und möglichst einfach zu bearbeiten sind. Versionierung, Branches und Merges sollen systemeigene Mechanismen sein.

**Zwei Anwendungsfelder:**

1. **Entwicklung:** zentrale Datenbank mit release- und featurebezogenen Branches für **Schema und Daten**, selektiven Commits, Tags, Diffs und Drei-Wege-Merges. Datenbankzustände sollen ähnlich wie Quellcode entwickelt werden können; Schemaänderungen sollen ohne manuell gepflegte Liquibase-Migrationen möglich sein.
2. **Fachlicher Betrieb:** jede erfolgreich persistierte Änderung ist einem nachvollziehbaren, unveränderlichen Commit zugeordnet: *wer hat wann was und warum geändert?* Historische Zustände, Audit, begründetes Revert und zeitbezogene Abfragen gehören zum Kernmodell. Die umfangreiche Historie wird im Normalbetrieb überwiegend geschrieben und nur selten gelesen.

**Leitmotiv:** „Daten flexibel kneten können, ohne ständig über technische Schlüssel, Migrationen, Indizes und Speicherdetails nachdenken zu müssen.“

---

## 2. Priorisierung und Begriffe

- **P0 — Forschungsfundament / zwingend im ersten Kern:** Ohne diese Eigenschaften kann die Hauptidee nicht valide getestet werden. Kleine vollständige vertikale Schnitte sind wichtiger als ein breites API.
- **P1 — unverzichtbares Zielsystem:** Muss durch nachfolgende Stufen erreichbar sein; im MVP gegebenenfalls bewusst vereinfacht, aber bei P0-Entscheidungen nicht verbauen.
- **P2 — wichtige Erweiterung:** Nach validierter Kernsemantik und einem ersten Storage-Vergleich.
- **P3 — langfristige Forschungsoption:** Architektonisch berücksichtigen, jedoch nicht voraussetzen oder vorzeitig implementieren.

**Unterscheide konsequent:**

- **Identität** (stabile Entity-ID) ≠ **Wertgleichheit** (zwei Strings „Thomas“) ≠ **Version** (Zustand einer Entity bei einem Commit).
- **Relation** (konkrete, identifizierbare Verbindung) ≠ **Prädikat/Relationstyp** (definierte Bedeutung) ≠ **Aussage/Assertion** (eine bestimmte semantische Behauptung ggf. mit Herkunft und Gewicht).
- **Transaktion** (atomarer Konsistenzrahmen) ≠ **persistierter Commit** (unveränderlicher Zustandsübergang) ≠ **fachliche Aktion** (begründender Kontext, ggf. mehrere Transaktionen).
- **Rollback vor Commit** (keine sichtbare Historie unbestätigter Änderungen) ≠ **Revert nach Commit** (neuer, nachvollziehbarer Gegen-Commit).
- **Logisches Datenmodell** ≠ **physische Speicherrepräsentation**.
- **Systemzeit** (wann wurde gespeichert) ≠ **Gültigkeitszeit** (wann galt etwas fachlich).
- **Explizit gespeicherte** Aussagen ≠ **abgeleitete** Aussagen.

---

## 3. Priorisierte Anforderungen

### 3.1 Universelles, streng typisiertes Metamodell

| ID | Priorität | Anforderung | Prüfkriterium |
|---|---|---|---|
| M-01 | P0 | Jede persistente Sache besitzt eine stabile Entity-ID, unabhängig von Branch und Version. | Identität bleibt über Commits und zulässige Umbenennungen stabil. |
| M-02 | P0 | **Relationen sind selbst Entities**, können Quelle oder Ziel anderer Relationen sein und besitzen eigene Historie. | Eine Relation wird durch eine zweite Relation mit einem Dokument verknüpft. |
| M-03 | P0 | **Attributdefinitionen und konkrete Attributwerte sind logisch Entities**; ein Attributwert kann über typisierte Relationen mit einem oder mehreren anderen Objekten verbunden sein. | Zwei Personen können dieselbe Attribut-Entity referenzieren; ihre Zuordnungen sind separat änderbar. |
| M-04 | P0 | Strenge, deterministische Typprüfung für primitive Werte sowie Source/Target-Beschränkungen von Relationen. | String-Attribut lehnt Integer-Wert ab; ungültige Relation scheitert atomar. |
| M-05 | P0 | Typdefinitionen und Constraints werden im Graph repräsentiert und versioniert; nur ein minimaler Bootstrap-Kern ist hartcodiert. | Neue fachliche Typen ohne Codeänderung am Kern definieren. |
| M-06 | P1 | Klassen-/Typvererbung einschließlich geerbter Attribute und Constraints, deterministische Konfliktregeln; Mehrfachvererbung als gesonderte Entscheidung. | Instanz eines Untertyps erfüllt Basis-Constraints. |
| M-07 | P1 | Felder/Attribute einfach hinzufügen, ändern, umbenennen, entfernen; Änderungen sind eigene Commits. | Rename behält Identität; ältere Schemas bleiben historisch lesbar. |
| M-08 | P1 | Typisierte Attribute, Pflichtfelder, Kardinalität, Uniqueness, Validierungsregeln, optionale Defaults; Schemaänderungen gegen Bestandsdaten prüfen. | Ungültige Schemaänderung scheitert oder wird ausdrücklich migriert. |
| M-09 | P1 | 1:1, 1:n, n:m und Graphrelationen ohne manuelle PK-/FK-Verwaltung. | Modellierung ohne technische Join-Tabellen in der öffentlichen API. |
| M-10 | P1 | Semantisch benannte Prädikate mit definierter Bedeutung; eine Verbindung kann **mehrere Aussagen/Bedeutungen** besitzen. | `worksOn`, `contributesTo` unabhängig zuweisen/entfernen. |
| M-11 | P1 | Aussagen/Relationen haben optional Gewichtung, Konfidenz, Herkunft, Zeitbezug und eigene Berechtigungen. | Gewichtung und Quelle werden unabhängig versioniert. |
| M-12 | P2 | Inverse, transitive, Unterrelationen; RDF-/OWL-kompatible Sichten und abgeleitete Aussagen, ohne vollständige Reasoning-Engine vorauszusetzen. | RDF-Tripel exportieren; abgeleitete Aussagen als solche markieren. |
| M-13 | P2 | Benutzerdefinierte Werttypen, Berechnungs-/Abhängigkeitsrelationen und strikte Konvertierungsregeln. | Eigener `EmailAddress`-Typ mit Constraint. |
| M-14 | P3 | Allgemeine n-stellige Relationen, Ontologie-Reasoning oder komplexe Regelsprachen. | Separater Forschungsnachweis, kein MVP-Blocker. |

**Designprinzip:** Ein Wertobjekt muss nicht automatisch global intern dedupliziert werden. Zwei gleichwertige Entities können unterschiedliche Identitäten und Lebenszyklen besitzen. Gemeinsame Wertobjekte müssen explizit als geteilt oder unveränderlich definiert sein. Die Engine darf logische Entities kompakt repräsentieren; sie darf durch Optimierung nicht unbemerkt Alias- oder Änderungssemantik verändern.

### 3.2 Versionierung, Entwicklung und fachlicher Betrieb

| ID | Priorität | Anforderung | Prüfkriterium |
|---|---|---|---|
| V-01 | P0 | Jede **erfolgreich abgeschlossene Schreibtransaktion** erzeugt einen dauerhaft referenzierbaren Versionsübergang. | Nach Commit sind vorheriger und aktueller Zustand abfragbar. |
| V-02 | P0 | Commits sind unveränderlich; sie enthalten Parent(s), Änderungssatz, Branch-/Kontextreferenz und Metadaten. | Spätere Änderungen verändern alte Commits nicht. |
| V-03 | P0 | Atomare Transaktionen: kein partiell sichtbarer Zustand; auch Schema- und Relationsänderungen werden validiert. | Fehler vor Commit hinterlässt keine Datenänderung. |
| V-04 | P0 | Historische Abfrage `asOf(commit)` und `diff(commitA, commitB)` zunächst für Entities/Relationen/Attribute. | Exakte Rekonstruktion anhand reproduzierbarer Testfälle. |
| V-05 | P0 | Ein Branch referenziert einen konsistenten Graphzustand; neue Änderungen sind branchlokal. | Änderungen in Feature-Branch bleiben auf Main unsichtbar. |
| V-06 | P1 | Selektives Staging und Committen **einzelner Schema- und Datenänderungen** mit Abhängigkeitsprüfung. | Zwei unabhängige Änderungen lassen sich getrennt committen. |
| V-07 | P1 | Drei-Wege-Merge mit gemeinsamen Vorfahren für Daten und Schema; automatische konfliktfreie Merges, explizite Konflikte. | Unabhängige Attribute mergen; konkurrierende Umbenennung/Löschung meldet Konflikt. |
| V-08 | P1 | Release-Branches, Feature-Branches, globale Versionierungs-Tags und historische Sichten. | Release-Tag kann Schema und Datenzustand identifizieren. |
| V-09 | P1 | Audit: Akteur, Systemzeit, Operation, alter/neuer Zustand, optional vorgeschriebener fachlicher Grund und Vorgangsreferenz. | „Wer hat wann was und warum geändert?“ beantwortbar. |
| V-10 | P1 | Revert erzeugt **neuen** Commit; vorhandene Historie wird nicht gelöscht. | Revert bleibt selbst nachvollziehbar. |
| V-11 | P1 | Verschachtelte Transaktionen bzw. Savepoints mit Rollback auf mehreren Ebenen; echte unabhängig commitbare Untertransaktionen gesondert prüfen. | Rollback eines Savepoints lässt äußere Transaktion fortsetzen. |
| V-12 | P1 | Schema und Daten sind gemeinsam branchbar und historisch lesbar; keine verpflichtend handgeschriebenen Migrationen bei einfachen Schemaänderungen. | Typ ergänzen, Branch wechseln, Änderungen mergen. |
| V-13 | P1 | Trennung von **fachlichen Betriebsänderungen** und für Entwicklungsmerge vorgesehenen Seed-/Referenz-/Testdaten. | Feature-Merge übernimmt nicht versehentlich produktive Kundendaten. |
| V-14 | P2 | Bitemporale Semantik: Systemzeit und optionale fachliche Gültigkeitszeit. | „Damals bekannt“ und „damals fachlich gültig“ getrennt abfragen. |
| V-15 | P2 | Fachliche Aktion kann mehrere Transaktionen referenzieren; zusammenhängende Korrektur/Komensation mit eindeutiger Auditspur. | Mehrstufiger Vorgang hat ein fachliches Korrelationsobjekt. |
| V-16 | P2 | Selective cherry-pick, Review/Diff von vorbereiteten Änderungen und Merge-Strategien. | Einen fachlichen Commit gezielt in einen anderen Branch übernehmen. |
| V-17 | P3 | Verteilte Offline-Replikation, verteiltes Multi-Master-Merging. | Eigenständiges Forschungsprojekt. |

**Normative Entscheidung für den PoC:** Ein atomarer Transaktions-Commit und ein Versionierungs-Commit werden zunächst als **ein Ereignis** betrachtet. Git-artiges selektives Staging findet **vor** dem erfolgreichen Commit statt. Später kann ein übergeordneter Entwicklungs-Commit mehrere unveränderliche Transaktions-Commits gruppieren. Dadurch bleibt die operative Audit-Historie lückenlos, auch wenn Entwickler selektiv fachliche Änderungen bündeln.

### 3.3 Sicherheit, Rechte und Abfragen

| ID | Priorität | Anforderung | Prüfkriterium |
|---|---|---|---|
| S-01 | P1 | Gruppen-/Rollenrechte auf Entity-, Relations- und **Attributebene**. | Zugriff auf Person erlaubt, auf sensibles Feld verweigert. |
| S-02 | P1 | Deklarative Rechtevererbung entlang explizit berechtigungsrelevanter Relationen. | Projektgruppe erhält Dokument-Leserecht, nicht Löschrecht. |
| S-03 | P1 | Deterministische Semantik bei mehreren Pfaden, Zyklen, expliziten Verboten und Vererbungsgrenzen; Default Deny. | Tests mit widersprüchlichen Pfaden und Zyklen. |
| S-04 | P1 | SQL-ähnliche Abfragen für typisierte Attribute, Filter, Projektion, Join/Traversal und Sortierung. | Typisierte Standardabfragen ohne Kenntnis physischer Speicherstruktur. |
| S-05 | P1 | Historische Abfragen und Branches beachten definierte Berechtigungs- und Auditrichtlinien; keine Rechteausweitung durch Zeitreise. | Historische Feldwerte bleiben unautorisiert verborgen. |
| S-06 | P2 | RDF-kompatible Abfragen/Sichten, ggf. SPARQL-Adapter. | Tripel-basierte Abfrage eines exportierten Teilgraphen. |
| S-07 | P2 | Provenienz und Vertrauen bei Aussagen: `weight` (Stärke) ≠ `confidence` (Sicherheit). | Mehrere Quellen/Aussagen getrennt prüfen. |
| S-08 | P2 | Zuverlässige Systemidentität, Autorisierung von Service-/Benutzeraktionen, revisionssichere Auditmetadaten. | Benutzer kann Audit-Akteur nicht frei fälschen. |
| S-09 | P2 | Aufbewahrung, Datenschutz, Lösch-/Sperrkonzepte trotz unveränderlicher Historie. | Dokumentierte Lösung für personenbezogene Daten. |

### 3.4 Betrieb und Performance

| ID | Priorität | Anforderung | Prüfkriterium |
|---|---|---|---|
| P-01 | P0 | Logische API von physischer Engine entkoppeln. | Gleiche Tests gegen zwei Backends ausführbar. |
| P-02 | P0 | Messbare Baseline mit naiver In-Memory-Repräsentation und reproduzierbarem Daten-Generator. | Benchmarks laufen automatisiert und liefern CSV/JSON. |
| P-03 | P1 | Historie effizient, unveränderlich und überwiegend append-orientiert speichern; aktive Daten schnell lesbar halten. | Wachstum und Lesezeiten bei steigender Historie messen. |
| P-04 | P1 | Strukturelle Teilung zwischen Versionen/Branches; nicht für jeden Commit komplette Kopien. | Branch mit null Änderungen verursacht kaum Datenkopien. |
| P-05 | P1 | Indizes und Materialisierungen nach Möglichkeit automatisch pflegen; manuelle Indexplanung ist nicht Teil der Fach-API. | Standardabfragen ohne manuell definierte Indizes. |
| P-06 | P1 | Konsistenter Neustart/Wiederherstellung nach Fehler; keine halben Commits. | Crash-/Recovery-Tests bestehen. |
| P-07 | P2 | Snapshots, Kompression, Kaltarchiv für selten gelesene Historie; adaptive historische Indizes. | Historische Abfragen funktionieren nach Archivierung. |
| P-08 | P2 | Persistentes Storage-Backend mit nachvollziehbarem Commit-Protokoll und Prüfwerten. | Neustart rekonstruiert identischen Graphstand. |
| P-09 | P2 | Oracle-kompatibler Integrations-/Adoptionspfad (z. B. bestehendes Metamodell, transaktionale Outbox/CDC), ohne Oracle als zwingenden Kern festzulegen. | Beispiel-Synchronisation mit überprüfbarem Fortschrittsmarker. |
| P-10 | P3 | Automatischer Query Optimizer, verteilte Speicherung, HA/Replikation, Online-Migration großer Bestände. | Erst nach belegtem Kernkonzept. |

---

## 4. Architekturhypothesen (noch keine Festlegungen)

### H1 — Minimaler Kern

Möglichst kleine Menge von Systemprimitiven:

```text
EntityId        stabile Identität
Value           streng typisierter primitiver Wert (String, Integer, Decimal, Bool, Binary, ...)
Edge            (edgeId, sourceId, predicateId, targetId)
Change          atomare Operation auf Entity/Value/Edge/Typdefinition
Commit          unveränderlicher Übergang eines konsistenten Graphzustands
```

Jede `Edge` besitzt eine `EntityId` und kann daher selbst Source/Target anderer Edges sein. Typen, Attributdefinitionen, Constraints und Berechtigungen werden möglichst als gewöhnliche Entities und Edges modelliert. Ein **minimaler, stabiler Bootstrap-Kern** ist legitim: Entity-Identitäten, Basiswerttypen, Referenzregeln, Commit-Protokoll und Definitionen der grundlegenden Prädikate. Vollständige Selbstbeschreibung darf nicht in endloser Metatyp-Rekursion enden.

**Offene Frage:** Können Commit-Metadaten ebenfalls als Entities vorliegen, ohne zirkuläre Abhängigkeiten beim atomaren Schreiben zu erzeugen? Denkbar: Commit-Metadaten logisch im Graph, physisches Commit-Envelope systemintern. Testen statt dogmatisch erzwingen.

### H2 — Attribute als eigenständige Entitäten

```text
Entity #1       type = Person
Entity #42      type = FirstNameValue, payload = String("Thomas")
Entity #100     relation from #1 via hasFirstName to #42
Entity #200     relation from another Person via hasFirstName to #42
```

Die zwei Personen **teilen dasselbe Wertobjekt**. Eine Änderung am Wertobjekt wirkt logisch auf beide; eine Änderung der Zuordnung betrifft nur die entsprechende Relation. Strikte Typprüfung gilt trotz universellem Graphmodell.

Physisch kann `#42` ein internierter unveränderlicher Wert oder ein eigener Datensatz sein; `#100` kann kompakt repräsentiert werden. Eine gemeinsame, **veränderbare** Attribut-Entity darf nur mit ausdrücklich definierter Shared-Mutation-Semantik zugelassen werden.

### H3 — Verbindungen und semantische Aussagen trennen

Eine konkrete Verbindung kann mehrere Bedeutungen/Assertions besitzen, die einzeln gewichtet und versioniert sind:

```text
Connection #4711: A -> B
Assertion #1: predicate = worksOn       weight=1.0 confidence=0.95
Assertion #2: predicate = contributesTo weight=0.8 confidence=0.90
Provenance:    sourceDocument -> Entity #900
```

**Gegenhypothese:** Jede Aussage selbst ist bereits eine Relation-Entity und eine separate `Connection`-Entity ist unnötig. Beide Modelle auf Ausdrucksstärke, Duplikate, Merge-Verhalten und Kosten vergleichen. Semantische Regeln (`inverse`, `transitive`, `domain`, `range`, `subPropertyOf`) werden versioniert, nicht als festcodierte Graphalgorithmen vorausgesetzt. RDF/RDF-star- bzw. RDF-1.2-kompatibler Export ist eine Ziel-Sicht, **keine** Pflicht für die interne Repräsentation.

### H4 — Append-only Historie mit materialisierter Gegenwart

- **Logische Wahrheit:** unveränderliches Änderungsjournal/Commit-DAG.
- **Operative Sicht:** kompakter aktueller Objekt-/Relationsbestand, materialisierte Indizes und Cache.
- **Historische Sicht:** Snapshots, Deltas und optional asynchron archivierte Segmente.
- **Branches:** Commit-Referenzen mit struktureller Teilung statt vollständiger Kopien.

**Unbedingt prüfen:** Ein reines Event-Sourcing-System kann bei hoher Schreiblast und komplexen Abfragen zusätzliche Komplexität erzeugen. Vergleiche daher das Journal-als-Wahrheit-Modell mit einem atomar gemeinsam verwalteten Current-State-plus-History-Modell. Beide müssen identische Semantik liefern.

### H5 — Versionierter Graph mit strukturellem Merge

Drei-Wege-Merge anhand gemeinsamer Vorfahren. Änderungen werden anhand **stabiler Entity-/Prädikat-IDs** verglichen, nicht nur anhand von Namen. Es gibt getrennte Konfliktklassen für:

- gleiches skalares Attribut in beiden Branches verändert;
- Relation auf einer Seite gelöscht, auf anderer Seite geändert;
- Typ/Constraint verschärft und gleichzeitig inkompatible Daten hinzugefügt;
- Typ/Attribut umbenannt, gelöscht oder Kardinalität geändert;
- Berechtigungsregeln konfligieren;
- gemeinsam genutztes Wertobjekt verändert;
- voneinander unabhängige Relationen/Attribute geändert.

**Keine stillschweigenden Last-Writer-Wins-Merges** für schema- oder sicherheitskritische Änderungen. Erst Konflikte markieren, dann explizite Auflösung als neuer Commit.

### H6 — Der reine Graph ist logische Semantik, nicht zwingend physische Form

Vergleiche drei Backends:

- **A / Referenz:** naive Maps aus Entity-, Edge- und Value-Objekten (leicht verständlich).
- **B / Kompakt:** primitive Arrays/Records, typisierte Values, gepackte Edges, ggf. interning unveränderlicher Werte.
- **C / Persistent:** unveränderliche, strukturell geteilte Index-/Baumstruktur (z. B. Persistent B-Tree, HAMT, Merkle-/Prolly-Tree); gegebenenfalls mmap/Datei später.

**Invariante:** gleiche IDs, Ergebnisse, Berechtigungen, Diff- und Merge-Semantik über alle Engines. Der Storage darf nur die Repräsentation ändern.

### H7 — Hybrid-Adoption ohne Austausch von Oracle

Ein optionaler Adapter könnte das vorhandene fachliche Oracle-Metamodell weiterverwenden:

```text
Anwendung/Objekt-API
     -> Oracle (operativer, transaktionaler Zustand + Commit-Kontext/Outbox)
     -> CDC bzw. verlässlicher Stream
     -> externer Versionsspeicher / historische Projektionen
```

Fachliches `actor`, `reason`, `correlationId` müssen in derselben Transaktion gesichert sein, nicht aus Redo-Logs geraten werden. Lückenlose Zustellung, Ordnung, Neustart, Backpressure und vollständig abgeglichener Fortschrittsmarker sind erforderlich. Ein asynchroner Historienexport kann nicht dieselbe unmittelbare Garantie bieten wie ein gemeinsamer atomarer Storage, solange die Bedingungen nicht ausdrücklich spezifiziert sind.

---

## 5. Ausführbare Semantik: erste öffentliche API (nur Vorschlag)

Die API ist eine **Testsprache**, keine vorgegebene Klassenstruktur:

```java
try (var tx = db.beginTransaction(
        Actor.user("u123"),
        Reason.of("Kundenstammdaten korrigiert"),
        "CRM-1842")) {

    var personType = tx.schema().getType("Person");
    var person = tx.create(personType);
    var firstName = tx.createValue("FirstName", "Thomas");
    tx.connect(person, "hasFirstName", firstName);

    CommitId commit = tx.commit();
}

var previous = db.snapshot(commitId);
var history  = db.history(personId);
var diff     = db.diff(commitA, commitB);

var feature = db.branch("feature/customer", db.branch("main").head());
var result  = db.merge("feature/customer", "main");
```

**Besondere Regeln:**

- Jede Objekt- und Edge-ID bleibt stabil; Snapshotobjekte dürfen nicht implizit mutieren.
- Graphänderungen werden erst nach vollständiger Typ-, Constraint- und Rechteprüfung sichtbar.
- Die gesamte Commit-Reihenfolge muss deterministisch sein (für gleiche Eingaben und definierte Reihenfolge).
- `Reason` kann abhängig vom Schema verpflichtend sein; der fachliche Inhalt einer Begründung kann technisch nicht automatisch verifiziert werden.
- Merge-/Staging-APIs dürfen Teiländerungen nicht ohne ihre erforderlichen Schema-/Relationsabhängigkeiten committen.
- Systemzeit- und Branch-Historie sind eindeutig; übergreifende absolute Reihenfolge verteilter Commits ist *nicht* ohne gesonderte Regeln garantiert.

---

## 6. Schrittweiser Umsetzungs- und Forschungsplan

**Arbeitsweise pro Stufe:** (1) Hypothese schriftlich festhalten, (2) minimale Implementierung, (3) automatisierte Korrektheitstests, (4) Benchmark/Ergebnisdaten soweit sinnvoll, (5) Entscheidung/Trade-offs dokumentieren, (6) erst dann nächste Stufe. Keine vorzeitige Festlegung auf DBMS, Dateiformat oder Distributed Systems.

### Stufe 0 — Repo, Forschungsdisziplin und Test-Oracle

**Ziel:** Reproduzierbares Fundament ohne Architekturballast.

- Java 21+, Maven oder Gradle; Unit- und Property-based-Tests; kleine CLI.
- Modulgrenzen `model`, `engine-api`, `engine-memory`, `test-fixtures`, `benchmarks`, `docs` (Namen anpassbar).
- Zentrales Glossar und explizite Invarianten.
- Deterministischer Zufallsdatengenerator mit festem Seed; Benchmarks mit JVM-Version, Warm-up, Heap, GC und Hardware protokollieren.
- Forschungslog mit Hypothese, Ergebnis, Einschränkung, Entscheidung und nächstem Schritt.

**Exit:** Build/test auf frischem Checkout reproduzierbar; dokumentierter minimaler Benchmark.

### Stufe 1 — Minimalgraph und Bootstrap

**Ziel:** Entity-IDs, primitive Values und identifizierbare Edges; Typen als Daten.

- Implementiere `EntityId`, primitive `Value`, `Edge(id, source, predicate, target)`.
- Minimalen Bootstrap für Typdefinitionen, Prädikate und Constraints schaffen; Zirkularitätsprobleme dokumentieren.
- `Person`, `FirstName`, `hasFirstName` vollständig über Graphobjekte definieren.
- Implementiere typisierte Source/Target- und Value-Checks.
- Test: Relation ist selbst Ziel einer Relation; Attribute und Definitionen sind Entities.

**Exit:** Alle folgenden Beispiele funktionieren ohne spezialisierten Anwendungscode: Typ anlegen, Person anlegen, Wertobjekt anlegen, Relation anlegen, Relation annotieren; ungültige Typzuweisung wird abgelehnt.

### Stufe 2 — Identität, Sharing und semantische Aussagen

**Ziel:** Die heikelsten Modellsemantiken vor Persistenz klären.

- Zwei Personen teilen ein `FirstName`-Wertobjekt; Alias-/Mutationsregeln testen.
- Vergleich `Connection + Assertions` vs. `jede Assertion = eigene Edge-Entity`.
- Unterschied Wertgleichheit/Identität, Gewicht/Konfidenz, Provenienz explizit testen.
- Kardinalität und Uniqueness für typisierte Relationen.
- Testrelationen zu Relationen, Quellen und Aussagen.

**Exit:** Eindeutige dokumentierte Regeln für Shared Mutation, referenzielle Integrität und Aussageidentität; kein semantischer Widerspruch im Modell.

### Stufe 3 — Atomare Commits, Historie und Snapshots

**Ziel:** Append-only Änderungen plus historische Zustände.

- Transaktions-Workspace, Validierung, atomare Veröffentlichung.
- Unveränderliche Commit-Objekte mit Parent, Actor, Reason, Zeitpunkt, Änderungen.
- Operationen `asOf`, `history`, `diff`, `revert` (Revert als neuer Commit).
- Erst eine lineare Commit-Kette; Branch-DAG später.
- Rollback und Savepoints innerhalb einer noch offenen Transaktion.
- Property-Tests: Replay == aktueller Zustand; Commit ist unveränderlich; Rollback hinterlässt keine sichtbaren Spuren.

**Exit:** Graph nach 1000 deterministischen Änderungen exakt rekonstruierbar; Revert und historische Typprüfung funktionieren.

### Stufe 4 — Branches, selektives Staging und Merge

**Ziel:** Git-artiger Entwicklungsworkflow ohne Vollkopien.

- Branch-Head und Tags auf Commit-DAG.
- Separater Workspace mit staged/unstaged Änderungen und Abhängigkeitsprüfung.
- Drei-Wege-Diff/Merge zuerst für unabhängige Datenänderungen, dann Schema-/Relationsänderungen.
- Konfliktobjekte mit maschinenlesbarem Grund; explizite Resolution.
- Datenklassen `test`, `reference`, `operational` oder äquivalente Merge-Policies erforschen.

**Exit:** Zwei Feature-Branches mergen unabhängige Schema- und Datenänderungen korrekt; konkurrierende Änderungen werden reproduzierbar erkannt; kein ungewollter Merge operativer Daten.

### Stufe 5 — Berechtigungen und semantische Regeln

**Ziel:** Rechte werden systemweit und versionsbewusst durchgesetzt.

- Gruppen, Rollen, ACL-/Policy-Entities; Feld- und Relationsrechte.
- Explizite Vererbung über freigegebene Relationstypen; Default Deny.
- Regel für Deny/Allow, Zyklen, konkurrierende Pfade; Testfälle gegen ungewollte Privilegieneskalation.
- Historische Leserechte und Branchzugang als eigene Policyentscheidungen dokumentieren.
- Minimale Regeln für inverses Prädikat/Unterprädikat, abgeleitete Aussagen klar von expliziten trennen.

**Exit:** In Tests kein Zugriff über nicht autorisierte Pfade; Auswertung deterministisch und erklärbar („warum erlaubt/verweigert?“).

### Stufe 6 — Abfrageschicht und Graph-Sichten

**Ziel:** Praktisch nutzbares, storageunabhängiges Query-Modell.

- Zunächst typisierte API: Filter, Projektion, Traversal, Join, Sortierung, Paging, `asOf`.
- Später kleine SQL-ähnliche DSL; kein vollständiger SQL-Parser im MVP.
- RDF-kompatibler Export für explizite Aussagen (mit dokumentierter Reifikation/Metadatenabbildung).
- Query-Ergebnisse stets mit definiertem Snapshot/Branch konsistent.

**Exit:** Repräsentative Produktdaten- und Knowledge-Graph-Abfragen bestehen; Ergebnisse unabhängig vom Backend identisch.

### Stufe 7 — Storage-Experimente und Benchmarks

**Ziel:** Prüfen, ob die semantische Eleganz zu vertretbaren Kosten implementierbar ist.

- Baseline A: naive Objekt-/Map-Engine.
- Engine B: kompakte Werte-/Edge-Repräsentation; nur zulässiges interning.
- Engine C: persistente/strukturell geteilte Datenstruktur für Branch-/Snapshot-Effizienz.
- Vergleiche Journal-als-Wahrheit mit Current-State-plus-History bei gleicher Semantik.
- Messung über 10k, 100k, 1M fachliche Objekte, jeweils z. B. 20 Attribute und variable Relationsdichte; separat 10k, 100k, 1M Commits, soweit Ressourcen reichen.
- Primärmetriken: Bytes/Entity, Bytes/Edge, Bytes/Commit, p50/p95/p99 Read/Write, Commit-Latenz, Branch-Kosten, Diff/Merge-Dauer, Snapshot-Rekonstruktion, Speicher nach GC, Warm-/Cold-Read.
- Messungen zusätzlich mit hoher Änderungsrate und **wenigen historischen Reads** (operative Realität), sowie mit vielen historischen Reads (Worst Case).

**Exit:** Vergleichsbericht mit Rohdaten, Graphen, Profilen und expliziter Go-/No-Go-Entscheidung. Keine pauschale Skalierungsbehauptung ohne Messdaten.

### Stufe 8 — Persistenter Storage und Recovery

**Ziel:** Semantik und Performance auch nach Neustart erhalten.

- Dauerhaftes Log und/oder atomare Root-/Commit-Pointer; Prüfsummen, fsync-/Durability-Politik.
- Rebuild materialisierter Indizes/Projektionen; Crash-Injection an kritischen Stellen.
- Snapshots, Kompression, archivierte Segmente und Restore.
- Wartung/Compaction darf alte referenzierte Commitzustände nicht beschädigen.

**Exit:** Wiederholbare Crash-/Recovery-Tests, vollständige Konsistenz der referenzierten Historie und dokumentierte Durability-Garantien.

### Stufe 9 — Oracle-Adapter als eigenständiger Forschungsstrang

**Ziel:** Einsatz ohne Ersatz einer unternehmensweit vorgegebenen Oracle-DB ermöglichen.

- Prüfe, wie vorhandenes Schema-/Daten-/Relationsmetamodell abgebildet werden kann.
- Prototyp: transaktionaler Audit-/Outbox-Kontext und externes Versionsjournal; optional CDC.
- Teste Reihenfolge, Duplikate, Recovery, Lückenfreiheit, Actor/Reason-Korrelation, Reconciliation und Audit-Watermark.
- Vergleiche Betriebsaufwand und Konsistenzversprechen mit nativer Engine.

**Exit:** Dokumentierte Grenzen: Welche Funktionen sind mit Oracle + Adapter tatsächlich erreichbar, welche nur mit nativer Versionierungs-Engine?

### Stufe 10 — Reifung zu einer nutzbaren Datenbank (erst nach positivem Befund)

- Schema-Migrationen und Kompatibilität auf großen Beständen.
- Mehrbenutzer-Konkurrenz: Isolation, MVCC/optimistische Konflikte, Deadlockfreiheit.
- Erweiterte Berechtigungen, Security Review, Audit-Rechtssicherheit und Datenschutz.
- Automatische Indizes, Query-Optimierung, bitemporale Abfragen, SPARQL-Adapter.
- Betrieb: Monitoring, Backups, Recovery, Upgrade-Prozesse, Benchmarks gegen existierende Systeme.
- Spätere Forschung: HA, Replikation, verteilte Speicherung, Clusterbetrieb.

---

## 7. Korrektheits- und Grenztests (Pflichtszenarien)

| Test | Erwartung |
|---|---|
| T01: `Person --hasFirstName--> String("Thomas")` | Strenge Typisierung erlaubt gültige Relation. |
| T02: Gleiche Relation auf `Integer(42)` | Atomar abgelehnt. |
| T03: Zwei Personen verweisen auf dieselbe Attribut-Entity | Sharing explizit, Änderungen an Relation isoliert. |
| T04: Relation referenziert Dokument und trägt `confidence` | Relation-zu-Relation ohne Sonderdatensatz. |
| T05: Typdefinition wird im Graph verändert | Neuer Commit, alte Version weiterhin lesbar. |
| T06: Typ-Constraint gegen vorhandene Instanzen | Inkompatible Migration wird erkannt. |
| T07: Fehler zwischen zwei Änderungen | Kein partieller Commit. |
| T08: Teilrollback mit Savepoint | Nur innerer Workspace zurückgesetzt. |
| T09: Revert eines produktiven Commits | Gegen-Commit; ursprünglicher Audit-Eintrag bleibt. |
| T10: Unabhängige Feature-Branches | Korrektes automatisches Merge. |
| T11: Konflikt an gemeinsamem Wertobjekt | Konflikt explizit, keine stille globale Mutation. |
| T12: Schemaänderung vs. neue Daten | Semantischer Merge-Konflikt erkannt. |
| T13: Berechtigungen über Relation mit Zyklus | Terminierend, deterministisch, Default Deny. |
| T14: Historische Feldabfrage ohne Leserecht | Keine Datenexfiltration über Zeitreise. |
| T15: Snapshot-Replay von Commit 0 bis N | Identischer Graph und identische Constraints. |
| T16: Branch ohne Änderungen bei großem Bestand | Kein vollständiges Kopieren von Objekten. |
| T17: Crash während Commit | Entweder alter oder neuer konsistenter Zustand. |
| T18: RDF-Export mit gewichteter, belegter Aussage | Semantik und Provenienz dokumentiert, kein Datenverlust ohne Warnung. |

**Eigenschaftsbasierte Tests:** Graphinvarianten, Replay-Invarianz, Merge-Kommutativität *nur für als unabhängig definierte Änderungen*, Snapshot-Immutabilität, Referenzintegrität, deterministisches Constraint-Verhalten, Idempotenz von Retry-Operationen mit deduplizierender Transaktions-ID.

---

## 8. Messdesign und Skalierungshypothesen

**Hypothesen, nicht Versprechen:**

1. Naives „ein Heap-Objekt pro Attribut und Relation“ verursacht bei Millionen von Entities hohen Speicher-/GC-Aufwand.
2. Kompakte Storage-Repräsentation kann logische Objektidentitäten beibehalten, ohne für jedes Attribut eine umfangreiche physische Objektstruktur zu benötigen.
3. Strukturelle Teilung reduziert Branch-/Snapshot-Kosten gegenüber vollständigen Kopien deutlich.
4. Append-orientierte Historie und getrennte materialisierte Current-State-Projektionen verbessern die operative Performance bei seltenen historischen Reads.
5. Semantische Relationen, Rechtevererbung und historische Traversal-Abfragen können hohe Graphkosten erzeugen; spezifische Materialisierungen/Indizes sind trotzdem erforderlich.

**Benchmark-Workloads:**

- **W1:** 90 % aktuelles Lesen, 9 % Schreiben, 1 % historische Abfragen.
- **W2:** Schreibintensiver Audit-Workload mit kleinen Transaktionen.
- **W3:** Graph-Traversal 1/3/5 Hops bei hoher und niedriger Gradverteilung.
- **W4:** 1000 parallele Feature-Branches mit wenigen Änderungen.
- **W5:** Merge unabhängiger bzw. konfligierender Attribute, Relationen und Schemas.
- **W6:** Viele kleine Commits und wenige große Commits, identischer Gesamtänderungsumfang.
- **W7:** Zugriff auf historische Daten nach Snapshot/Archivierung.

**Faire Vergleiche:** gleiche logische Daten, gleiche Semantik, Warm-up, mehrfache Läufe, relevante JVM-/CPU-/Heap-Infos, Konfidenzbereiche; keine unbegründete Gleichsetzung von Heap-Größe und persistentem Plattenspeicher.

**Mindestbericht pro Run:** Seed, Datensatzgrößen, Relationsdichte, Commitanzahl, Branchanzahl, Engine, JVM, Hardware, p50/p95/p99, Durchsatz, verwendeter Speicher, bekannte Einschränkungen.

---

## 9. Entscheidungsstellen / Architektur-Forks

Keine dieser Fragen ungeprüft in der ersten Implementierung festschreiben:

1. **Connection + Assertions** vs. jede semantische Aussage als eigene Relation-Entity.
2. **Geteilte Werte:** unveränderliche internierte Values vs. veränderbare identifizierte Attributobjekte vs. explizite Semantik für beide.
3. **Typisierung:** Single Inheritance vs. Multiple Inheritance und entsprechende Konfliktauflösung.
4. **Versionierungskern:** Log als Source of Truth vs. Current State + atomar gekoppelte History.
5. **Commit-Granularität:** eins pro erfolgreicher Transaktion; wie Entwicklungsgruppen-Commits darauf aufbauen.
6. **Partial Staging:** Änderungsabhängigkeiten und atomare Konsistenz bei Schema-/Datenänderungen.
7. **Merge:** strukturell, semantisch oder kombiniert; sichere Behandlung von Konflikten.
8. **Berechtigungssemantik:** Deny/Allow-Reihenfolge, historische Sicht, relationale Propagation, Policy-Zyklen.
9. **Historien-Speicherung:** Delta-only, Snapshotperioden, Branchsharing, Compaction, Indexierung.
10. **RDF-Kompatibilität:** Exportmodell, Metadaten zu Aussagen, explizite vs. abgeleitete Tripel.
11. **Oracle-Integration:** nur Read/CDC, bi-direktional oder vollständig eigener Kern; Konsistenzgarantien.

Für jede Entscheidung eine kurze ADR (*Architecture Decision Record*) mit Alternativen, Test, Ergebnis, Nebenwirkungen und Revisionskriterien anlegen.

---

## 10. Bewusst nicht im ersten MVP

Kein vollständiger SQL-/SPARQL-Parser, kein vollständiger OWL-Reasoner, keine verteilten Transaktionen, keine replizierte Cluster-DB, keine automatische semantische Migration beliebiger Schemas, keine sofortige Zertifizierungs-/Compliance-Eignung, kein garantiert optimaler automatischer Indexsatz, keine vollständige Oracle-Kompatibilität, keine irreversiblen Produktiv-Datenlöschkonzepte ohne Security-/Legal-Review. Diese Punkte werden als künftige Anforderungen dokumentiert, **nicht ignoriert**.

---

## 11. Referenzsysteme zur Untersuchung (nicht ungeprüft übernehmen)

| Referenz | Forschungsinteresse |
|---|---|
| **Dolt** | Daten-/Schema-Branches, Commits, Drei-Wege-Merge, strukturell geteilte persistente Datenstrukturen. |
| **TerminusDB** | Versionierter Dokument-/Knowledge-Graph, Commits und Branches, semantische Modellierung. |
| **Datomic** | Immutable Facts, Transaktionen als abfragbare Daten, Audit und Historie. |
| **Gel (ehem. EdgeDB)** | Objektorientiertes, typisiertes Schema, Vererbung, Relationsergonomie und Queries. |
| **SurrealDB** | Flexible Graph-/Dokumentenmodellierung und Zugriffssteuerung bis auf Daten-/Feldebene. |
| **XTDB** | Bitemporale Historie und schemaflexible Sicht. |
| **RDF/RDFS/OWL** | Standardisierte Aussagen, Ontologien, Inferenz, Provenienz-/Reifikationskonzepte. |
| **Oracle / Flashback / CDC** | Integrationspfad in regulierte Unternehmenslandschaften ohne DB-Austausch. |

**Anweisung:** Vor Übernahme eines konkreten Produktmechanismus die aktuelle Originaldokumentation und tatsächliche Implementierung prüfen. Produktfeatures und Semantik ändern sich; diese Tabelle ist eine Forschungsliste, keine zertifizierte Featurematrix.

---

## 12. Erwartete Artefakte pro Entwicklungsstufe

Der Agent führt diese Projektstruktur fort (sinngemäß, nicht dogmatisch):

```text
README.md
pom.xml                         # oder begründetes Build-Äquivalent
model/
engine-api/
engine-memory/
engine-persistent/              # erst später
query/
benchmarks/
examples/
docs/
  CONCEPT.md                    # dieses Konzept in Projektverwaltung übernehmen
  GLOSSARY.md
  INVARIANTS.md
  RESEARCH_LOG.md
  ARCHITECTURE.md
  adr/0001-....md
  experiments/<experiment>/README.md
  experiments/<experiment>/results.csv
  experiments/<experiment>/analysis.md
```

**Nach jeder Stufe berichten:**

```text
Stufe / Hypothese:
Implementiert:
Tests (bestanden/fehlgeschlagen):
Messungen und Reproduzierbarkeit:
Was widerlegt / bestätigt wurde:
Risiken und technische Schulden:
Entscheidung (weiter / anpassen / verwerfen):
Nächster kleinster sinnvoller Versuch:
```

**Agentenregeln:**

- Keine „alles funktioniert“-Behauptung ohne Tests und Reproduktionsanweisung.
- Keine große Refaktorierung ohne vorherige Invarianten-/Regressionstests.
- Keine Datenbankwahl nur nach Bequemlichkeit oder Tool-Hype.
- Kein stillschweigendes Ersetzen einer strengen Semantik durch eine lockere Typprüfung.
- Keine Optimierung, die Identität, Sharing, Historie oder Sicherheit verändert.
- Keine „perfekte“ universelle Typ-/Ontologiehierarchie entwerfen, bevor reale Beispiele validiert wurden.
- Jede abgeschlossene Stufe enthält ausführbaren Code, klare Grenzen und eine begründete nächste Forschungsfrage.
- Bei unklaren Fragen zunächst den kleinsten differenzierenden Test bauen; nicht die komplexeste Option implementieren.

---

## 13. Startauftrag an den Coding-Agenten

**Beginne jetzt mit Stufe 0 und Stufe 1, nicht mit der gesamten Datenbank.**

1. Lege ein kleines Java-21-Projekt mit Tests und reproduzierbarem Build an.
2. Implementiere die primitiven `EntityId`, `Value`, `Edge`, `Type`-Repräsentationen und einen minimalen In-Memory-Graph.
3. Erzeuge **alle fachlichen Typen und Relationen als Daten im Graph**: `Person`, `FirstName`, `hasFirstName`, `SourceDocument`, `supportedBy`.
4. Zeige folgende Fälle als automatisierte Tests: `Person -> hasFirstName -> "Thomas"`; ungültiger `Integer`-Wert wird abgelehnt; zwei Personen können dasselbe Wertobjekt referenzieren; eine Relation kann per `supportedBy` auf ein Dokument verweisen.
5. Prüfe, wie klein der Bootstrap-Kern tatsächlich sein kann. Dokumentiere alle nicht selbstbeschreibbaren Ausnahmen.
6. Implementiere **noch keine** aufwendige Persistenz, Git-Merge-Engine, vollständige Query-Sprache oder Rechteverwaltung.
7. Erstelle einen kurzen Architekturbericht und die erste ADR mit zwei Optionen für die Behandlung von semantischen Aussagen auf Relationen.
8. Stelle fest, welche Annahmen von Stufe 1 für Historie/Branching in Stufe 3–4 gefährlich sein könnten, und halte die betroffenen APIs bewusst austauschbar.

**Erfolgsdefinition des ersten Forschungslaufs:** Ein minimales, typisiertes, selbstbeschreibbares Graphmodell mit Beziehungen-als-Entities und Attribute-als-Entities funktioniert und ist sauber getestet. Seine Beschränkungen sind ehrlich und konkret dokumentiert. Auf dieser Grundlage kann der nächste Commit-/Historien-Versuch beginnen, ohne dass bereits der gesamte Storage festgelegt wurde.

---

## 14. Langfristiges Zielbild

Die Oberfläche soll sich für Entwickler wie eine einfache, objektorientierte, SQL-ähnlich abfragbare Datenbank anfühlen. Sie bietet ohne technische FK-/PK-Pflege flexible streng typisierte Datenstrukturen, Beziehungen mit definierter Semantik, bis zu Feldern reichende Rechte und automatische Indizierung, soweit sinnvoll. Unterhalb der API hält sie eine nachvollziehbare, versionsfähige Welt aus Entities, Assertions, Typen und Commits bereit: historisch abfragbar, branchbar, mergebar und revertierbar. Der physische Speicher ist eine optimierte Implementierungsentscheidung und nicht Teil der Fachsemantik.

**Oberste Forschungsfrage:**

> Können wir einen vollständig versionierten, streng typisierten und möglichst selbstbeschreibenden Entity-/Relationsgraphen physisch so kompakt und effizient speichern, dass die Flexibilität des Modells nicht durch einen unverhältnismäßigen Aufwand für Speicher, Abfragen, Historie, Berechtigungen und Branch-Merges erkauft wird?

**Kernstrategie:** Semantik zuerst beweisen → Änderungshistorie und Branches korrekt machen → alternative Speicherstrategien messen → Persistenz und Recovery absichern → Integrationspfad in Unternehmensdatenbanken erforschen → erst dann produktionsnahe Anforderungen ausbauen.
