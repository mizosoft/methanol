local metadata = ARGV[1]
local commitMetadata = ARGV[2] == '1'
local commitData = ARGV[3] == '1'

if not commitMetadata and not commitData then
    redis.call('unlink', KEYS[1] .. ':data:wip')
    return redis.status_reply('edit discarded')
end

if not redis.exists(KEYS[1] .. ':data:wip') then
    return redis.status_reply('edit discarded')
end

local version, dataVersion, dataSize, openCount = unpack(
        redis.call('hmget', KEYS[1], 'version', 'dataVersion', 'dataSize', 'openCount'))
local newVersion = 1 + (version or 0)

local newDataSize, newDataVersion
if commitData then
    newDataSize = redis.call('strlen', KEYS[1] .. ':data:wip')
    newDataVersion = 1 + (dataVersion or 0)

    redis.call('rename', KEYS[1] .. ':data:wip', KEYS[1] .. ':data:' .. newDataVersion)

    if version then
        if openCount == 0 then
            redis.call('unlink', KEYS[1] .. ':data:' .. dataVersion)
        else
            -- TODO handle expiry of stale entries.
            redis.call('rename', KEYS[1] .. ':data:' .. dataVersion, KEYS[1] .. ':data:' .. dataVersion .. ':stale')
        end
    end
else
    redis.call('unlink', KEYS[1] .. ':data:wip')

    if not version then
        -- This is a new entry with no data stream.
        newDataSize = 0
        newDataVersion = 1
        redis.call('set', KEYS[1] .. ':data:1', '')
    else
        -- Keep the data stream of the older entry.
        newDataSize = dataSize
        newDataVersion = dataVersion
    end
end

local updatedFields = { 'version', newVersion, 'dataVersion', newDataVersion, 'dataSize', newDataSize, 'openCount', 0 }
if commitMetadata then
    table.insert(updatedFields, 'metadata')
    table.insert(updatedFields, metadata)
end

redis.call('hset', KEYS[1], unpack(updatedFields))

return redis.status_reply("commit entry: " .. newVersion .. ', ' .. newDataVersion)
