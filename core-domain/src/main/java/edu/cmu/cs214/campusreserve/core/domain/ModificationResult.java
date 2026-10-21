package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of a request to change a booking's interval, party size, or both.
 *
 * <p>A successful modification reports the booking before and after the change,
 * and the bookings promoted off the waitlist into any time it freed. A request
 * that would change nothing is answered as {@link Unchanged}, and nothing is
 * written. A refused request writes nothing either; in particular a collision is
 * refused, never waitlisted.
 */
public sealed interface ModificationResult {

    /**
     * The booking now runs as {@code after}. {@code before} is how it stood when
     * the request came in. {@code promotions} may be empty, and always is when
     * only the party size changed.
     */
    record Modified(Booking before, Booking after, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            promotions = List.copyOf(promotions);
        }
    }

    /** The request asked for what the booking already is. Nothing was written. */
    record Unchanged(Booking booking) implements ModificationResult {
        public Unchanged {
            Objects.requireNonNull(booking, "booking");
        }
    }

    /** The modification was refused. The booking stands as it was. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the booking change? */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /** Did the request ask for what the booking already was? */
    default boolean isUnchanged() {
        return this instanceof Unchanged;
    }

    /** Was the modification refused? */
    default boolean isRejected() {
        return this instanceof Rejected;
    }

    /**
     * The booking as it stands after the request: the new version when it was
     * modified, the untouched one when nothing changed, empty when refused.
     */
    default Optional<Booking> currentBooking() {
        if (this instanceof Modified modified) {
            return Optional.of(modified.after());
        }
        if (this instanceof Unchanged unchanged) {
            return Optional.of(unchanged.booking());
        }
        return Optional.empty();
    }

    /** Promotions triggered by the modification, empty unless the interval changed. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
