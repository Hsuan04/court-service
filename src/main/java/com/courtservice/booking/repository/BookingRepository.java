package com.courtservice.booking.repository;

import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

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

    /**
     * Returns whether a member has a booking of a session in the given status. Strategies ask for
     * {@link BookingStatus#CONFIRMED} only: a cancelled booking does not stop the member from
     * booking the session again.
     */
    boolean existsByCourtSessionIdAndMemberIdAndStatus(Long courtSessionId, Long memberId, BookingStatus status);

    /**
     * Returns the ids of the members with a booking of a session in the given status, read from the
     * {@code member_id} column without loading bookings or members. Used to initialize the Redis
     * member set of the {@code redis-lua} strategy; a session has at most {@code capacity}
     * confirmed bookings, so the list stays small.
     */
    @Query("select b.member.id from Booking b where b.courtSession.id = :courtSessionId and b.status = :status")
    List<Long> findMemberIdsByCourtSessionIdAndStatus(@Param("courtSessionId") Long courtSessionId,
                                                      @Param("status") BookingStatus status);

    /**
     * Cancels a booking only if it is still confirmed, in one conditional statement.
     *
     * <p>A conditional {@code UPDATE} rather than a read followed by an entity change: when two
     * cancellations of the same booking race, the second waits for the first's row lock, then
     * re-evaluates the condition against the committed row and matches nothing. Exactly one
     * cancellation therefore succeeds and the seat is given back once. The persistence context is
     * cleared afterwards, so an entity loaded earlier in the transaction is not read stale.
     *
     * @param id        the booking id
     * @param updatedAt the modification time to record
     * @return 1 if the booking was cancelled, 0 if it does not exist or was already cancelled
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b
               set b.status = com.courtservice.booking.entity.BookingStatus.CANCELLED, b.updatedAt = :updatedAt
             where b.id = :id and b.status = com.courtservice.booking.entity.BookingStatus.CONFIRMED
            """)
    int cancelIfConfirmed(@Param("id") Long id, @Param("updatedAt") OffsetDateTime updatedAt);
}
