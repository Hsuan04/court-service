-- Undoes a reservation whose database write failed.
-- The seat is added back only if the stock still exists: INCR on a missing key would create a
-- stock of 1 that the database never granted.
-- KEYS[1]: remaining seats of the session
-- KEYS[2]: set of member ids holding a seat
-- ARGV[1]: member id
-- ARGV[2]: '1' to give the seat back, '0' to only remove the member
-- Returns 1 if the seat was given back, otherwise 0.
redis.call('SREM', KEYS[2], ARGV[1])
if ARGV[2] == '1' and redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
return 0
