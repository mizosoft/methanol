local dataKey = KEYS[1]
local position = ARGV[1]
local limit = ARGV[2]
local staleEntryTtlSeconds = ARGV[3]

local staleDataKey = dataKey .. ':stale'
local range = redis.call('getrange', staleDataKey, position, limit)
if redis.call('ttl', staleDataKey) < 0.5 * staleEntryTtlSeconds then
    redis.call('expire', staleDataKey, staleEntryTtlSeconds)
end
return range
