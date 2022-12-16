local staleEntryTtlSeconds = ARGV[1]

for entryKey in KEYS do
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
end
