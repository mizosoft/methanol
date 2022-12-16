local entryKey = KEYS[1]
local targetEntryVersion = ARGV[1]
local staleEntryTtlSeconds = ARGV[2]

local entryVersion, dataVersion = unpack(
    redis.call('hmget', entryKey, 'entryVersion', 'dataVersion'))
if not entryVersion or targetEntryVersion ~= '-1' and targetEntryVersion ~= entryVersion then
    return false
end

redis.call('unlink', entryKey)

local dataKey = entryKey .. ':data:' .. dataVersion
if redis.call('expire', dataKey, staleEntryTtlSeconds) == 1 then
    redis.call('rename', dataKey, dataKey .. ':stale')
end

-- Invalidate any ongoing edit for this entry.
local currentEditorId = redis.call('getdel', entryKey .. ':editor')
if currentEditorId then
    redis.call('unlink', entryKey .. ':wip_data:' .. currentEditorId)
end
return true
