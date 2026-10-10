-- Applies a capacity change to the seat stock of a session, only if the stock exists. A missing
-- stock is left alone: the next booking initializes it from the database, which already has the
-- new capacity.
-- KEYS[1]: remaining seats of the session
-- ARGV[1]: new capacity minus old capacity
-- Returns 1 if the stock was adjusted, otherwise 0.
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('INCRBY', KEYS[1], ARGV[1])
return 1
