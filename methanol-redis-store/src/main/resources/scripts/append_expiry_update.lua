local wipDataKey = KEYS[1]
local data = ARGV[1]
local expiryMillis = ARGV[2]

local range = redis.call('append', wipDataKey, data)
redis.call('pexpire', wipDataKey, expiryMillis)
return range
