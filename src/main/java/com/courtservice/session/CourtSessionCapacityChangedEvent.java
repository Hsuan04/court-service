package com.courtservice.session;

/**
 * Published when the capacity of a court session changes, so other modules that keep their own
 * seat count can adjust it, for example the Redis seat stock of the Redis booking strategies.
 *
 * <p>It carries the difference rather than the new capacity: a seat count kept elsewhere may
 * already include seats that are taken but not yet committed, and adding the difference keeps
 * them, while resetting the count to {@code capacity - bookedCount} would hand them out again.
 *
 * @param courtSessionId the id of the changed session
 * @param capacityDelta  the new capacity minus the old one; never zero
 */
public record CourtSessionCapacityChangedEvent(Long courtSessionId, int capacityDelta) {
}
