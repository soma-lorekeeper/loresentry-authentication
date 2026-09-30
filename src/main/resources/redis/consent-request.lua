local key = KEYS[1]
local mode = ARGV[1]
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if mode == 'create' then
    local value = cjson.encode({user_id=ARGV[2], terms_version_id=ARGV[3], created_at=now, expires_at=now+1800000})
    if not redis.call('SET', key, value, 'PX', 1800000, 'NX') then return nil end
    return value
end
local raw = redis.call('GET', key)
if not raw then return nil end
local value = cjson.decode(raw)
if type(value.user_id) ~= 'string' or type(value.terms_version_id) ~= 'string'
    or type(value.created_at) ~= 'number' or type(value.expires_at) ~= 'number'
    or value.expires_at - value.created_at ~= 1800000 or value.created_at > now then
    return redis.error_reply('Invalid consent state')
end
local ttl = redis.call('PTTL', key)
if ttl <= 0 or value.expires_at <= now then return nil end
if mode == 'refresh' then
    if value.user_id ~= ARGV[2] then return nil end
    value.terms_version_id = ARGV[3]
    raw = cjson.encode(value)
    redis.call('SET', key, raw, 'KEEPTTL', 'XX')
end
return raw
