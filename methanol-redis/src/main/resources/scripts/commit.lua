local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local editorId = ARGV[1]
local metadata = ARGV[2]
local clientDataSize = tonumber(ARGV[3]) -- < 0 to not commit the data stream.
local staleEntryInactiveTtlSeconds = ARGV[4]
local commit = ARGV[5]
local clockKey = ARGV[6]

if redis.call('get', editorLockKey) ~= editorId then
  redis.call('unlink', wipDataKey)
  return { 0, 'Editor lock expired' }
end
redis.call('unlink', editorLockKey) -- Release lock.

if commit == '0' then
  redis.call('unlink', wipDataKey)
  return { 0, 'Edit discarded by client' }
end

local wipDataSize
local commitData = clientDataSize >= 0
if commitData then
  -- Remove data expiry to not carry over to committed entry.
  if redis.call('persist', wipDataKey) == 0 and redis.call('exists', wipDataKey) == 0 then
    return { 0, 'Editor data removed or expired' }
  end

  -- Make sure client & server agree on written data size.
  wipDataSize = redis.call('strlen', wipDataKey)
  if wipDataSize ~= clientDataSize then
    redis.call('unlink', wipDataKey)

    local msg = 'Data size inconsistency; client: ' .. clientDataSize .. ', server: ' .. wipDataSize
    redis.log(redis.LOG_WARNING, msg)
    return { 0, msg }
  end
else
  redis.call('unlink', wipDataKey)
  wipDataSize = -1
end

local entryVersion, dataVersion, dataSize = unpack(
    redis.call('hmget', entryKey, 'entryVersion', 'dataVersion', 'dataSize'))
local newEntryVersion
if entryVersion then
  newEntryVersion = entryVersion + 1
else
  if clockKey ~= '' then
    newEntryVersion = redis.call('incr', clockKey)
  else
    -- Rely on microseconds since epoch for versioning. Still, this may cause an ABA problem in case
    -- an entry is created, opened for reading, deleted, then created again while the reader is still
    -- active, all in 1 microsecond. This can hardly occur in practice.
    local time = redis.call('time')
    newEntryVersion = time[1] * 1000 + math.floor(time[2] / 1000)
  end
end

local newDataSize, newDataVersion
if commitData then
  newDataSize = wipDataSize
  if dataVersion then
    -- If a previous entry existed, schedule its data stream for expiry. We don't immediately lose
    -- the entry as a concurrent reader might be in progress.
    local dataKey = entryKey .. ':data:' .. dataVersion
    redis.call('expire', dataKey, staleEntryInactiveTtlSeconds)
    redis.call('rename', dataKey, dataKey .. ':stale')
    newDataVersion = dataVersion + 1
  else
    newDataVersion = newEntryVersion
  end
  redis.call('rename', wipDataKey, entryKey .. ':data:' .. newDataVersion)
else
  if dataVersion then
    -- Keep the data stream of the older entry.
    newDataSize = dataSize
    newDataVersion = dataVersion
  else
    -- This a new entry with an empty data stream.
    newDataSize = 0
    newDataVersion = newEntryVersion
    redis.call('set', entryKey .. ':data:' .. newDataVersion, '')
  end
end

redis.call(
    'hset', entryKey,
    'metadata', metadata,
    'entryVersion', newEntryVersion,
    'dataVersion', newDataVersion,
    'dataSize', newDataSize)
return { 1 }
