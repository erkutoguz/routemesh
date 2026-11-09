local storedEpoch = tonumber(redis.call('HGET', KEYS[2], 'epoch'))
local storedSeq   = tonumber(redis.call('HGET', KEYS[2], 'seq'))
local epoch = tonumber(ARGV[2])
local seq   = tonumber(ARGV[3])

if storedEpoch ~= nil then
  if epoch < storedEpoch or (epoch == storedEpoch and seq <= storedSeq) then
    return 0
  end
end

redis.call('HSET', KEYS[2], 'epoch', epoch, 'seq', seq)
redis.call('PEXPIRE', KEYS[2], ARGV[6])
redis.call('GEOADD', KEYS[1], ARGV[4], ARGV[5], ARGV[1])

local t = redis.call('TIME')
redis.call('ZADD', KEYS[3], t[1] * 1000 + math.floor(t[2] / 1000), ARGV[1])

return 1
