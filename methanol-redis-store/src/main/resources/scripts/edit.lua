local entryKey = KEYS[1]
local editorLockKey = KEYS[2]
local wipDataKey = KEYS[3]
local targetEntryVersion = ARGV[1]
local editorId = ARGV[2]
local editorLockExpiryMillis = ARGV[3]

if targetEntryVersion ~= '-1' and redis.call('hget', entryKey, 'entryVersion') ~= targetEntryVersion then
    return false
end

if not redis.call('set', editorLockKey, editorId, 'nx', 'px', editorLockExpiryMillis) then
    return false
end

redis.call('set', wipDataKey, '', 'px', editorLockExpiryMillis)
return true
