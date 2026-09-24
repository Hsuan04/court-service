package com.courtservice.session;

import com.courtservice.common.error.ResourceNotFoundException;
import com.courtservice.court.Court;
import com.courtservice.court.CourtService;
import com.courtservice.session.dto.CourtSessionCreateRequest;
import com.courtservice.session.dto.CourtSessionResponse;
import com.courtservice.session.dto.CourtSessionUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages court sessions. {@code bookedCount} is never changed here; it belongs to the booking
 * flow. Depends on the court module through {@link CourtService} only.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CourtSessionService {

    private static final String RESOURCE_NAME = "CourtSession";

    private final CourtSessionRepository courtSessionRepository;
    private final CourtService courtService;

    public CourtSessionService(CourtSessionRepository courtSessionRepository, CourtService courtService) {
        this.courtSessionRepository = courtSessionRepository;
        this.courtService = courtService;
    }

    /**
     * Creates a court session with no bookings.
     *
     * @param request the session details
     * @return the created session
     * @throws ResourceNotFoundException if the court does not exist
     * @throws com.courtservice.common.error.InvalidTimeRangeException if a time range is empty or reversed
     */
    @Transactional
    public CourtSessionResponse create(CourtSessionCreateRequest request) {
        Court court = courtService.getCourtReference(request.courtId());
        CourtSession session = courtSessionRepository.save(new CourtSession(court, request.sessionDate(),
                request.startTime(), request.endTime(), request.capacity(), request.openAt(), request.closeAt()));
        log.info("Created court session {} for court {}", session.getId(), request.courtId());
        return toResponse(session);
    }

    /**
     * Returns a single court session.
     *
     * @param id the session id
     * @return the session
     * @throws ResourceNotFoundException if no session has the given id
     */
    public CourtSessionResponse get(Long id) {
        return toResponse(findSession(id));
    }

    /**
     * Returns one page of court sessions. Only the court id is exposed, and it is read from the
     * lazy proxy, so listing does not load courts.
     *
     * @param pageable the requested page, size and sort
     * @return the requested page
     */
    public Page<CourtSessionResponse> list(Pageable pageable) {
        return courtSessionRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Replaces the schedule and capacity of a court session. The hosting court and
     * {@code bookedCount} are left unchanged. If either business method rejects the change,
     * the exception rolls back the whole transaction, so no partial update is persisted.
     *
     * @param id      the session id
     * @param request the new schedule and capacity
     * @return the updated session
     * @throws ResourceNotFoundException if no session has the given id
     * @throws com.courtservice.common.error.InvalidTimeRangeException if a time range is empty or reversed
     * @throws com.courtservice.common.error.BusinessRuleViolationException if the capacity is below
     *                                                                      {@code bookedCount}
     */
    @Transactional
    public CourtSessionResponse update(Long id, CourtSessionUpdateRequest request) {
        CourtSession session = findSession(id);
        session.reschedule(request.sessionDate(), request.startTime(), request.endTime(),
                request.openAt(), request.closeAt());
        session.changeCapacity(request.capacity());
        // Flush before mapping so that @UpdateTimestamp has already refreshed updatedAt.
        courtSessionRepository.flush();
        log.info("Updated court session {}", id);
        return toResponse(session);
    }

    /**
     * Deletes a court session.
     *
     * <p>Whether the session still has bookings is decided by the {@code booking.court_session_id}
     * foreign key rather than by asking the booking module, because the booking module already
     * depends on this one and a reverse call would create a cycle. The flush forces the
     * constraint check inside this method, so the resulting
     * {@link org.springframework.dao.DataIntegrityViolationException} is raised here and mapped
     * to {@code RESOURCE_IN_USE} by the global exception handler.
     *
     * @param id the session id
     * @throws ResourceNotFoundException if no session has the given id
     * @throws org.springframework.dao.DataIntegrityViolationException if the session still has bookings
     */
    @Transactional
    public void delete(Long id) {
        courtSessionRepository.delete(findSession(id));
        courtSessionRepository.flush();
        log.info("Deleted court session {}", id);
    }

    /**
     * Verifies that a court session exists, for other modules that query data owned by a session.
     *
     * @param id the session id
     * @throws ResourceNotFoundException if no session has the given id
     */
    public void verifyExists(Long id) {
        if (!courtSessionRepository.existsById(id)) {
            throw new ResourceNotFoundException(RESOURCE_NAME, id);
        }
    }

    private CourtSession findSession(Long id) {
        return courtSessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
    }

    private CourtSessionResponse toResponse(CourtSession session) {
        return new CourtSessionResponse(session.getId(), session.getCourt().getId(), session.getSessionDate(),
                session.getStartTime(), session.getEndTime(), session.getCapacity(), session.getBookedCount(),
                session.getOpenAt(), session.getCloseAt(), session.getCreatedAt(), session.getUpdatedAt());
    }
}
