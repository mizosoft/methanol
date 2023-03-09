local staleEntryTtlSeconds = ARGV[1]

local removedAny = false
for _, entryKey in pairs(KEYS) do
  local dataVersion = redis.call('hget', entryKey, 'dataVersion')
  redis.call('unlink', entryKey)
  if dataVersion then
    removedAny = true;
    local dataKey = entryKey .. ':data:' .. dataVersion
    if redis.call('expire', dataKey, staleEntryTtlSeconds) == 1 then
      redis.call('rename', dataKey, dataKey .. ':stale')
    end
  end

  -- Invalidate any ongoing edit for this entry.
  local currentEditorId = redis.call('getdel', entryKey .. ':editor')
  removedAny = true;
  if currentEditorId then
    redis.call('unlink', entryKey .. ':wip_data:' .. currentEditorId)
  end
end

return removedAny;
