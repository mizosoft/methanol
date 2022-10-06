--if not redis.call('exists', KEYS[1] .. ':meta') then
--    return {}
--end

local metadata, dataSize, version = unpack(redis.call('hmget', KEYS[1] .. ':meta', 'metadata', 'dataSize', 'version'))
if not metadata then
    return {}
end

if not redis.call('exists', KEYS[1] .. ':data:' .. version) then
    redis.call('unlink', KEYS[1] .. ':meta')
    return {}
end

redis.call('hincrby', KEYS[1] .. ':meta', 'openCount', 1)

return { metadata, dataSize, version, KEYS[1] .. ':data:' .. version }
