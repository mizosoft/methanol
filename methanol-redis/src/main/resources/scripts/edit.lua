local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local targetEntryVersion = ARGV[1]
local editorId = ARGV[2]
local timeToLiveSeconds = ARGV[3]

if targetEntryVersion ~= '-1' and redis.call('hget', entryKey, 'entryVersion') ~= targetEntryVersion then
  return false
end

-- Make sure both editorLockKey & wipDataKey expire together.
local now = redis.call('time')[1]
local expireAt = now + timeToLiveSeconds
if not redis.call('set', editorLockKey, editorId, 'nx', 'exat', expireAt) then
  return false
end
redis.call('set', wipDataKey, '', 'exat', expireAt)
redis.log(redis.LOG_WARNING, "acquired editor for ", entryKey, editorLockKey, wipDataKey)
return true
