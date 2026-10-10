-- Takes one seat of a session for a member, as one atomic step: no other command can run between
-- the checks and the writes, so two requests can never both take the last seat.
-- KEYS[1]: remaining seats of the session
-- KEYS[2]: set of member ids holding a seat
-- ARGV[1]: member id
-- Returns the seats left after this one, -1 if no seat is left, -2 if the member already holds a
-- seat, or -3 if the seat stock is not initialized yet.
local stock = redis.call('GET', KEYS[1])
if not stock then
    return -3
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end
if tonumber(stock) <= 0 then
    return -1
end
redis.call('SADD', KEYS[2], ARGV[1])
return redis.call('DECR', KEYS[1])
