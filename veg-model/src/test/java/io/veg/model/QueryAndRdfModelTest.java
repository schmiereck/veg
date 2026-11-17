package io.veg.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryAndRdfModelTest {

    @Test
    void comparisonOperatorMatchesTypedValuesSafely() {
        assertThat(ComparisonOperator.EQ.matches(Value.of(new BigDecimal("19.90")), Value.of(new BigDecimal("19.9"))))
                .isTrue();
        assertThat(ComparisonOperator.NEQ.matches(Value.of("A"), Value.of("B"))).isTrue();
        assertThat(ComparisonOperator.GT.matches(Value.of(42L), Value.of(10L))).isTrue();
        assertThat(ComparisonOperator.LTE.matches(Value.of(10L), Value.of(10L))).isTrue();
        assertThat(ComparisonOperator.CONTAINS.matches(Value.of("Hello VEG"), Value.of("VEG"))).isTrue();
        assertThat(ComparisonOperator.STARTS_WITH.matches(Value.of("Hello VEG"), Value.of("Hello"))).isTrue();

        // Cross-type comparisons must return false rather than throwing or coercing
        assertThat(ComparisonOperator.LT.matches(Value.of("10"), Value.of(20L))).isFalse();
        assertThat(ComparisonOperator.CONTAINS.matches(Value.of(123L), Value.of("12"))).isFalse();
        assertThat(ComparisonOperator.LT.matches(Value.of(new byte[]{1}), Value.of(new byte[]{2}))).isFalse();
        assertThat(ComparisonOperator.GTE.matches(
                Value.of(Instant.parse("2026-01-02T00:00:00Z")),
                Value.of(Instant.parse("2026-01-01T00:00:00Z"))
        )).isTrue();
    }

    @Test
    void queryRowAndSortOrderProvideImmutableTypedAccess() {
        EntityId id = new EntityId(1001L);
        QueryRow row = new QueryRow(
                Map.of("p", id),
                Map.of(
                        "p.name", Value.of("Alice"),
                        "p.age", Value.of(34L),
                        "p.score", Value.of(new BigDecimal("0.95")),
                        "p.active", Value.of(true)
                )
        );

        assertThat(row.entity("p")).contains(id);
        assertThat(row.stringValue("p.name")).contains("Alice");
        assertThat(row.intValue("p.age")).contains(34L);
        assertThat(row.decimalValue("p.score")).contains(new BigDecimal("0.95"));
        assertThat(row.booleanValue("p.active")).contains(true);
        assertThat(row.stringValue("p.age")).isEmpty();

        SortOrder sort = new SortOrder("p.age", SortDirection.DESC);
        assertThat(sort.direction()).isEqualTo(SortDirection.DESC);
        assertThatThrownBy(() -> new SortOrder(" ", SortDirection.ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rdfOptionsAndResultsValidateAndCopyCollections() {
        RdfExportOptions def = RdfExportOptions.turtleDefault();
        assertThat(def.format()).isEqualTo(RdfFormat.TURTLE);
        assertThat(def.includeSchema()).isTrue();
        assertThat(def.includeDerivedAssertions()).isFalse();
        assertThat(def.includeReifiedProvenance()).isTrue();

        RdfExportOptions derived = RdfExportOptions.turtleWithDerived();
        assertThat(derived.includeDerivedAssertions()).isTrue();

        RdfExportOptions nt = RdfExportOptions.nTriplesDefault();
        assertThat(nt.format()).isEqualTo(RdfFormat.N_TRIPLES);

        RdfExportResult exportResult = new RdfExportResult("", 0, 0, List.of("warn"));
        assertThatThrownBy(() -> exportResult.warnings().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);

        RdfImportResult importResult = new RdfImportResult(Map.of("ex:a", new EntityId(1000L)), 1, 0, 0, 0);
        assertThatThrownBy(() -> importResult.resourceEntities().put("ex:b", new EntityId(1001L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
