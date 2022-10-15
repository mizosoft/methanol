local wipDataKey = KEYS[1]
local data = ARGV[1]
local timeToLiveMillis = ARGV[2]

local range = redis.call('append', wipDataKey, data)
redis.call('pexpire', wipDataKey, timeToLiveMillis)
return range
