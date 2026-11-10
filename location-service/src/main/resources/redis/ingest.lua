-- KEYS: 1 geo, 2 kurye durumu, 3 lastseen, 4 kurye izleme kaydı
-- ARGV: 1 kurye, 2 epoch, 3 seq, 4 boylam, 5 enlem, 6 durum TTL (ms)
-- Dönüş: {0} eski paket, {1} kabul, {2, mesafe, gönderi, tür} kabul + yaklaşma tetiklendi
local storedEpoch = tonumber(redis.call('HGET', KEYS[2], 'epoch'))
local storedSeq   = tonumber(redis.call('HGET', KEYS[2], 'seq'))
local epoch = tonumber(ARGV[2])
local seq   = tonumber(ARGV[3])

if storedEpoch ~= nil then
  if epoch < storedEpoch or (epoch == storedEpoch and seq <= storedSeq) then
    return {0}
  end
end

redis.call('HSET', KEYS[2], 'epoch', epoch, 'seq', seq)
redis.call('PEXPIRE', KEYS[2], ARGV[6])
redis.call('GEOADD', KEYS[1], ARGV[4], ARGV[5], ARGV[1])

local t = redis.call('TIME')
redis.call('ZADD', KEYS[3], t[1] * 1000 + math.floor(t[2] / 1000), ARGV[1])

-- Canlı takip (SSE): konum, kuryenin kanalına yayınlanır. Aboneler "tür|epoch|seq|enlem|boylam[|hedef türü|mesafe]" okur.
local channel = 'courier:loc:' .. ARGV[1]
redis.call('PUBLISH', channel, 'L|' .. ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5] .. '|' .. ARGV[4])

local watch = redis.call('HMGET', KEYS[4], 'lat', 'lon', 'radius', 'state', 'shipment', 'kind')
if watch[4] == 'ARMED' then
  -- Redis GEODIST ile aynı yarıçap, find_nearest.lua mesafeleriyle tutarlı olsun
  local earth = 6372797.560856
  local lat1 = math.rad(tonumber(ARGV[5]))
  local lat2 = math.rad(tonumber(watch[1]))
  local dLat = lat2 - lat1
  local dLon = math.rad(tonumber(watch[2]) - tonumber(ARGV[4]))
  local a = math.sin(dLat / 2) ^ 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dLon / 2) ^ 2
  local distance = 2 * earth * math.asin(math.min(1, math.sqrt(a)))
  if distance <= tonumber(watch[3]) then
    redis.call('HSET', KEYS[4], 'state', 'FIRED')
    redis.call('PUBLISH', channel, 'A|' .. ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5] .. '|' .. ARGV[4] .. '|'
      .. watch[6] .. '|' .. string.format('%.3f', distance))
    return {2, string.format('%.3f', distance), watch[5], watch[6]}
  end
end

return {1}
