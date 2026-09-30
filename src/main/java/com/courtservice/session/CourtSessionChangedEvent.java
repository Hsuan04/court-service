package com.courtservice.session;

/**
 * Published when a court session is updated or deleted, so other modules can drop what they cached
 * about it, for example a sold-out flag after the capacity was raised.
 *
 * <p>Other modules listen instead of being called, because they already depend on this module and
 * a direct call back would create a dependency cycle.
 *
 * @param courtSessionId the id of the changed session
 */
public record CourtSessionChangedEvent(Long courtSessionId) {
}
