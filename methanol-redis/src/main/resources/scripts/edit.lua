local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local editorId = ARGV[1]
local targetEntryVersion = ARGV[2]
local editorLockInactiveTtlSeconds = ARGV[3]

if targetEntryVersion ~= '-1' and redis.call('hget', entryKey, 'entryVersion') ~= targetEntryVersion then
  return false
end

if not redis.call('set', editorLockKey, editorId, 'nx', 'ex', editorLockInactiveTtlSeconds) then
  return false
end
redis.call('set', wipDataKey, '', 'ex', editorLockInactiveTtlSeconds)
return true
