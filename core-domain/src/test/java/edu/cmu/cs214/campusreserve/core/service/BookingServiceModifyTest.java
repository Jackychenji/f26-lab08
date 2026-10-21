package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingChange;
import edu.cmu.cs214.campusreserve.core.domain.BookingStatus;
import edu.cmu.cs214.campusreserve.core.domain.CancellationResult;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.domain.WaitlistEntry;
import edu.cmu.cs214.campusreserve.core.events.DomainEvent;
import edu.cmu.cs214.campusreserve.core.events.WaitlistPromotedEvent;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.policy.ConflictChecker;
import edu.cmu.cs214.campusreserve.core.ports.BookingStore;
import edu.cmu.cs214.campusreserve.core.ports.IdGenerator;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BookingServiceModifyTest extends BookingServiceTestBase {

    private static final TimeInterval SLOT = Fixtures.interval("10:00", "11:00");
    private static final TimeInterval AFTERNOON = Fixtures.interval("14:00", "15:00");
    private static final TimeInterval STRANDED_SLOT = Fixtures.interval("16:00", "17:00");

    /** Every booking write the service makes, so a test can tell "saved nothing" apart. */
    private CountingBookingStore writes;

    /** The base wiring, with booking writes routed through a counter. */
    @Override
    protected BookingService build(BookingPolicy policy) {
        writes = new CountingBookingStore(bookings);
        service = new BookingService(
            writes, rooms, users, waitlist, publisher, IdGenerator.sequential(), policy, clock);
        return service;
    }

    // Clause 2: a booking never collides with itself.

    @Test
    void aFifteenMinuteNudgeIsAllowedAgainstTheRoomAndTheBookersOwnCalendar() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval nudged = Fixtures.interval("10:15", "11:15");

        ModificationResult result = service.modifyBooking(id, "u-1", nudged, 4);

        assertTrue(result.isModified());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(nudged, stored.interval());
        assertTrue(stored.holdsRoom());
        assertEquals(List.of(id), service.bookingsForRoom("r-1").stream().map(Booking::id).toList());
        assertEquals(List.of(id), service.activeBookingsForUser("u-1").stream().map(Booking::id).toList());
    }

    @Test
    void aMoveOntoTheBookersOtherBookingIsUserDoubleBooked() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
        assertEquals(SLOT, service.findBooking(id).orElseThrow().interval());
    }

    @Test
    void aMoveIntoATakenSlotIsRoomUnavailableAndChangesNothingAtAll() {
        strandAWaiterOnAFreeSlot();
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        assertTrue(book("r-1", "u-3", SLOT).isWaitlisted());
        Booking before = service.findBooking(id).orElseThrow();
        List<Booking> storedBefore = bookings.findAll();
        List<WaitlistEntry> queueBefore = waitlist.findAll();
        List<DomainEvent> eventsBefore = publisher.published();
        int savesBefore = writes.saves();

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("11:30", "12:30"), 4);

        assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
        assertEquals(storedBefore, bookings.findAll());
        assertEquals(queueBefore, waitlist.findAll());
        assertEquals(eventsBefore, publisher.published());
        assertEquals(savesBefore, writes.saves());
        assertTrue(service.waitlistForUser("u-1").isEmpty());
    }

    @Test
    void aBookerAtTheCapCanStillModify() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertEquals(Optional.of(RejectionReason.USER_BOOKING_LIMIT_REACHED),
            book("r-2", "u-1", AFTERNOON).rejectionReason());

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 4);

        assertTrue(result.isModified());
        assertEquals(AFTERNOON, service.findBooking(id).orElseThrow().interval());
        assertEquals(1, service.activeBookingsForUser("u-1").size());
    }

    // Clause 3: the whole request check runs on the new values.

    @Test
    void aPartyTooBigForTheRoomIsRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result = service.modifyBooking(id, "u-1", SLOT, 11);

        assertEquals(Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY), result.rejectionReason());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(4, stored.attendees());
        assertTrue(stored.history().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newIntervalsThatBreakARule")
    void everyIntervalRuleStillAppliesToTheNewInterval(RejectionReason expected, TimeInterval newInterval) {
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result = service.modifyBooking(id, "u-1", newInterval, 4);

        assertEquals(Optional.of(expected), result.rejectionReason());
        assertEquals(SLOT, service.findBooking(id).orElseThrow().interval());
    }

    static Stream<Arguments> newIntervalsThatBreakARule() {
        return Stream.of(
            Arguments.of(RejectionReason.INTERVAL_TOO_SHORT, Fixtures.interval("10:00", "10:15")),
            Arguments.of(RejectionReason.INTERVAL_TOO_LONG, Fixtures.interval("10:00", "14:00")),
            Arguments.of(RejectionReason.INTERVAL_NOT_ALIGNED, Fixtures.interval("10:05", "11:05")),
            Arguments.of(RejectionReason.INTERVAL_SPANS_DAYS,
                TimeInterval.parse("2026-10-20T23:00:00Z", "2026-10-21T00:30:00Z")),
            Arguments.of(RejectionReason.OUTSIDE_OPENING_HOURS, Fixtures.interval("05:00", "06:00")),
            Arguments.of(RejectionReason.INTERVAL_IN_PAST, Fixtures.intervalOn("2026-10-19", "07:00", "08:00")),
            Arguments.of(RejectionReason.TOO_FAR_IN_ADVANCE, Fixtures.intervalOn("2026-11-05", "10:00", "11:00")));
    }

    @Test
    void aRoomTakenOutOfServiceRefusesTheChange() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        addRoom(Fixtures.studyRoom("r-1", 10).withActive(false));

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 4);

        assertEquals(Optional.of(RejectionReason.ROOM_INACTIVE), result.rejectionReason());
    }

    // Clause 1: owner only, upcoming only.

    @Test
    void aCancelledBookingCannotBeModified() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        service.cancelBooking(id, "u-1");

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 4);

        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED), result.rejectionReason());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, stored.status());
        assertEquals(SLOT, stored.interval());
    }

    @Test
    void aBookingThatHasStartedCannotBeModified() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        clock.setTo("2026-10-20T10:00:00Z");

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 4);

        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_STARTED), result.rejectionReason());
        assertEquals(SLOT, service.findBooking(id).orElseThrow().interval());
    }

    @Test
    void aStrangerCannotModifySomeoneElsesBooking() {
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result = service.modifyBooking(id, "u-2", AFTERNOON, 4);

        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(SLOT, stored.interval());
        assertTrue(stored.history().isEmpty());
    }

    @Test
    void anUnknownBookingIsNotFound() {
        ModificationResult result = service.modifyBooking("bk-missing", "u-1", AFTERNOON, 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
    }

    @Test
    void aNonPositivePartySizeIsAnArgumentError() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertThrows(IllegalArgumentException.class, () -> service.modifyBooking(id, "u-1", SLOT, 0));
    }

    // The check order.

    @Test
    void aNoOpByAStrangerIsStillRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER),
            service.modifyBooking(id, "u-2", SLOT, 4).rejectionReason());
    }

    @Test
    void aNoOpOnACancelledBookingIsStillRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        service.cancelBooking(id, "u-1");
        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED),
            service.modifyBooking(id, "u-1", SLOT, 4).rejectionReason());
    }

    @Test
    void aNoOpIsAnsweredBeforeTheRulesRun() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        addRoom(Fixtures.studyRoom("r-1", 10).withActive(false));
        assertTrue(service.modifyBooking(id, "u-1", SLOT, 4).isUnchanged());
    }

    @Test
    void theRulesAreCheckedBeforeEitherCalendar() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 11);

        assertEquals(Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY), result.rejectionReason());
    }

    @Test
    void theBookersOwnCalendarIsCheckedBeforeTheRoom() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
    }

    // Clause 4: freed time goes to the waitlist, and only an interval change looks.

    @Test
    void anIntervalChangePromotesTheFirstWaiterInArrivalOrder() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(book("r-1", "u-3", SLOT).isWaitlisted());
        assertTrue(book("r-1", "u-2", SLOT).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 4);

        assertTrue(result.isModified());
        assertEquals(1, result.promotions().size());
        Booking promoted = result.promotions().get(0).booking();
        assertEquals("u-3", promoted.userId());
        assertEquals(SLOT, promoted.interval());
        assertTrue(service.findBooking(promoted.id()).orElseThrow().holdsRoom());
        assertEquals(List.of("u-2"), service.waitlistForRoom("r-1").stream()
            .map(WaitlistEntry::userId).toList());
        assertEquals(List.of("u-3"), publisher.ofType(WaitlistPromotedEvent.class).stream()
            .map(WaitlistPromotedEvent::userId).toList());
    }

    @Test
    void shorteningHandsTheFreedHourToItsWaiter() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("11:00", "12:00")).isWaitlisted());
        assertTrue(book("r-1", "u-3", SLOT).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", SLOT, 4);

        assertEquals(List.of("u-2"), result.promotions().stream().map(p -> p.booking().userId()).toList());
        assertEquals(List.of("u-3"), service.waitlistForRoom("r-1").stream()
            .map(WaitlistEntry::userId).toList());
    }

    @Test
    void aNudgeFreesOnlyTheQuarterHourItLeaves() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(book("r-1", "u-2", Fixtures.interval("09:30", "10:15")).isWaitlisted());
        assertTrue(book("r-1", "u-3", SLOT).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        assertEquals(List.of("u-2"), result.promotions().stream().map(p -> p.booking().userId()).toList());
        assertEquals(List.of("u-3"), service.waitlistForRoom("r-1").stream()
            .map(WaitlistEntry::userId).toList());
        assertFalse(new ConflictChecker().containsMutualConflict(service.bookingsForRoom("r-1")));
    }

    @Test
    void lengtheningPromotesNobodyWaitingForTheTimeItKeeps() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(book("r-1", "u-2", SLOT).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "12:00"), 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, waitlist.count());
    }

    @Test
    void anAttendeeOnlyChangePromotesNobodyEvenWithAPromotableWaiter() {
        WaitlistEntry stranded = strandAWaiterOnAFreeSlot();
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result = service.modifyBooking(id, "u-1", SLOT, 6);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(6, service.findBooking(id).orElseThrow().attendees());
        assertEquals(List.of(stranded), service.waitlistForRoom("r-1"));
        assertTrue(service.activeBookingsForUser(stranded.userId()).isEmpty());
        assertTrue(publisher.ofType(WaitlistPromotedEvent.class).isEmpty());
    }

    @Test
    void anyIntervalChangeRunsThePassSoTheSameStrandedWaiterIsPromoted() {
        WaitlistEntry stranded = strandAWaiterOnAFreeSlot();
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:30"), 4);

        assertEquals(1, result.promotions().size());
        assertEquals(stranded, result.promotions().get(0).entry());
        assertTrue(service.waitlistForRoom("r-1").isEmpty());
    }

    @Test
    void aFailingSubscriberDoesNotUndoTheModificationOrItsPromotion() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(book("r-1", "u-2", SLOT).isWaitlisted());
        publisher.failWith(new IllegalStateException("subscriber is down"));

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 4);

        assertTrue(result.isModified());
        assertEquals(AFTERNOON, service.findBooking(id).orElseThrow().interval());
        assertEquals(1, result.promotions().size());
        assertEquals(0, waitlist.count());
    }

    // Clause 5, domain half: history.

    @Test
    void aSuccessfulChangeAppendsOneHistoryEntry() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        Booking original = service.findBooking(id).orElseThrow();
        clock.advanceMinutes(30);

        ModificationResult result = service.modifyBooking(id, "u-1", AFTERNOON, 6);

        ModificationResult.Modified modified = (ModificationResult.Modified) result;
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(original, modified.before());
        assertEquals(stored, modified.after());
        assertEquals(List.of(new BookingChange(Instant.parse("2026-10-19T09:30:00Z"), SLOT, 4, AFTERNOON, 6)),
            stored.history());
        assertEquals("r-1", stored.roomId());
        assertEquals("u-1", stored.userId());
        assertEquals(BookingStatus.CONFIRMED, stored.status());
        assertEquals(original.createdAt(), stored.createdAt());
    }

    @Test
    void historyGrowsOldestFirstAndSurvivesCancellation() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        service.modifyBooking(id, "u-1", AFTERNOON, 4);
        service.modifyBooking(id, "u-1", AFTERNOON, 8);

        CancellationResult cancelled = service.cancelBooking(id, "u-1");

        List<BookingChange> history = cancelled.cancelledBooking().orElseThrow().history();
        assertEquals(2, history.size());
        assertEquals(SLOT, history.get(0).previousInterval());
        assertEquals(AFTERNOON, history.get(0).newInterval());
        assertTrue(history.get(0).intervalChanged());
        assertFalse(history.get(1).intervalChanged());
        assertEquals(4, history.get(1).previousAttendees());
        assertEquals(8, history.get(1).newAttendees());
        assertEquals(history, service.findBooking(id).orElseThrow().history());
    }

    @Test
    void aNoOpIsUnchangedWithEmptyHistoryAndWritesNothing() {
        strandAWaiterOnAFreeSlot();
        String id = bookAndGetId("r-1", "u-1", SLOT);
        Booking before = service.findBooking(id).orElseThrow();
        List<WaitlistEntry> queueBefore = waitlist.findAll();
        List<DomainEvent> eventsBefore = publisher.published();
        int savesBefore = writes.saves();

        ModificationResult result = service.modifyBooking(id, "u-1", SLOT, 4);

        assertTrue(result.isUnchanged());
        assertEquals(before, ((ModificationResult.Unchanged) result).booking());
        assertTrue(service.findBooking(id).orElseThrow().history().isEmpty());
        assertEquals(savesBefore, writes.saves());
        assertEquals(queueBefore, waitlist.findAll());
        assertEquals(eventsBefore, publisher.published());
    }

    /**
     * Leaves u-5 waiting in r-1 for a slot that is free, so any promotion pass
     * over r-1 would promote them. The pass that freed the slot skipped them
     * because their own calendar was busy in r-2 at the time; they have since
     * cancelled that booking.
     */
    private WaitlistEntry strandAWaiterOnAFreeSlot() {
        addUser(Fixtures.user("u-4"));
        addUser(Fixtures.user("u-5"));
        String holder = bookAndGetId("r-1", "u-4", STRANDED_SLOT);
        WaitlistEntry entry = book("r-1", "u-5", STRANDED_SLOT).waitlistEntry().orElseThrow();
        String elsewhere = bookAndGetId("r-2", "u-5", STRANDED_SLOT);

        assertTrue(service.cancelBooking(holder, "u-4").promotions().isEmpty());
        assertTrue(service.cancelBooking(elsewhere, "u-5").isCancelled());

        assertEquals(List.of(entry), service.waitlistForRoom("r-1"));
        assertTrue(service.bookingsForRoom("r-1").stream().noneMatch(Booking::holdsRoom));
        assertTrue(service.activeBookingsForUser("u-5").isEmpty());
        return entry;
    }

    /** A booking store that forwards everything and counts the saves. */
    private static final class CountingBookingStore implements BookingStore {
        private final BookingStore delegate;
        private int saves;

        CountingBookingStore(BookingStore delegate) {
            this.delegate = delegate;
        }

        int saves() {
            return saves;
        }

        @Override
        public void save(Booking booking) {
            saves++;
            delegate.save(booking);
        }

        @Override
        public Optional<Booking> findById(String bookingId) {
            return delegate.findById(bookingId);
        }

        @Override
        public List<Booking> findByRoom(String roomId) {
            return delegate.findByRoom(roomId);
        }

        @Override
        public List<Booking> findActiveByRoom(String roomId) {
            return delegate.findActiveByRoom(roomId);
        }

        @Override
        public List<Booking> findByUser(String userId) {
            return delegate.findByUser(userId);
        }

        @Override
        public List<Booking> findActiveByUser(String userId) {
            return delegate.findActiveByUser(userId);
        }

        @Override
        public List<Booking> findAll() {
            return delegate.findAll();
        }

        @Override
        public int count() {
            return delegate.count();
        }
    }
}
