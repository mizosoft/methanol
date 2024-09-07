local editorLockKey = KEYS[1]
local wipDataKey = KEYS[2]
local editorId = ARGV[1]
local editorLockInactiveTtlSeconds = tonumber(ARGV[2])
local dataCount = tonumber(ARGV[3])

local data = {}
for i = 1, dataCount do
    data[i] = ARGV[3 + i]
end

-- Make sure we're extending the expiry of the correct editor lock.
if redis.call('expire', wipDataKey, editorLockInactiveTtlSeconds) == 0
  or redis.call('get', editorLockKey) ~= editorId
  or redis.call('expire', editorLockKey, editorLockInactiveTtlSeconds) == 0 then
  redis.call('unlink', wipDataKey)
  return -1
end

local sizeAfterAppend = 0
for _, d in ipairs(data) do
  sizeAfterAppend = redis.call('append', wipDataKey, d)
end
return sizeAfterAppend
