package com.courtservice.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Returns one page of bookings of a session. Filtering on the association id resolves to the
     * {@code court_session_id} foreign key column, so neither the data nor the count query joins
     * {@code court_session}.
     */
    Page<Booking> findByCourtSessionId(Long courtSessionId, Pageable pageable);

    /**
     * Returns one page of bookings of a member. Filtering on the association id resolves to the
     * {@code member_id} foreign key column, so neither the data nor the count query joins
     * {@code member}.
     */
    Page<Booking> findByMemberId(Long memberId, Pageable pageable);
}
