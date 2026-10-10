package com.courtservice.booking.stock;

import com.courtservice.booking.stock.SeatStockScripts.Reservation;
import com.courtservice.support.RedisTestData;
import com.courtservice.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every seat stock Lua script against a real Redis and asserts its return value and the keys
 * it leaves behind, including the cases where a script must write nothing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatStockScriptsIntegrationTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 8L;
    private static final int SEATS = 2;
    private static final int NO_SEATS = 0;
    private static final int RAISE = 3;
    private static final int CUT = -1;

    @Autowired
    private SeatStockScripts scripts;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void flushRedis() {
        new RedisTestData(redissonClient).flushAll();
    }

    @Test
    void reserveReportsAMissingStockWithoutCreatingKeys() {
        // when
        Reservation reservation = scripts.reserve(SESSION_ID, MEMBER_ID);

        // then
        assertThat(reservation).isEqualTo(Reservation.NOT_INITIALIZED);
        assertThat(stock().isExists()).isFalse();
        assertThat(members().isExists()).isFalse();
    }

    @Test
    void initializeWritesStockAndMembersOnlyOnce() {
        // given
        boolean first = scripts.initialize(SESSION_ID, SEATS, List.of(MEMBER_ID));

        // when
        boolean second = scripts.initialize(SESSION_ID, SEATS + 1, List.of(OTHER_MEMBER_ID));

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(stock().get()).isEqualTo(Integer.toString(SEATS));
        assertThat(members().readAll()).containsExactly(Long.toString(MEMBER_ID));
    }

    @Test
    void reserveTakesASeatAndRecordsTheMember() {
        // given
        scripts.initialize(SESSION_ID, SEATS, List.of());

        // when
        Reservation reservation = scripts.reserve(SESSION_ID, MEMBER_ID);

        // then
        assertThat(reservation).isEqualTo(Reservation.RESERVED);
        assertThat(stock().get()).isEqualTo(Integer.toString(SEATS - 1));
        assertThat(members().readAll()).containsExactly(Long.toString(MEMBER_ID));
    }

    @Test
    void reserveRejectsAMemberWhoAlreadyHoldsASeatWithoutTakingAnother() {
        // given
        scripts.initialize(SESSION_ID, SEATS, List.of(MEMBER_ID));

        // when
        Reservation reservation = scripts.reserve(SESSION_ID, MEMBER_ID);

        // then
        assertThat(reservation).isEqualTo(Reservation.ALREADY_RESERVED);
        assertThat(stock().get()).isEqualTo(Integer.toString(SEATS));
    }

    @Test
    void reserveRejectsWhenNoSeatIsLeftWithoutRecordingTheMember() {
        // given
        scripts.initialize(SESSION_ID, NO_SEATS, List.of());

        // when
        Reservation reservation = scripts.reserve(SESSION_ID, MEMBER_ID);

        // then
        assertThat(reservation).isEqualTo(Reservation.NO_SEAT_LEFT);
        assertThat(stock().get()).isEqualTo(Integer.toString(NO_SEATS));
        assertThat(members().readAll()).isEmpty();
    }

    @Test
    void releaseGivesTheSeatBackAndRemovesTheMember() {
        // given
        scripts.initialize(SESSION_ID, SEATS, List.of());
        scripts.reserve(SESSION_ID, MEMBER_ID);

        // when
        boolean returned = scripts.release(SESSION_ID, MEMBER_ID, true);

        // then
        assertThat(returned).isTrue();
        assertThat(stock().get()).isEqualTo(Integer.toString(SEATS));
        assertThat(members().readAll()).isEmpty();
    }

    @Test
    void releaseWithoutReturningTheSeatOnlyRemovesTheMember() {
        // given
        scripts.initialize(SESSION_ID, SEATS, List.of());
        scripts.reserve(SESSION_ID, MEMBER_ID);

        // when
        boolean returned = scripts.release(SESSION_ID, MEMBER_ID, false);

        // then
        assertThat(returned).isFalse();
        assertThat(stock().get()).isEqualTo(Integer.toString(SEATS - 1));
        assertThat(members().readAll()).isEmpty();
    }

    @Test
    void releaseDoesNotRecreateAMissingStock() {
        // when
        boolean returned = scripts.release(SESSION_ID, MEMBER_ID, true);

        // then
        assertThat(returned).isFalse();
        assertThat(stock().isExists()).isFalse();
    }

    @Test
    void adjustAddsTheCapacityChangeToAnExistingStock() {
        // given
        scripts.initialize(SESSION_ID, SEATS, List.of());

        // when
        boolean raised = scripts.adjust(SESSION_ID, RAISE);
        boolean cut = scripts.adjust(SESSION_ID, CUT);

        // then
        assertThat(raised).isTrue();
        assertThat(cut).isTrue();
        assertThat(stock().get()).isEqualTo(Integer.toString(SEATS + RAISE + CUT));
    }

    @Test
    void adjustLeavesAMissingStockAlone() {
        // when
        boolean adjusted = scripts.adjust(SESSION_ID, RAISE);

        // then
        assertThat(adjusted).isFalse();
        assertThat(stock().isExists()).isFalse();
    }

    private RBucket<String> stock() {
        return redissonClient.getBucket(SeatStockKeys.stock(SESSION_ID), StringCodec.INSTANCE);
    }

    private RSet<String> members() {
        return redissonClient.getSet(SeatStockKeys.members(SESSION_ID), StringCodec.INSTANCE);
    }
}
