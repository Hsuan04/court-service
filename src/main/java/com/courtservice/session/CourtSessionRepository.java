package com.courtservice.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CourtSessionRepository extends JpaRepository<CourtSession, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update CourtSession s set s.bookedCount = :bookedCount where s.id = :id")
    int overwriteBookedCount(@Param("id") Long id, @Param("bookedCount") int bookedCount);

    //used by Pessimistic Lock Strategy
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CourtSession s where s.id = :id")
    Optional<CourtSession> findByIdForUpdate(@Param("id") Long id);

}
