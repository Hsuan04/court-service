package com.courtservice.booking;

import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.dto.BookingCreateResponse;
import com.courtservice.booking.dto.BookingResponse;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.strategy.BookingStrategyRegistry;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.common.error.ResourceNotFoundException;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries over bookings. Depends on the session and member modules through their
 * services only.
 *
 * <p>Responses expose the associated session and member by id, read from the lazy proxies via
 * {@code getId()}. Hibernate answers {@code getId()} on an uninitialized proxy from the foreign
 * key it already holds, so listing bookings issues no extra query per row (no N+1).
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class BookingService {

    private static final String RESOURCE_NAME = "Booking";

    private final BookingRepository bookingRepository;
    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingStrategyRegistry bookingStrategyRegistry;

    public BookingService(BookingRepository bookingRepository, CourtSessionService courtSessionService,
                          MemberService memberService, BookingStrategyRegistry bookingStrategyRegistry) {
        this.bookingRepository = bookingRepository;
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingStrategyRegistry = bookingStrategyRegistry;
    }

    /**
     * Returns a single booking.
     *
     * @param id the booking id
     * @return the booking
     * @throws ResourceNotFoundException if no booking has the given id
     */
    public BookingResponse get(Long id) {
        return bookingRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
    }

    /**
     * Returns one page of bookings of a court session. The session is checked first so that an
     * unknown session yields 404 rather than an empty page.
     *
     * @param courtSessionId the session id
     * @param pageable       the requested page, size and sort
     * @return the requested page
     * @throws ResourceNotFoundException if no session has the given id
     */
    public Page<BookingResponse> listByCourtSession(Long courtSessionId, Pageable pageable) {
        courtSessionService.verifyExists(courtSessionId);
        return bookingRepository.findByCourtSessionId(courtSessionId, pageable).map(this::toResponse);
    }

    /**
     * Returns one page of bookings of a member. The member is checked first so that an unknown
     * member yields 404 rather than an empty page.
     *
     * @param memberId the member id
     * @param pageable the requested page, size and sort
     * @return the requested page
     * @throws ResourceNotFoundException if no member has the given id
     */
    public Page<BookingResponse> listByMember(Long memberId, Pageable pageable) {
        memberService.verifyExists(memberId);
        return bookingRepository.findByMemberId(memberId, pageable).map(this::toResponse);
    }

    /**
     * Creates a booking using the configured concurrency strategy.
     *
     * <p>{@code NOT_SUPPORTED} suspends the class-level read-only transaction, so this method runs
     * with no transaction and the strategy decides where its own transaction begins and ends.
     * Without it, the strategy would join a read-only transaction (PostgreSQL rejects the INSERT),
     * and a JVM lock taken by the strategy would be released before the outer transaction commits.
     *
     * <p>A strategy's duplicate check is itself a check-then-act race: two concurrent requests of
     * the same member can both pass it, leaving the database unique constraint to reject the second
     * insert. That violation is translated here, once for every strategy, into the same
     * {@code DUPLICATE_BOOKING} error the check would have raised.
     *
     * @param request the session and member to book
     * @return the created booking with the capacity and booked count the strategy wrote
     * @throws ResourceNotFoundException if the session or member does not exist
     * @throws BusinessException with {@code DUPLICATE_BOOKING} if the member already booked the
     *                           session
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingCreateResponse create(BookingCreateRequest request) {
        BookingOutcome outcome;
        try {
            outcome = bookingStrategyRegistry.active().book(request.courtSessionId(), request.memberId());
        } catch (DataIntegrityViolationException e) {
            if (!violatesSessionMemberUniqueness(e)) {
                throw e;
            }
            log.info("Session {} member {}: duplicate booking rejected by the unique constraint",
                    request.courtSessionId(), request.memberId());
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }
        BookingResponse booking = toResponse(outcome.booking());
        log.info("Created booking {} for session {} by member {} (bookedCount={}/{})", booking.id(),
                request.courtSessionId(), request.memberId(), outcome.bookedCount(), outcome.capacity());
        return new BookingCreateResponse(booking, outcome.capacity(), outcome.bookedCount());
    }

    private static boolean violatesSessionMemberUniqueness(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return Booking.SESSION_MEMBER_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    private BookingResponse toResponse(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getCourtSession().getId(), booking.getMember().getId(),
                booking.getStatus(), booking.getCreatedAt(), booking.getUpdatedAt());
    }
}
