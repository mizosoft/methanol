local editorLockKey = KEYS[1]
local wipDataKey = KEYS[2]
local data = ARGV[1]
local editorId = ARGV[2]
local editorLockTtlSeconds = tonumber(ARGV[3])

redis.log(redis.LOG_WARNING, editorId, redis.call('get', editorLockKey))

-- Update expiry if enough time has passed.
if redis.call('ttl', wipDataKey) < 0.7 * editorLockTtlSeconds then
  -- Make sure we're extending the expiry of the correct editor lock.
  if redis.call('get', editorLockKey) ~= editorId then
    redis.log(redis.LOG_WARNING, "expired editor lock ", editorId, wipDataKey)
    return -1
  end

  local now = redis.call('time')[1]
  local expireAt = now + editorLockTtlSeconds
  if redis.call('expireat', editorLockKey, expireAt) == 0
      or redis.call('expireat', wipDataKey, expireAt) == 0 then
    redis.log(redis.LOG_WARNING, "can't set expireat ", editorId, wipDataKey)
    return -1
  end
end
redis.log(redis.LOG_WARNING, "appended! ", data, " on ", wipDataKey)
return redis.call('append', wipDataKey, data)
