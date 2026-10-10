package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.stock.SeatStockKeys;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the Redis counter handling around the transaction with mocked Redisson objects, so the
 * initialization and the rules for giving a seat back are asserted without Redis or a database.
 */
class RedisCacheBookingStrategyTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final int CAPACITY = 10;
    private static final int BOOKED = 3;
    private static final long SEATS_LEFT_AFTER_DEDUCTION = 4L;
    private static final long OVERDRAWN = -1L;

    private CourtSessionService courtSessionService;
    private TransactionTemplate transactionTemplate;
    private RBucket<String> bucket;
    private RAtomicLong stock;
    private RedisCacheBookingStrategy strategy;

    @BeforeEach
    void setUp() {
        courtSessionService = mock(CourtSessionService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        bucket = mockBucket();
        stock = mock(RAtomicLong.class);
        when(redissonClient.<String>getBucket(SeatStockKeys.stock(SESSION_ID), StringCodec.INSTANCE)).thenReturn(bucket);
        when(redissonClient.getAtomicLong(SeatStockKeys.stock(SESSION_ID))).thenReturn(stock);
        when(bucket.isExists()).thenReturn(true);
        when(stock.decrementAndGet()).thenReturn(SEATS_LEFT_AFTER_DEDUCTION);
        strategy = new RedisCacheBookingStrategy(courtSessionService, mock(MemberService.class),
                mock(BookingRepository.class), transactionTemplate, redissonClient);
    }

    @Test
    void missingStockIsInitializedFromTheDatabase() {
        // given
        CourtSession session = mock(CourtSession.class);
        when(session.getCapacity()).thenReturn(CAPACITY);
        when(session.getBookedCount()).thenReturn(BOOKED);
        when(bucket.isExists()).thenReturn(false);
        when(courtSessionService.findSession(SESSION_ID)).thenReturn(session);
        when(transactionTemplate.execute(anyCallback())).thenReturn(new BookingOutcome(null, CAPACITY, BOOKED + 1));

        // when
        strategy.book(SESSION_ID, MEMBER_ID);

        // then
        verify(bucket).setIfAbsent(Integer.toString(CAPACITY - BOOKED));
    }

    @Test
    void existingStockIsNotReloaded() {
        // given
        when(transactionTemplate.execute(anyCallback())).thenReturn(new BookingOutcome(null, CAPACITY, 1));

        // when
        strategy.book(SESSION_ID, MEMBER_ID);

        // then
        verify(courtSessionService, never()).findSession(SESSION_ID);
        verify(bucket, never()).setIfAbsent(anyString());
    }

    @Test
    void successfulBookingKeepsTheSeat() {
        // given
        BookingOutcome outcome = new BookingOutcome(null, CAPACITY, 1);
        when(transactionTemplate.execute(anyCallback())).thenReturn(outcome);

        // when
        BookingOutcome result = strategy.book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(result).isSameAs(outcome);
        verify(stock, never()).incrementAndGet();
    }

    @Test
    void overdrawnCounterGivesTheDeductionBackWithoutTouchingTheDatabase() {
        // given
        when(stock.decrementAndGet()).thenReturn(OVERDRAWN);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        verify(stock, times(1)).incrementAndGet();
        verify(transactionTemplate, never()).execute(anyCallback());
    }

    @Test
    void businessFailureInTheDatabaseGivesTheSeatBack() {
        // given
        BusinessException notFound = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultDetail());
        when(transactionTemplate.execute(anyCallback())).thenThrow(notFound);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(notFound);
        verify(stock, times(1)).incrementAndGet();
    }

    @Test
    void unexpectedFailureInTheDatabaseGivesTheSeatBack() {
        // given
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(transactionTemplate.execute(anyCallback())).thenThrow(failure);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(failure);
        verify(stock, times(1)).incrementAndGet();
    }

    /** Redis granted a seat the database does not have; giving it back would widen the drift. */
    @Test
    void fullDatabaseKeepsTheSeatOutOfTheCounter() {
        // given
        BusinessException full = new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        when(transactionTemplate.execute(anyCallback())).thenThrow(full);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(full);
        verify(stock, never()).incrementAndGet();
    }

    @Test
    void failureToGiveTheSeatBackKeepsTheOriginalException() {
        // given
        IllegalStateException failure = new IllegalStateException("database unavailable");
        IllegalStateException redisDown = new IllegalStateException("Redis unavailable");
        when(transactionTemplate.execute(anyCallback())).thenThrow(failure);
        when(stock.incrementAndGet()).thenThrow(redisDown);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isSameAs(failure)
                .hasSuppressedException(redisDown);
    }

    @SuppressWarnings("unchecked")
    private static RBucket<String> mockBucket() {
        return mock(RBucket.class);
    }

    private static TransactionCallback<BookingOutcome> anyCallback() {
        return any();
    }
}
