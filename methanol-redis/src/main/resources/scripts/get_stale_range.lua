local dataKey = KEYS[1]
local position = ARGV[1]
local limit = ARGV[2]
local staleEntryTtlSeconds = ARGV[3]

local staleDataKey = dataKey .. ':stale'
local ttl = redis.call('ttl', staleDataKey)
if ttl == -2 then
  return "" -- Entry doesn't exist (e.g. expired).
end

if ttl < 0.5 * staleEntryTtlSeconds then
  redis.call('expire', staleDataKey, staleEntryTtlSeconds)
end
return redis.call('getrange', staleDataKey, position, limit)
