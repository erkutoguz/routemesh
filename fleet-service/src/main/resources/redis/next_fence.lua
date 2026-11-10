-- KEYS[1]: kurye başına fencing sayacı
-- ARGV[1]: veritabanındaki son fence_token (taban değer)
-- Redis sıfırlansa ya da geride kalsa bile token veritabanındaki değerin altına inmez.
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local floor = tonumber(ARGV[1])
if current < floor then
    redis.call('SET', KEYS[1], floor)
end
return redis.call('INCR', KEYS[1])
