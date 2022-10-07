local metadata, dataSize, version, dataVersion = unpack(
        redis.call('hmget', KEYS[1], 'metadata', 'dataSize', 'version', 'dataVersion'))

if not metadata then
    return {}
end

if not redis.call('exists', KEYS[1] .. ':data:' .. dataVersion) then
    redis.call('unlink', KEYS[1])
    return {}
end

redis.call('hincrby', KEYS[1], 'openCount', 1)

return { metadata, dataSize, version, KEYS[1] .. ':data:' .. dataVersion }
