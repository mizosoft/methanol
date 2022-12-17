local cursor = ARGV[1]
local pattern = ARGV[2]
local limit = ARGV[3]

local result = redis.call('scan', cursor, 'MATCH', pattern, 'COUNT', limit)
local entries = {}
for _, entryKey in pairs(result[2]) do
  local entry = redis.call('hmget', entryKey, 'metadata', 'dataSize', 'entryVersion', 'dataVersion')
  table.insert(entry, 1, entryKey)
  table.insert(entries, entry)
end

return { result[1], entries }