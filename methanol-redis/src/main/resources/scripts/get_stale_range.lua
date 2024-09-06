local dataKey = KEYS[1]
local position = ARGV[1]
local limit = ARGV[2]
local staleEntryInactiveTtlSeconds = ARGV[3]

local staleDataKey = dataKey .. ':stale'
local ttl = redis.call('ttl', staleDataKey)
if ttl == -2 then
  return "" -- Entry doesn't exist (e.g. expired).
end

if ttl < 0.5 * staleEntryInactiveTtlSeconds then
  redis.call('expire', staleDataKey, staleEntryInactiveTtlSeconds)
end
return redis.call('getrange', staleDataKey, position, limit)
