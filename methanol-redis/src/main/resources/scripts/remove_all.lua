local staleEntryInactiveTtlSeconds = ARGV[1]

local removedAny = false
for _, entryKey in ipairs(KEYS) do
  local dataVersion = redis.call('hget', entryKey, 'dataVersion')
  local removedEntry = redis.call('unlink', entryKey) > 0
  removedAny = removedAny or removedEntry
  if dataVersion then
    local dataKey = entryKey .. ':data:' .. dataVersion
    if redis.call('expire', dataKey, staleEntryInactiveTtlSeconds) == 1 then
      redis.call('rename', dataKey, dataKey .. ':stale')
    end
  end

  -- Invalidate any ongoing edit for this entry.
  local currentEditorId = redis.call('getdel', entryKey .. ':editor')
  if currentEditorId then
    redis.call('unlink', entryKey .. ':data:wip:' .. currentEditorId)
    removedAny = true
  end
end
return removedAny
