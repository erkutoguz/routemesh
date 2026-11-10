-- KEYS: 1 gönderi->kurye indeksi, 2 bitti işareti
-- ARGV: 1 gönderi, 2 bitti işareti TTL (ms), 3 kurye izleme kaydı anahtar öneki
-- Kurye izleme kaydı yalnızca hâlâ bu gönderiye aitse silinir (kurye başka gönderiye geçmiş olabilir).
-- Anahtar adı indeksten okunduğu için script yalnızca tek düğümlü Redis'te çalışır.
local courier = redis.call('GET', KEYS[1])
local removed = 0
if courier then
  local watchKey = ARGV[3] .. courier
  if redis.call('HGET', watchKey, 'shipment') == ARGV[1] then
    redis.call('DEL', watchKey)
    removed = 1
  end
  redis.call('DEL', KEYS[1])
end
redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
return removed
