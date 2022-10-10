-- TODO handle expiry.
if version ~= '-1' and redis.call('hget', KEYS[1], 'version') ~= ARGV[1] then
    return false
end

return redis.call('setnx', KEYS[1] .. ':data:wip')
