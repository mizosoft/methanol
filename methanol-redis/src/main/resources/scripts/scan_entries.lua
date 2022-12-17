local cursor = ARGV[1]
local pattern = ARGV[2]
local limit = ARGV[3]

local result = redis.call('scan', cursor, 'MATCH', pattern, 'COUNT', limit)
local entries = {}
for _, key in pairs(result[2]) do
  local entry = redis.call('hmget', key, 'metadata', 'dataSize', 'entryVersion', 'dataVersion');
  table.insert(entry, 1, key)
  table.insert(entries, entry)
end

return { result[1], entries }