package edu.cmu.cs214.campusreserve.core.policy;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingRequest;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.Room;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules a request must satisfy before the platform looks at whether the
 * room is free. Every check returns a {@link RejectionReason} or nothing.
 *
 * <p>The checks run in a fixed order so that the reason a caller sees is stable:
 * the room first, then the shape of the interval, then the calendar window, then
 * the attendee count.
 */
public final class BookingRules {

    private final BookingPolicy policy;
    private final Clock clock;

    public BookingRules(BookingPolicy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The limits these rules enforce. */
    public BookingPolicy policy() {
        return policy;
    }

    /** The current instant, truncated to the whole minute the platform works in. */
    public Instant now() {
        return clock.instant();
    }

    /**
     * Runs every request-level check against {@code room} in order. Returns the
     * first reason that fires, or empty when the request is well formed.
     */
    public Optional<RejectionReason> checkRequest(BookingRequest request, Room room) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(room, "room");
        if (!room.active()) {
            return Optional.of(RejectionReason.ROOM_INACTIVE);
        }
        Optional<RejectionReason> shape = checkIntervalShape(request.interval(), room);
        if (shape.isPresent()) {
            return shape;
        }
        Optional<RejectionReason> window = checkCalendarWindow(request.interval(), room);
        if (window.isPresent()) {
            return window;
        }
        return checkAttendees(request.attendees(), room);
    }

    /** Duration, alignment, and single-day checks. */
    public Optional<RejectionReason> checkIntervalShape(TimeInterval interval, Room room) {
        long minutes = interval.durationMinutes();
        if (minutes < policy.minDurationMinutes()) {
            return Optional.of(RejectionReason.INTERVAL_TOO_SHORT);
        }
        if (minutes > effectiveMaxDurationMinutes(room)) {
            return Optional.of(RejectionReason.INTERVAL_TOO_LONG);
        }
        if (!interval.isAlignedTo(policy.slotGranularityMinutes())) {
            return Optional.of(RejectionReason.INTERVAL_NOT_ALIGNED);
        }
        if (!interval.isWithinOneUtcDay()) {
            return Optional.of(RejectionReason.INTERVAL_SPANS_DAYS);
        }
        return Optional.empty();
    }

    /** Past, opening-hours, and advance-window checks. */
    public Optional<RejectionReason> checkCalendarWindow(TimeInterval interval, Room room) {
        Instant now = now();
        if (!interval.start().isAfter(now)) {
            return Optional.of(RejectionReason.INTERVAL_IN_PAST);
        }
        if (interval.startMinuteOfDayUtc() < policy.openingMinuteOfDayUtc()
            || interval.endMinuteOfDayUtc() > policy.closingMinuteOfDayUtc()) {
            return Optional.of(RejectionReason.OUTSIDE_OPENING_HOURS);
        }
        long daysAhead = Duration.between(now, interval.start()).toDays();
        if (daysAhead > effectiveMaxAdvanceDays(room)) {
            return Optional.of(RejectionReason.TOO_FAR_IN_ADVANCE);
        }
        return Optional.empty();
    }

    /** Does the room seat the party? */
    public Optional<RejectionReason> checkAttendees(int attendees, Room room) {
        if (!room.fits(attendees)) {
            return Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY);
        }
        return Optional.empty();
    }

    /**
     * May {@code requesterId} cancel {@code booking} right now? Owners may cancel
     * their own bookings until the moment the booking starts.
     */
    public Optional<RejectionReason> checkCancellable(Booking booking, String requesterId) {
        Objects.requireNonNull(booking, "booking");
        Objects.requireNonNull(requesterId, "requesterId");
        if (!booking.isOwnedBy(requesterId)) {
            return Optional.of(RejectionReason.NOT_BOOKING_OWNER);
        }
        if (!booking.holdsRoom()) {
            return Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED);
        }
        if (booking.hasStartedBy(now())) {
            return Optional.of(RejectionReason.BOOKING_ALREADY_STARTED);
        }
        return Optional.empty();
    }

    /**
     * May {@code requesterId} change {@code booking} right now? The window is the
     * same as for cancelling: only the owner, only while the booking still holds
     * the room, and only until the moment it starts. The reasons come back in the
     * same order.
     */
    public Optional<RejectionReason> checkModifiable(Booking booking, String requesterId) {
        return checkCancellable(booking, requesterId);
    }

    /** Is the booking still in the future? */
    public boolean isUpcoming(Booking booking) {
        return !booking.hasStartedBy(now());
    }

    /** Would this interval still be legal to hold if it were promoted right now? */
    public boolean isPromotable(TimeInterval interval, Room room) {
        return room.active()
            && checkIntervalShape(interval, room).isEmpty()
            && checkCalendarWindow(interval, room).isEmpty();
    }

    /** The stricter of the platform cap and the room kind's cap. */
    public int effectiveMaxDurationMinutes(Room room) {
        return Math.min(policy.maxDurationMinutes(), room.kind().maxDurationMinutes());
    }

    /** The stricter of the platform window and the room kind's window. */
    public int effectiveMaxAdvanceDays(Room room) {
        return Math.min(policy.maxAdvanceDays(), room.kind().maxAdvanceDays());
    }
}
