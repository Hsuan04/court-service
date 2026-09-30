package com.courtservice.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface CourtSessionRepository extends JpaRepository<CourtSession, Long> {

    /**
     * @param id        the session id
     * @param updatedAt the modification time to record
     * @return the counters after the increment, or empty if the session is full or does not exist
     */
    @Query(nativeQuery = true, value = """
            UPDATE court_session
               SET booked_count = booked_count + 1, version = version + 1, updated_at = :updatedAt
             WHERE id = :id AND booked_count < capacity
            RETURNING booked_count AS "bookedCount", capacity AS "capacity"
            """)
    Optional<BookedCountSnapshot> incrementBookedCountIfAvailable(@Param("id") Long id,
                                                                  @Param("updatedAt") OffsetDateTime updatedAt);

    @Modifying(flushAutomatically = true)
    @Query("update CourtSession s set s.bookedCount = :bookedCount where s.id = :id")
    int overwriteBookedCount(@Param("id") Long id, @Param("bookedCount") int bookedCount);

    //used by Pessimistic Lock Strategy
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CourtSession s where s.id = :id")
    Optional<CourtSession> findByIdForUpdate(@Param("id") Long id);

}
