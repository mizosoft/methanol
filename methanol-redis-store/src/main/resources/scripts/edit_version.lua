local version = redis.call('hget', KEYS[1], 'version')
if version ~= ARGV[1] then
    return false
end

return redis.call('setnx', KEYS[1] .. ':data:wip')
