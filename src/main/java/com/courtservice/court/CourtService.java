package com.courtservice.court;

import com.courtservice.common.error.ResourceNotFoundException;
import com.courtservice.court.dto.CourtCreateRequest;
import com.courtservice.court.dto.CourtResponse;
import com.courtservice.court.dto.CourtUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages courts. Owns the court module's persistence; other modules reach courts only
 * through this service.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CourtService {

    private static final String RESOURCE_NAME = "Court";

    private final CourtRepository courtRepository;

    public CourtService(CourtRepository courtRepository) {
        this.courtRepository = courtRepository;
    }

    /**
     * Creates a court.
     *
     * @param request the court details
     * @return the created court
     */
    @Transactional
    public CourtResponse create(CourtCreateRequest request) {
        Court court = courtRepository.save(new Court(request.name(), request.address()));
        log.info("Created court {}", court.getId());
        return toResponse(court);
    }

    /**
     * Returns a single court.
     *
     * @param id the court id
     * @return the court
     * @throws ResourceNotFoundException if no court has the given id
     */
    public CourtResponse get(Long id) {
        return toResponse(findCourt(id));
    }

    /**
     * Returns one page of courts.
     *
     * @param pageable the requested page, size and sort
     * @return the requested page
     */
    public Page<CourtResponse> list(Pageable pageable) {
        return courtRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Replaces the details of a court.
     *
     * @param id      the court id
     * @param request the new details
     * @return the updated court
     * @throws ResourceNotFoundException if no court has the given id
     */
    @Transactional
    public CourtResponse update(Long id, CourtUpdateRequest request) {
        Court court = findCourt(id);
        court.changeDetails(request.name(), request.address());
        // Flush before mapping so that @UpdateTimestamp has already refreshed updatedAt.
        courtRepository.flush();
        log.info("Updated court {}", id);
        return toResponse(court);
    }

    /**
     * Deletes a court.
     *
     * <p>Whether the court still has sessions is decided by the {@code court_session.court_id}
     * foreign key rather than by asking the session module, because the session module already
     * depends on this one and a reverse call would create a cycle. The flush forces the
     * constraint check inside this method, so the resulting
     * {@link org.springframework.dao.DataIntegrityViolationException} is raised here and mapped
     * to {@code RESOURCE_IN_USE} by the global exception handler, instead of surfacing later
     * at commit time.
     *
     * @param id the court id
     * @throws ResourceNotFoundException if no court has the given id
     * @throws org.springframework.dao.DataIntegrityViolationException if the court still has sessions
     */
    @Transactional
    public void delete(Long id) {
        courtRepository.delete(findCourt(id));
        courtRepository.flush();
        log.info("Deleted court {}", id);
    }

    /**
     * Returns a reference to an existing court, for other modules that only need it to set a
     * foreign key. The court's columns are not loaded.
     *
     * @param id the court id
     * @return a lazy reference to the court
     * @throws ResourceNotFoundException if no court has the given id
     */
    public Court getCourtReference(Long id) {
        if (!courtRepository.existsById(id)) {
            throw new ResourceNotFoundException(RESOURCE_NAME, id);
        }
        return courtRepository.getReferenceById(id);
    }

    private Court findCourt(Long id) {
        return courtRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
    }

    private CourtResponse toResponse(Court court) {
        return new CourtResponse(court.getId(), court.getName(), court.getAddress(),
                court.getCreatedAt(), court.getUpdatedAt());
    }
}
