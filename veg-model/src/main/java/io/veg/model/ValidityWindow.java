package io.veg.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Half-open business validity interval {@code [validFrom, validTo)} attached to an entity or edge ({@code V-14}).
 *
 * <p>Absent bounds represent open-ended validity ({@code -infinity} or {@code +infinity}).</p>
 */
public record ValidityWindow(Optional<Instant> validFrom, Optional<Instant> validTo) {

    public static final ValidityWindow UNBOUNDED = new ValidityWindow(Optional.empty(), Optional.empty());

    public ValidityWindow {
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        Objects.requireNonNull(validTo, "validTo must not be null");
        if (validFrom.isPresent() && validTo.isPresent() && !validFrom.get().isBefore(validTo.get())) {
            throw new IllegalArgumentException(
                    "validFrom (" + validFrom.get() + ") must be strictly before validTo (" + validTo.get() + ")");
        }
    }

    public static ValidityWindow unbounded() {
        return UNBOUNDED;
    }

    public static ValidityWindow of(Instant validFrom, Instant validTo) {
        return new ValidityWindow(Optional.ofNullable(validFrom), Optional.ofNullable(validTo));
    }

    /**
     * Returns {@code true} iff {@code validTime} lies inside the half-open interval {@code [validFrom, validTo)}.
     */
    public boolean isValidAt(Instant validTime) {
        Objects.requireNonNull(validTime, "validTime must not be null");
        if (validFrom.isPresent() && validTime.isBefore(validFrom.get())) {
            return false;
        }
        return validTo.isEmpty() || validTime.isBefore(validTo.get());
    }
}
