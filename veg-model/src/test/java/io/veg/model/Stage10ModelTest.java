package io.veg.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Stage10ModelTest {

    @Test
    void validityWindowEvaluatesHalfOpenIntervalCorrectly() {
        Instant t1 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2025-06-01T00:00:00Z");
        Instant t3 = Instant.parse("2025-12-31T23:59:59Z");

        ValidityWindow window = ValidityWindow.of(t1, t3);
        assertThat(window.isValidAt(t1.minusSeconds(1))).isFalse();
        assertThat(window.isValidAt(t1)).isTrue();
        assertThat(window.isValidAt(t2)).isTrue();
        assertThat(window.isValidAt(t3)).isFalse();
        assertThat(ValidityWindow.unbounded().isValidAt(t2)).isTrue();

        assertThatThrownBy(() -> ValidityWindow.of(t2, t1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidityWindow.of(t2, t2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactionReceiptAndBootstrapTemporalConstantsValidateCleanly() {
        RedactionReceipt receipt = new RedactionReceipt(
                new EntityId(1050L),
                Instant.now(),
                Actor.user("dpo"),
                Reason.of("GDPR Art. 17 erasure"),
                "abcd1234",
                3
        );
        assertThat(receipt.affectedCommitCount()).isEqualTo(3);
        assertThat(BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE.isBootstrap()).isTrue();
        assertThat(BootstrapIds.VALID_FROM.isBootstrap()).isTrue();
        assertThat(BootstrapIds.VALID_TO.isBootstrap()).isTrue();
    }
}
