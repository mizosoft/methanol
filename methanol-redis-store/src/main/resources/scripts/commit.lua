local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local editorId = ARGV[1]
local metadata = ARGV[2]
local clientDataSize = tonumber(ARGV[3])
local staleEntryTimeToLiveSeconds = ARGV[4]
local commit = ARGV[5]

local commitData = clientDataSize >= 0

if redis.call('get', editorLockKey) ~= editorId then
    redis.call('unlink', wipDataKey)
    redis.log(redis.LOG_DEBUG, 'editor lock expired')
    return false
end
redis.call('unlink', editorLockKey)

if commit == '0' then
    redis.call('unlink', wipDataKey)
    redis.log(redis.LOG_DEBUG, 'edit discarded by client')
    return false
end

local wipDataSize = redis.call('strlen', wipDataKey)
if commitData and wipDataSize ~= clientDataSize then
    redis.call('unlink', wipDataKey)
    redis.log(redis.LOG_DEBUG, 'client & server disagree on written data size')
    return false
end

local entryVersion, dataVersion, dataSize = unpack(
        redis.call('hmget', entryKey, 'entryVersion', 'dataVersion', 'dataSize'))
local newEntryVersion = 1 + (entryVersion or 0)

local newDataSize, newDataVersion
if commitData then
    newDataSize = wipDataSize
    newDataVersion = 1 + (dataVersion or 0)

    redis.call('persist', wipDataKey)
    redis.call('rename', wipDataKey, entryKey .. ':data:' .. newDataVersion)

    -- If a previous entry existed, schedule its data stream for expiry. We don't immediately lose
    -- the entry as a concurrent reader might be in progress.
    if dataVersion then
        local dataKey = entryKey .. ':data:' .. dataVersion
        redis.call('expire', dataKey, staleEntryTimeToLiveSeconds)
        redis.call('rename', dataKey, dataKey .. ':stale')
    end
else
    redis.call('unlink', wipDataKey)
    if not entryVersion then
        -- This a new entry with an empty data stream.
        newDataSize = 0
        newDataVersion = 1
        redis.call('set', entryKey .. ':data:1', '')
    else
        -- Keep the data stream of the older entry.
        newDataSize = dataSize
        newDataVersion = dataVersion
    end
end

redis.call(
        'hset', entryKey,
        'metadata', metadata,
        'entryVersion', newEntryVersion,
        'dataVersion', newDataVersion,
        'dataSize', newDataSize)
redis.log(redis.LOG_WARNING, entryKey, unpack(
        redis.call('hmget', entryKey, 'metadata', 'entryVersion', 'dataVersion', 'dataSize')))
return true
