local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local editorId = ARGV[1]
local clientDataSize = ARGV[2]
local metadata = ARGV[3]
local commitMetadata = ARGV[4] == '1'
local commitData = ARGV[5] == '1'
local staleEntryExpiryMillis = ARGV[6]

if redis.call('get', editorLockKey) ~= editorId then
    redis.call('unlink', wipDataKey)
    return redis.status_reply('edit discarded')
end
redis.call('unlink', editorLockKey)

if not commitMetadata and not commitData then
    redis.call('unlink', wipDataKey)
    return redis.status_reply('edit discarded')
end

if redis.call('strlen', wipDataKey) ~= tonumber(clientDataSize) then
    redis.call('unlink', wipDataKey)
    return redis.status_reply('edit discarded')
end

local entryVersion, dataVersion, dataSize = unpack(
        redis.call('hmget', entryKey, 'version', 'dataVersion', 'dataSize'))
local newEntryVersion = 1 + (entryVersion or 0)

local newDataSize, newDataVersion
if commitData then
    newDataSize = redis.call('strlen', wipDataKey)
    newDataVersion = 1 + (dataVersion or 0)

    redis.call('rename', wipDataKey, entryKey .. ':data:' .. newDataVersion)
    -- Remove the expiry carried over from editor's data.
    redis.call('persist', entryKey .. ':data:' .. newDataVersion)

    -- If a previous entry existed, schedule its data stream for expiry. We don't immediately lose
    -- the entry as a concurrent reader might be in progress.
    if dataVersion then
        local dataKey = entryKey .. ':data:' .. dataVersion
        redis.call('rename', dataKey, dataKey .. ':stale')
        redis.call('pexpire', dataKey .. ':stale', staleEntryExpiryMillis)
    end
else
    redis.call('unlink', wipDataKey)
    if not entryVersion then
        -- This is a new entry with no data stream.
        newDataSize = 0
        newDataVersion = 1
        redis.call('set', entryKey .. ':data:1', '')
    else
        -- Keep the data stream of the older entry.
        newDataSize = dataSize
        newDataVersion = dataVersion
    end
end

local updatedFields = { 'entryVersion', newEntryVersion, 'dataVersion', newDataVersion, 'dataSize', newDataSize }
if commitMetadata then
    table.insert(updatedFields, 'metadata')
    table.insert(updatedFields, metadata)
end
redis.call('hset', entryKey, unpack(updatedFields))
return redis.status_reply("commit entry: " .. newEntryVersion .. ', ' .. newDataVersion)
