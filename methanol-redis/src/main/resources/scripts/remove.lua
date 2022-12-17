local entryKey = KEYS[1]
local targetEntryVersion = ARGV[1]
local staleEntryTtlSeconds = ARGV[2]

local entryVersion, dataVersion = unpack(
    redis.call('hmget', entryKey, 'entryVersion', 'dataVersion'))
if targetEntryVersion ~= '-1' and targetEntryVersion ~= entryVersion then
  return false
end

local removed = redis.call('unlink', entryKey) > 0
if removed and dataVersion then
  local dataKey = entryKey .. ':data:' .. dataVersion
  if redis.call('expire', dataKey, staleEntryTtlSeconds) == 1 then
    redis.call('rename', dataKey, dataKey .. ':stale')
  end
end

-- Invalidate any ongoing edit for this entry.
local currentEditorId = redis.call('getdel', entryKey .. ':editor')
if currentEditorId then
  redis.call('unlink', entryKey .. ':wip_data:' .. currentEditorId)
  removed = true
end
return removed
