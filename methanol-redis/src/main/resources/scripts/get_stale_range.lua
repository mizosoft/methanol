local dataKey = KEYS[1]
local position = ARGV[1]
local limit = ARGV[2]
local staleEntryInactiveTtlSeconds = ARGV[3]

local staleDataKey = dataKey .. ':stale'
redis.call('expire', staleDataKey, staleEntryInactiveTtlSeconds)
return redis.call('getrange', staleDataKey, position, limit)
