local editorLockKey = KEYS[1]
local wipDataKey = KEYS[2]
local editorId = ARGV[1]
local editorLockTtlSeconds = tonumber(ARGV[2])
local dataCount = tonumber(ARGV[3])

local data = {}
for i = 1, dataCount do
    data[i] = ARGV[3 + i]
end

local ttl = redis.call('ttl', wipDataKey)
if ttl == -2 then
  redis.log(redis.LOG_WARNING, 'Editor lock expired')
  return -1
end

-- Update expiry if enough time has passed.
if ttl < 0.5 * editorLockTtlSeconds then
  -- Make sure we're extending the expiry of the correct editor lock.
  if redis.call('get', editorLockKey) ~= editorId then
    redis.log(redis.LOG_WARNING, 'Editor lock expired')
    redis.call('unlink', wipDataKey)
    return -1
  end

  local now = redis.call('time')[1]
  local expireAt = now + editorLockTtlSeconds
  if redis.call('expireat', editorLockKey, expireAt) == 0
      or redis.call('expireat', wipDataKey, expireAt) == 0 then
    return -1
  end
end

local sizeAfterAppend = 0
for _, d in ipairs(data) do
  sizeAfterAppend = redis.call('append', wipDataKey, d)
end
return sizeAfterAppend
