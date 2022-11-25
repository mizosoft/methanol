local entryKey = KEYS[1]
local targetEntryVersion = ARGV[1]
local staleEntryTimeToLiveSeconds = ARGV[2]

local entryVersion, dataVersion = unpack(
        redis.call('hmget', entryKey, 'entryVersion', 'dataVersion'))
if targetEntryVersion ~= '-1' and targetEntryVersion ~= entryVersion then
    return false
end

local removed = redis.call('unlink', entryKey) > 0
if removed then
    local dataKey = entryKey .. ':data:' .. dataVersion
    redis.call('expire', dataKey, staleEntryTimeToLiveSeconds)
    redis.call('rename', dataKey, dataKey .. ':stale')
end

-- Invalidate any ongoing edit for this entry.
local currentEditorId = redis.call('getdel', entryKey .. ':editor')
if currentEditorId then
    redis.call('unlink', entryKey .. ':wip_data:' .. currentEditorId)
    removed = true
end
return removed