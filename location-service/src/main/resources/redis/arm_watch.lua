-- KEYS: 1 kurye izleme kaydı, 2 gönderi->kurye indeksi, 3 bitti işareti
-- ARGV: 1 gönderi, 2 kurye, 3 tür, 4 enlem, 5 boylam, 6 yarıçap (m), 7 satıcı, 8 sürüm, 9 TTL (ms)
-- Dönüş: 1 kuruldu, 0 yok sayıldı (gönderi bitmiş ya da olay eski/tekrar)
if redis.call('EXISTS', KEYS[3]) == 1 then
  return 0
end

local current = redis.call('HMGET', KEYS[1], 'shipment', 'version')
if current[1] == ARGV[1] and tonumber(current[2]) >= tonumber(ARGV[8]) then
  return 0
end

redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1],
  'shipment', ARGV[1], 'kind', ARGV[3], 'lat', ARGV[4], 'lon', ARGV[5],
  'radius', ARGV[6], 'merchant', ARGV[7], 'version', ARGV[8], 'state', 'ARMED')
redis.call('PEXPIRE', KEYS[1], ARGV[9])
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[9])
return 1
