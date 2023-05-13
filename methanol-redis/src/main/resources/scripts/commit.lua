local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local editorId = ARGV[1]
local metadata = ARGV[2]
local clientDataSize = tonumber(ARGV[3]) -- < 0 to not commit the data stream.
local staleEntryTtlSeconds = ARGV[4]
local commit = ARGV[5]
local clockKey = ARGV[6]

local commitData = clientDataSize >= 0

if redis.call('get', editorLockKey) ~= editorId then
  redis.call('unlink', wipDataKey)
  redis.log(redis.LOG_WARNING, 'editor lock expired')
  return { 0, 'editor lock expired' }
end
redis.call('unlink', editorLockKey)

if commit == '0' then
  redis.call('unlink', wipDataKey)
  redis.log(redis.LOG_NOTICE, 'edit discarded by client')
  return { 0, 'edit discarded by client' }
end

-- Make sure client & server agree on written data size.
local wipDataSize = redis.call('strlen', wipDataKey)
if wipDataSize ~= clientDataSize and (commitData or wipDataSize ~= 0) then
  redis.call('unlink', wipDataKey)
  redis.log(redis.LOG_WARNING, 'client & server disagree on written data size')
  return { 0, "entry size inconsistency" }
end

local nextVersion
if (clockKey ~= '') then
  nextVersion = function()
    return redis.call('incr', clockKey)
  end
else
  -- Rely on microseconds in current day for versioning. Still, this may cause an ABA problem in two scenarios:
  --   A entry is created, opened for reading, deleted, then created again, while the reader is
  --   still active, all in 1 microsecond.
  --   A reader is opened, then the entry is deleted and created again exactly a day later, while the
  --   reader is still active.
  -- Both cases can hardly occur in practice.
  nextVersion = function()
    local time = redis.call('time')
    local secondsInDay = time[1] % (60 * 60 * 24)
    local millisInDay = secondsInDay * 1000000 + time[2]
    return millisInDay
  end
end

local entryVersion, dataVersion, dataSize = unpack(
    redis.call('hmget', entryKey, 'entryVersion', 'dataVersion', 'dataSize'))
local newEntryVersion = 1 + (entryVersion or nextVersion())

local newDataSize, newDataVersion
if commitData then
  newDataSize = wipDataSize
  newDataVersion = 1 + (dataVersion or nextVersion())

  redis.call('persist', wipDataKey)
  redis.call('rename', wipDataKey, entryKey .. ':data:' .. newDataVersion)

  -- If a previous entry existed, schedule its data stream for expiry. We don't immediately lose
  -- the entry as a concurrent reader might be in progress.
  if dataVersion then
    local dataKey = entryKey .. ':data:' .. dataVersion
    redis.call('expire', dataKey, staleEntryTtlSeconds)
    redis.call('rename', dataKey, dataKey .. ':stale')
  end
else
  redis.call('unlink', wipDataKey)
  if entryVersion then
    -- Keep the data stream of the older entry.
    newDataSize = dataSize
    newDataVersion = dataVersion
  else
    -- This a new entry with an empty data stream.
    newDataSize = 0
    newDataVersion = 1
    redis.call('set', entryKey .. ':data:1', '')
  end
end

redis.call(
    'hset', entryKey,
    'metadata', metadata,
    'entryVersion', newEntryVersion,
    'dataVersion', newDataVersion,
    'dataSize', newDataSize)
return { 1 }
