local version, dataVersion, openCount = unpack(
        redis.call('hmget', KEYS[1], 'version', 'dataVersion', 'openCount'))

if ARGV[1] ~= '-1' and ARGV[1] ~= version then
    return false
end

local keysToDelete = { KEYS[1] }

if dataVersion then
    if openCount == 0 then
        table.insert(keysToDelete, KEYS[1] .. ':data:' .. dataVersion)
    else
        -- TODO handle expiry.
        redis.call('rename', KEYS[1] .. ':data:' .. dataVersion, KEYS[1] .. ':data:' .. dataVersion .. ':stale')
    end
end

return redis.call('unlink', unpack(keysToDelete)) >= 1
