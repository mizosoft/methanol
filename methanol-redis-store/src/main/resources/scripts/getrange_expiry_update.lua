local dataKey = KEYS[1]
local position = ARGV[1]
local limit = ARGV[2]
local expiryMillis = ARGV[3]

local range = redis.call('getrange', dataKey, position, limit)
redis.call('pexpire', dataKey, expiryMillis)
return range
