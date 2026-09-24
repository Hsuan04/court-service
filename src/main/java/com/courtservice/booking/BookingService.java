package com.courtservice.booking;

import com.courtservice.booking.dto.BookingResponse;
import com.courtservice.common.error.ResourceNotFoundException;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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
@Transactional(readOnly = true)
public class BookingService {

    private static final String RESOURCE_NAME = "Booking";

    private final BookingRepository bookingRepository;
    private final CourtSessionService courtSessionService;
    private final MemberService memberService;

    public BookingService(BookingRepository bookingRepository, CourtSessionService courtSessionService,
                          MemberService memberService) {
        this.bookingRepository = bookingRepository;
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
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

    private BookingResponse toResponse(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getCourtSession().getId(), booking.getMember().getId(),
                booking.getStatus(), booking.getCreatedAt(), booking.getUpdatedAt());
    }
}
