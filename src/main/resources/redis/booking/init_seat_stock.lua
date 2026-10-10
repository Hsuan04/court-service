-- Initializes the seat stock of a session from the database, unless another request already did.
-- Both keys are written together, so a booking never sees a stock without its member set.
-- KEYS[1]: remaining seats of the session
-- KEYS[2]: set of member ids holding a seat
-- ARGV[1]: remaining seats
-- ARGV[2..n]: member ids that already booked the session
-- Returns 1 if this call initialized the stock, or 0 if it already existed.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
redis.call('DEL', KEYS[2])
redis.call('SET', KEYS[1], ARGV[1])
for i = 2, #ARGV do
    redis.call('SADD', KEYS[2], ARGV[i])
end
return 1
