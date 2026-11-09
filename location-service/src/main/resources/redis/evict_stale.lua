local t = redis.call('TIME')
local cutoff = t[1] * 1000 + math.floor(t[2] / 1000) - tonumber(ARGV[1])

local ids = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', cutoff, 'LIMIT', 0, tonumber(ARGV[2]))
for _, id in ipairs(ids) do
  redis.call('ZREM', KEYS[1], id)
  redis.call('ZREM', KEYS[2], id)
end

return #ids
