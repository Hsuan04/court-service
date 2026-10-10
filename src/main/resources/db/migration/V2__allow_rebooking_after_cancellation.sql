-- A cancelled booking must not stop the member from booking the same session again, so only
-- confirmed bookings stay unique per session and member. The partial unique index keeps the old
-- constraint's name: PostgreSQL reports a violation under the index name, which the application
-- matches to answer DUPLICATE_BOOKING.
ALTER TABLE booking DROP CONSTRAINT uq_booking_session_member;
CREATE UNIQUE INDEX uq_booking_session_member ON booking (court_session_id, member_id) WHERE status = 'CONFIRMED';

-- The dropped constraint's index also served lookups by session (listing a session's bookings,
-- the foreign key check on session delete); the partial index skips cancelled rows, so it cannot.
CREATE INDEX idx_booking_court_session_id ON booking (court_session_id);
