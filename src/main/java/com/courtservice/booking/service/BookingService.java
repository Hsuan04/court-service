package com.courtservice.booking.service;

import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.dto.BookingCreateResponse;
import com.courtservice.booking.dto.BookingResponse;
import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.event.BookingCancelledEvent;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.strategy.BookingStrategyRegistry;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.common.error.ResourceNotFoundException;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Creates, cancels and queries bookings. Depends on the session and member modules through their
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
    private final SoldOutSessionCache soldOutSessionCache;
    private final ApplicationEventPublisher eventPublisher;

    public BookingService(BookingRepository bookingRepository, CourtSessionService courtSessionService,
                          MemberService memberService, BookingStrategyRegistry bookingStrategyRegistry,
                          SoldOutSessionCache soldOutSessionCache, ApplicationEventPublisher eventPublisher) {
        this.bookingRepository = bookingRepository;
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingStrategyRegistry = bookingStrategyRegistry;
        this.soldOutSessionCache = soldOutSessionCache;
        this.eventPublisher = eventPublisher;
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
     * <p>Before any strategy runs, a session flagged as sold out by {@link SoldOutSessionCache} is
     * rejected with {@code SESSION_FULL} without touching the database. This changes the error
     * precedence while the flag lives: an unknown member gets {@code SESSION_FULL} rather than
     * {@code RESOURCE_NOT_FOUND}, and a repeated booking gets {@code SESSION_FULL} rather than
     * {@code DUPLICATE_BOOKING}. That is accepted because skipping the database is the purpose of
     * the flag, neither request could succeed, and the precise error returns once the flag expires.
     * The flag is set when a strategy reports {@code SESSION_FULL} or a booking takes the last seat.
     *
     * @param request the session and member to book
     * @return the created booking with the capacity and booked count the strategy wrote
     * @throws ResourceNotFoundException if the session or member does not exist
     * @throws BusinessException with {@code DUPLICATE_BOOKING} if the member already booked the
     *                           session, or {@code SESSION_FULL} if no seat is left or the session
     *                           is flagged as sold out
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingCreateResponse create(BookingCreateRequest request) {
        soldOutSessionCache.rejectIfSoldOut(request.courtSessionId());
        BookingOutcome outcome;
        try {
            outcome = bookingStrategyRegistry.active().book(request.courtSessionId(), request.memberId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SESSION_FULL) {
                soldOutSessionCache.markSoldOut(request.courtSessionId());
            }
            throw e;
        } catch (DataIntegrityViolationException e) {
            if (!violatesSessionMemberUniqueness(e)) {
                throw e;
            }
            log.info("Session {} member {}: duplicate booking rejected by the unique constraint",
                    request.courtSessionId(), request.memberId());
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }
        if (outcome.bookedCount() >= outcome.capacity()) {
            soldOutSessionCache.markSoldOut(request.courtSessionId());
        }
        BookingResponse booking = toResponse(outcome.booking());
        log.info("Created booking {} for session {} by member {} (bookedCount={}/{})", booking.id(),
                request.courtSessionId(), request.memberId(), outcome.bookedCount(), outcome.capacity());
        return new BookingCreateResponse(booking, outcome.capacity(), outcome.bookedCount());
    }

    /**
     * Cancels a booking and gives its seat back to the session. The booking row is kept with status
     * {@code CANCELLED}, and the member may book the session again.
     *
     * <p>The same for every concurrency strategy, because both writes are conditional statements:
     * the booking changes only if it is still confirmed, so of two concurrent cancellations exactly
     * one succeeds, and {@code booked_count} drops by one only for that one. The seat release also
     * raises the session's version, so a lock-based booking that read the old count at the same
     * moment fails with {@code CONCURRENT_MODIFICATION} instead of overwriting the release.
     *
     * <p>State outside the database (the Redis seat stock and the sold-out flag) follows through a
     * {@link BookingCancelledEvent} handled after commit, so a rolled-back cancellation never gives
     * a seat back there.
     *
     * @param id the booking id
     * @throws ResourceNotFoundException if no booking has the given id
     * @throws BusinessException         with {@code BOOKING_ALREADY_CANCELLED} if the booking was
     *                                   already cancelled
     */
    @Transactional
    public void cancel(Long id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
        // Read before the conditional update clears the persistence context; getId() needs no query.
        Long courtSessionId = booking.getCourtSession().getId();
        Long memberId = booking.getMember().getId();

        if (bookingRepository.cancelIfConfirmed(id, OffsetDateTime.now()) == 0) {
            log.info("Booking {}: cancellation rejected, already cancelled", id);
            throw new BusinessException(ErrorCode.BOOKING_ALREADY_CANCELLED,
                    ErrorCode.BOOKING_ALREADY_CANCELLED.getDefaultDetail());
        }
        courtSessionService.releaseSeat(courtSessionId);
        eventPublisher.publishEvent(new BookingCancelledEvent(id, courtSessionId, memberId));
        log.info("Cancelled booking {} of session {}", id, courtSessionId);
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
