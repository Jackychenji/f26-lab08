package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One modification of a booking: what the interval and the party size were
 * before the change, what they became, and when the change was made.
 *
 * <p>The room never changes, so it is not recorded. A change that alters nothing
 * is never recorded either; the service answers it without touching the booking.
 */
public record BookingChange(
    Instant changedAt,
    TimeInterval previousInterval,
    int previousAttendees,
    TimeInterval newInterval,
    int newAttendees) {

    public BookingChange {
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(previousInterval, "previousInterval");
        Objects.requireNonNull(newInterval, "newInterval");
        if (previousAttendees <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + previousAttendees);
        }
        if (newAttendees <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + newAttendees);
        }
    }

    /** Did the change move or resize the interval? */
    public boolean intervalChanged() {
        return !previousInterval.equals(newInterval);
    }

    /** Did the change alter the party size? */
    public boolean attendeesChanged() {
        return previousAttendees != newAttendees;
    }
}
