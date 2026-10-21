# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Written 2026-10-21, before any task ran, on a starter that was green
(418 Java tests, 137 Python tests). Committed on its own, ahead of the guardrail
and the run, so the order is in `git log`.

**Decisions the change request leaves to me.** Every task below relies on them.

- *History lives on the booking.* `Booking` gains an immutable `history` list
  of `BookingChange(changedAt, previousInterval, previousAttendees,
  newInterval, newAttendees)`. `Booking.confirmed(...)` keeps its six-argument
  signature (billing's shipped tests call it) and `cancelled()` keeps the
  history. No new store port, so the `BookingService` constructor that `cli`
  and the shipped tests call does not change.
- *Error surface: no new `RejectionReason`.* Modification reuses
  `BOOKING_NOT_FOUND`, `NOT_BOOKING_OWNER`, `BOOKING_ALREADY_CANCELLED`,
  `BOOKING_ALREADY_STARTED`, the rule reasons, `USER_DOUBLE_BOOKED`, and
  `ROOM_UNAVAILABLE`. `ErrorMapping` already maps every one (404, 403, 409,
  400). A collision is a rejection, never a waitlisting.
- *Check order:* booking exists, then owner / not cancelled / not started, then
  no-op, then `BookingRules.checkRequest`, then the booker's own calendar, then
  the room. A no-op by the owner of an upcoming booking returns `Unchanged`.

**The tasks.**

**T1. A booker can modify their upcoming booking in the domain.**
- *Delivers:* `BookingService.modifyBooking(bookingId, requesterId,
  newInterval, newAttendees)` returning a sealed `ModificationResult`
  (`Modified(before, after, promotions)`, `Unchanged(booking)`,
  `Rejected(reason)`). Change-request clauses 1 to 4 and the domain half of 5:
  owner-only and upcoming-only; the full `checkRequest` on the new values with
  the cap waived; own-calendar and room re-checks through `ConflictChecker` with
  the booking itself filtered out of the list; a rejection writes nothing; a
  success appends one history entry; `promoteWaitlist` runs if and only if the
  interval changed; a no-op saves nothing. No event yet (T2).
- *Modules:* `core-domain` only, main code plus a new
  `core/service/BookingServiceModifyTest`. *Depends on:* nothing.
- *Checkpoint:* `mvn -pl core-domain -am test` reports core-domain
  `Tests run: N, Failures: 0` with N > 167, and `BookingServiceModifyTest` has
  a named test for each of: a 15-minute nudge is allowed (room and own
  calendar); a move into a taken slot is `ROOM_UNAVAILABLE` and leaves the
  stored booking, the waitlist, and the published events exactly as they were;
  a booker at the cap can still modify; a rule failure (capacity); cancelled,
  started, and stranger are rejected; an interval change promotes the first
  waiter in arrival order; an attendee-only change promotes nobody; a no-op is
  `Unchanged` with empty history. Then `git add -N . && git diff --stat`
  lists only files under `core-domain/`.
- *Green while wrong:* the gate only runs the tests the agent wrote. If an
  attendee-only change still calls `promoteWaitlist`, a test where nobody is
  waiting passes anyway. It only shows up with a waiter whose slot is already
  free but who was skipped by an earlier pass (their own calendar was busy then).

**T2. The `booking-modified` event and the owner's notification.**
- *Delivers:* `BookingModifiedEvent` declared in `core-domain/.../events` and
  added to `DomainEvent`'s `permits`. Its core fields are the new interval and
  attendees, plus `previousInterval` and `previousAttendees`. `modifyBooking`
  publishes it on `Modified` only. `EventLogFormat` writes the core fields then
  `previousStart, previousEnd, previousAttendees`, and `eventTypes()` lists
  it. `NotificationKind.BOOKING_MODIFIED` and a `BookingModifiedTemplate`
  registered in `TemplateRegistry.standard()` give the owner a message naming
  the old and new time and party size. Promotions keep the existing notice.
- *Modules:* `core-domain`, `storage`, `notifications`. *Depends on:* T1.
- *Checkpoint:* `mvn -B clean test`, not a module gate. Adding to the sealed
  `DomainEvent` breaks the exhaustive switches in `NotificationService` and
  `NotificationKind`, which `-pl core-domain -am` never compiles. Convincing:
  BUILD SUCCESS with storage > 63 and notifications > 41, a storage test that
  asserts the exact log line of a modification, and a notifications test whose
  body contains both the old and the new `IntervalText`.
- *Green while wrong:* the publish sits before the no-op check, so a no-op
  emits an event and a notification. Storage and notifications tests build the
  event by hand and never go through the service, so they stay green. Needs a
  core-domain test: no-op leaves `publisher` empty.

**T3. The analytics package learns `booking-modified`.**
- *Delivers:* `events.py` accepts `booking-modified`, and `build_ledger`
  updates the existing record's start, end, and attendees in place, keeping
  `created_at` and `from_waitlist`. A modification of a booking the log never
  opened is collected the way an orphan cancellation is. Utilization then
  counts the current length. `revenue.py` is not edited: it reads the same
  ledger (change request: "the ledger learns the new event once").
- *Modules:* `analytics/` only. *Depends on:* T2 (the line format).
- *Checkpoint:* `pytest` from `analytics/` reports `N passed` with N > 137,
  including: book 60 minutes, modify to 30, and the room's `booked_minutes` is
  30 with `booking_count` 1; a promoted booking that is modified keeps
  `promoted_count` 1. `git diff --stat` shows `revenue.py` untouched.
- *Green while wrong:* putting `booking-modified` in `HOLDING_EVENT_TYPES`.
  The ledger re-opens the record, so minutes are right, but `from_waitlist`
  resets and `created_at` moves. A test that checks only minutes passes.

**T4. `PATCH /bookings/{id}`, history on the read view, conventions files.**
- *Delivers:* `Router.patch`; `BookingsHandler` registers
  `PATCH /bookings/{bookingId}` behind `auth.require` and passes the caller's
  own id, so the domain's owner check gives 403 to anyone else, administrators
  included. Params: `start` with `end` or `minutes` (together, optional) and
  `attendees` (optional), at least one present or 400; missing values come from
  the current booking. 200 with the booking and any promotions.
  `JsonViews.booking` gains `history`, so `GET /bookings/{id}` shows what the
  booking used to be to the owner and to an administrator. Docs: the event list
  in `AGENTS.md` rule 6, the event list and HTTP table in `ARCHITECTURE.md`.
  I make the docs edits myself, not the agent.
- *Modules:* `http-api`, plus the two docs. *Depends on:* T1 (T2 for the log line).
- *Checkpoint:* `mvn -pl http-api -am test` reports http-api > 78 with
  `EndpointTest` cases: owner PATCH 200 and a following GET shows one history
  entry; an administrator PATCHing someone else's booking gets 403; a taken
  slot is 409 and a GET shows the booking unchanged; an empty PATCH is 400.
  Then one `mvn -B clean test`.
- *Green while wrong:* the handler calls
  `auth.requireActingFor(request, booking.userId())` and hands the owner's id to
  the service. Administrators can then modify other people's bookings, and tests
  that only try the owner and a plain stranger stay green.

**AGENTS.md rules 3 and 4.** T1's checkpoint catches both, through its diff
audit rather than its tests: a second overlap predicate or a direct
`waitlist.save`/`remove` behaves the same under every test. I grep the T1 diff
for `isBefore|isAfter|compareTo` and `waitlist\.(save|remove)`, and require that
the new code reaches overlap only through `conflictChecker.hasConflict` and
promotion only through the existing `promoteWaitlist`. T1 is the only task that
writes conflict or waitlist logic; T4's audit repeats the grep on `http-api`.

**Out of scope, with reasons.**

| Not touched | Reason |
|---|---|
| `billing` | Change request: "The price does not change. … Billing is not part of this change request. Do not re-price, re-issue, or void and re-invoice." |
| `cli` | Change request: "Nothing in the CLI. The administrative commands render fields that already exist and do not need a new command." |
| `accounts` | Change request: "Nothing in account management. Modification uses the token and ownership checks that already exist. Accounts does not change." |
| `analytics/.../revenue.py` (a file inside an in-scope module) | Change request: "The revenue report is not part of this change … do not fork the reading"; AGENTS.md rule 6 keeps one reader of the log. |
| every shipped test, `CHANGE-REQUEST.md`, `CLAUDE.md` | AGENTS.md rule 7 (never edit a shipped test); the change is judged against these files. |

---

## Milestone 2: The guardrail

**The recipe.** Recipe C, the filesystem lockdown. `guardrails/protected-trees.txt`
lists my three out-of-scope modules, `billing`, `cli`, and `accounts`, and
`guardrails/lockdown.sh` made `billing/src`, `cli/src`, `accounts/src` and the
three `pom.xml` files read-only (59 files under `src/`, all read-only afterwards
by `find -writable`). I picked C because the operating system enforces it, so it
binds every process on this machine: my main agent session, which was started
one directory above the repository and therefore never loads the repository's
`.claude/settings.json` (recipes A and B would not reach it), its subagents, and
any shell command they run.

*What it cannot catch:* on Windows the lock is only the read-only attribute on
existing files, so it does not stop a brand-new file being created inside a
locked `src/`, Git Bash `sed -i` still replaced a read-only test file (it
renames over it), `attrib -R` or `chmod -R u+w` undoes it, and it says nothing
about edits inside the trees I left open, `analytics/.../revenue.py` included.

**The captured block.** A separate Claude Code session started at the
repository root in Manual mode (`claude -p --permission-mode manual`, which the
init record reports as `permissionMode: "default"`, Manual's internal name),
with `Edit` pre-approved by `--allowedTools Edit`. Pre-approving is the same
as clicking Yes on the Manual prompt, so the permission layer let the call
through and the lock was the only thing left in the way. Session
`3f9e60c9-9e94-480c-9e5b-bcad68cb6f62`, model `claude-opus-5[1m]`; the full
stream-json is kept outside the repository (lab forks carry no transcripts).
Excerpt, with the Read results shortened:

```text
USER: Use your Edit tool to add the comment line // lab08 guardrail probe as
      the first line of billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java.
      Use only the Read and Edit tools for this, no shell commands. If the edit
      is refused, stop and tell me exactly what the tool returned.

ASSISTANT tool_use Read  {"file_path": "D:\\datas\\CMU_code\\f26-lab08\\billing\\src\\main\\java\\edu\\cmu\\cs214\\campusreserve\\billing\\Money.java"}
TOOL_RESULT            1  package edu.cmu.cs214.campusreserve.billing; ... (178 lines)
ASSISTANT tool_use Read  {"file_path": "D:\\datas\\CMU_code\\f26-lab08\\guardrails\\protected-trees.txt"}
TOOL_RESULT            ... 12 billing / 13 cli / 14 accounts
ASSISTANT tool_use Edit  {"replace_all": false,
                          "file_path": "D:\\datas\\CMU_code\\f26-lab08\\billing\\src\\main\\java\\edu\\cmu\\cs214\\campusreserve\\billing\\Money.java",
                          "old_string": "package edu.cmu.cs214.campusreserve.billing;",
                          "new_string": "// lab08 guardrail probe\npackage edu.cmu.cs214.campusreserve.billing;"}
TOOL_RESULT is_error=true
      EPERM: operation not permitted, rename
      'D:\datas\CMU_code\f26-lab08\billing\src\main\java\edu\cmu\cs214\campusreserve\billing\Money.java.tmp.27492.9f250d649a51'
      -> 'D:\datas\CMU_code\f26-lab08\billing\src\main\java\edu\cmu\cs214\campusreserve\billing\Money.java'
ASSISTANT: The edit was refused. Stopping as instructed. ... The failure is at
      the filesystem level: Edit wrote a temp file alongside the target and the
      atomic rename over `Money.java` was denied ...
```

File unchanged afterwards: `md5sum -c` on `Money.java` against the hash taken
before the run printed `OK`, `git status --short billing` printed nothing, and
the temp file was gone. The block also shows the gap above: the tool could
*create* `Money.java.tmp...` inside the locked directory. Only replacing the
existing file failed.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The task.** T1, a booker can modify their upcoming booking in the domain
(`core-domain` only, no dependencies). Run by a fresh implementer agent given
the T1 entry above as its brief, under the recipe C lock.

**The takeover point.** Written and committed before the run started. I stop
the agent and write the code myself when any one of these fires:

1. `git add -N . && git diff --stat` lists a path outside `core-domain/`, or a
   file under `core-domain/src/test/` that exists in the starter commit
   `3a9923d` (a shipped test). I `git checkout -- <path>` that file, delete any
   new file outside `core-domain/`, and redo whatever that edit was for by hand.
2. The T1 diff contains a comparison of interval endpoints
   (`grep -nE "isBefore|isAfter|compareTo"` on added lines outside
   `TimeInterval`) or a `waitlist.save`/`waitlist.remove` call outside the
   existing `joinWaitlist`, `leaveWaitlist`, and `promoteWaitlist`. I delete
   that code and write the self-exclusion myself as a filtered list handed to
   `conflictChecker.hasConflict`, and the promotion as a call to
   `promoteWaitlist(roomId)`.
3. The gate `mvn -pl core-domain -am test` is still red after the agent's
   second attempt at the same failure. I stop it, read the first failing
   test, and fix that test's cause myself.

**The evidence.** Name the task from Milestone 1. Paste the result line of its
gate command (run it without `-q` so the line is in the output) and the result
line of one `mvn -B clean test` run after the task was finished. The module
gate cannot see a cross-module compile break on a warm tree. A cold build can,
and so can CI.

**The diff.** The files the agent changed, and how each one maps to that task's
declared scope (plain `git diff` leaves out new files, but
`git add -N . && git diff --stat` includes them). Note anything it touched that
you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

**The refuter.** What you gave it and what you asked it to do (paste the
brief). Then the one finding you settled, and how (the new failing test with
its red run, or the evidence that dismissed it). If nothing it reported held
up, say how you checked its strongest claim.
