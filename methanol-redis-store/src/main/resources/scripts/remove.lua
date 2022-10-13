local entryKey = KEYS[1]
local targetEntryVersion = ARGV[1]
local staleEntryExpiryMillis = ARGV[2]

local entryVersion, dataVersion = unpack(redis.call('hmget', entryKey, 'entryVersion', 'dataVersion'))
if targetEntryVersion ~= '-1' and targetEntryVersion ~= entryVersion then
    return false
end

local removed = redis.call('unlink', entryKey) > 0
if removed then
    local dataKey = entryKey .. ':data:' .. dataVersion
    redis.call('rename', dataKey, dataKey .. ':stale')
    redis.call('pexpire', dataKey .. ':stale', staleEntryExpiryMillis)
end
return removed
