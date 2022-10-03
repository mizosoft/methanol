local version = redis.call('get', KEYS[1] .. ':version')
if not version then
    return {}
end

local reply = redis.call('hmget', KEYS[1] .. ':' .. version, 'metadata', 'blockCount', 'blockSize')

--redis.call('hincrby', key, 'openCount', 1)

table.insert(reply, version)
return reply
