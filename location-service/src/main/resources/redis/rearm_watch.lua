-- KEYS: 1 kurye izleme kaydı
-- ARGV: 1 gönderi, 2 tür
-- Kafka'ya yazılamayan tetiği geri alır; izleme hâlâ aynı gönderi ve türe aitse tekrar ARMED olur.
if redis.call('HGET', KEYS[1], 'shipment') == ARGV[1]
   and redis.call('HGET', KEYS[1], 'kind') == ARGV[2]
   and redis.call('HGET', KEYS[1], 'state') == 'FIRED' then
  redis.call('HSET', KEYS[1], 'state', 'ARMED')
  return 1
end
return 0
