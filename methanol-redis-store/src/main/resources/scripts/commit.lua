local metadata = ARGV[1]
local blockCountSoFar = ARGV[2]
local blockSize = ARGV[3]
local remainingBlockCount = ARGV[4]
local fields = { 'metadata', metadata, 'blockCount', blockCountSoFar + remainingBlockCount, 'blockSize', blockSize }

-- Add the remaining blocks as key-val pairs.
for i = 1, remainingBlockCount do
    table.insert(fields, blockCountSoFar + i - 1)
    table.insert(fields, ARGV[i + 4])
end

local wipEntryKey = KEYS[2]
local entryKeyPrefix = KEYS[1]
local updatedVersion = 1 + (redis.call('get', entryKeyPrefix .. ':version') or -1)

redis.call('hset', wipEntryKey, unpack(fields))
redis.call('rename', wipEntryKey, entryKeyPrefix .. ':' .. updatedVersion)
redis.call('set', entryKeyPrefix .. ':version', updatedVersion)

-- Delete the lock field carried over from the wip entry.
redis.call('hdel', entryKeyPrefix .. ':' .. updatedVersion, 'lock')

return updatedVersion
