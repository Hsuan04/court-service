package com.courtservice.booking.stock;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Runs the Lua scripts in {@code redis/booking/} that keep the seat stock of a session in Redis
 * (see {@link SeatStockKeys}).
 *
 * <p>Redis runs a script as one command, so each script's checks and writes are atomic: no other
 * client's command can run between them. That is what lets {@link #reserve(Long, Long)} check the
 * member, check the stock and take the seat without a lock.
 *
 * <p>Every value is passed as a string through {@link StringCodec}, so the keys hold plain
 * integers and member ids that {@code redis-cli} shows as they are.
 */
@Component
public class SeatStockScripts {

    private static final String SCRIPT_DIRECTORY = "redis/booking/";
    private static final long NO_SEAT_LEFT = -1;
    private static final long ALREADY_RESERVED = -2;
    private static final long NOT_INITIALIZED = -3;
    private static final long DONE = 1;
    private static final String RETURN_SEAT = "1";
    private static final String KEEP_SEAT = "0";

    private final RScript script;
    private final String reserveSeat;
    private final String initSeatStock;
    private final String releaseSeat;
    private final String adjustSeatStock;

    /**
     * Reads every script from the classpath once.
     *
     * @param redissonClient the client the scripts run on
     * @throws UncheckedIOException if a script cannot be read
     */
    public SeatStockScripts(RedissonClient redissonClient) {
        this.script = redissonClient.getScript(StringCodec.INSTANCE);
        this.reserveSeat = load("reserve_seat.lua");
        this.initSeatStock = load("init_seat_stock.lua");
        this.releaseSeat = load("release_seat.lua");
        this.adjustSeatStock = load("adjust_seat_stock.lua");
    }

    /**
     * Takes one seat of a session for a member and records the member, in one atomic step.
     *
     * @param courtSessionId the session to book
     * @param memberId       the member taking the seat
     * @return what happened; only {@link Reservation#RESERVED} wrote anything
     */
    public Reservation reserve(Long courtSessionId, Long memberId) {
        long result = run(reserveSeat, keysOf(courtSessionId), memberId.toString());
        if (result == NO_SEAT_LEFT) {
            return Reservation.NO_SEAT_LEFT;
        }
        if (result == ALREADY_RESERVED) {
            return Reservation.ALREADY_RESERVED;
        }
        if (result == NOT_INITIALIZED) {
            return Reservation.NOT_INITIALIZED;
        }
        return Reservation.RESERVED;
    }

    /**
     * Initializes the seat stock and member set of a session, unless another request already did.
     *
     * @param courtSessionId the session id
     * @param remainingSeats seats left according to the database
     * @param memberIds      members who already booked the session according to the database
     * @return {@code true} if this call initialized the stock, {@code false} if it already existed
     */
    public boolean initialize(Long courtSessionId, int remainingSeats, Collection<Long> memberIds) {
        List<Object> args = new ArrayList<>(memberIds.size() + 1);
        args.add(Integer.toString(remainingSeats));
        memberIds.forEach(memberId -> args.add(memberId.toString()));
        return run(initSeatStock, keysOf(courtSessionId), args.toArray()) == DONE;
    }

    /**
     * Undoes a reservation whose database write failed: removes the member and, if asked and the
     * stock still exists, gives the seat back.
     *
     * @param courtSessionId the session id
     * @param memberId       the member whose reservation is undone
     * @param returnSeat     whether the seat goes back to the stock
     * @return {@code true} if the seat was given back
     */
    public boolean release(Long courtSessionId, Long memberId, boolean returnSeat) {
        return run(releaseSeat, keysOf(courtSessionId), memberId.toString(),
                returnSeat ? RETURN_SEAT : KEEP_SEAT) == DONE;
    }

    /**
     * Adds a capacity change to the seat stock of a session, if the stock exists.
     *
     * @param courtSessionId the session id
     * @param capacityDelta  the new capacity minus the old one
     * @return {@code true} if the stock existed and was adjusted
     */
    public boolean adjust(Long courtSessionId, int capacityDelta) {
        return run(adjustSeatStock, List.of(SeatStockKeys.stock(courtSessionId)),
                Integer.toString(capacityDelta)) == DONE;
    }

    private long run(String luaScript, List<Object> keys, Object... args) {
        return script.<Long>eval(RScript.Mode.READ_WRITE, luaScript, RScript.ReturnType.LONG, keys, args);
    }

    private static List<Object> keysOf(Long courtSessionId) {
        return List.of(SeatStockKeys.stock(courtSessionId), SeatStockKeys.members(courtSessionId));
    }

    private static String load(String fileName) {
        try {
            return new ClassPathResource(SCRIPT_DIRECTORY + fileName).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Lua script " + fileName, e);
        }
    }

    /** The outcome of {@link #reserve(Long, Long)}. */
    public enum Reservation {
        /** A seat was taken and the member recorded. */
        RESERVED,
        /** No seat is left; nothing was written. */
        NO_SEAT_LEFT,
        /** The member already holds a seat in the session; nothing was written. */
        ALREADY_RESERVED,
        /** The stock of the session has not been initialized yet; nothing was written. */
        NOT_INITIALIZED
    }
}
