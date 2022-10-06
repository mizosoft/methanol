local metadata = ARGV[1]
local commitMetadata = ARGV[2] == '1'
local commitData = ARGV[3] == '1'

local version, dataSize, openCount = unpack(redis.call('hmget', KEYS[1] .. ':meta', 'version', 'dataSize', 'openCount'))

-- Must commit at least either of metadata or data.
if not commitMetadata and not commitData then
    return redis.error_reply('neither metadata nor data is to be committed')
end

local newVersion = 1 + (version or 0)

local newDataSize
if commitData then
    newDataSize = redis.call('strlen', KEYS[1] .. ':data:wip')
    redis.call('rename', KEYS[1] .. ':data:wip', KEYS[1] .. ':data:' .. newVersion)

    -- Delete the data stream of the previous entry if no one's viewing it.
    if version and openCount == 0 then
        redis.call('unlink', KEYS[1] .. ':data:' .. version)
    end
else
    redis.call('unlink', KEYS[1] .. ':data:wip')

    if not version then
        -- This is a new entry with no data stream.
        newDataSize = 0
    else
        -- Keep the data stream of the older entry.
        newDataSize = dataSize
        if openCount == 0 then
            redis.call('rename', KEYS[1] .. ':data:' .. version, KEYS[1] .. ':data:' .. newVersion)
        else
            redis.call('copy', KEYS[1] .. ':data:' .. version, KEYS[1] .. ':data:' .. newVersion)
        end
    end
end

local updatedFields = { 'version', newVersion, 'dataSize', newDataSize, 'openCount', 0 }
if commitMetadata then
    table.insert(updatedFields, 'metadata')
    table.insert(updatedFields, metadata)
end

redis.call('hset', KEYS[1] .. ':meta', unpack(updatedFields))

return redis.status_reply("commit entry: " .. newVersion)

--if version and openCount > 0 then
--    redis.call('expire', KEYS[1] .. ':data:' .. version, 120)
--end
