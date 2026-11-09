local limit = tonumber(ARGV[4])
local t = redis.call('TIME')
local cutoff = t[1] * 1000 + math.floor(t[2] / 1000) - tonumber(ARGV[5])

local found = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2],
                         'BYRADIUS', ARGV[3], 'm', 'ASC', 'WITHDIST')

local result = {}
local count = 0
for _, item in ipairs(found) do
  local lastSeen = tonumber(redis.call('ZSCORE', KEYS[2], item[1]))
  if lastSeen ~= nil and lastSeen >= cutoff then
    result[#result + 1] = item[1]
    result[#result + 1] = item[2]
    count = count + 1
    if count >= limit then
      break
    end
  end
end

return result
