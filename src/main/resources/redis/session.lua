-- One user key, no intermediate deletion, and one final write per operation.
local function uuid4(value)
    return type(value) == 'string' and #value == 36
        and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-4%x%x%x%-[89ab]%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
        and value == string.lower(value)
end

local function decode(raw)
    local ok, value = pcall(cjson.decode, raw)
    if not ok or type(value) ~= 'table' or value.schema_version ~= 1
        or not uuid4(value.sid) or not uuid4(value.refresh_jti)
        or type(value.refresh_expires_at) ~= 'number'
        or value.refresh_expires_at % 1 ~= 0 or value.refresh_expires_at <= 0
        or value.refresh_expires_at > 253402300799 then
        return nil
    end
    local fields = 0
    for _ in pairs(value) do fields = fields + 1 end
    if fields ~= 4 then return nil end
    return value
end

local now = redis.call('TIME')
local nowSeconds = tonumber(now[1]) + tonumber(now[2]) / 1000000
local operation = ARGV[1]
local replacement = nil
if operation == 'replace' or operation == 'rotate' then
    replacement = decode(ARGV[2])
    if not replacement or replacement.refresh_expires_at <= nowSeconds then return -2 end
end

if operation == 'replace' then
    redis.call('SET', KEYS[1], ARGV[2], 'PXAT', string.format('%.0f', replacement.refresh_expires_at * 1000))
    return 1
end

local sid, jti, expiry = ARGV[3], ARGV[4], tonumber(ARGV[5])
if not uuid4(sid) or not expiry or expiry % 1 ~= 0 then return -2 end
if operation == 'revoke' and expiry <= nowSeconds then return 0 end
if operation ~= 'rotate' and operation ~= 'revoke' then return -2 end
if operation == 'rotate' and (not uuid4(jti) or replacement.sid ~= sid or replacement.refresh_jti == jti) then return -2 end

local raw = redis.pcall('GET', KEYS[1])
if type(raw) == 'table' then return -2 end
if not raw then return 0 end
local current = decode(raw)
if not current then return -2 end
if current.refresh_expires_at <= nowSeconds or current.sid ~= sid then return 0 end

if operation == 'revoke' then
    redis.call('DEL', KEYS[1])
    return 1
end
if current.refresh_jti ~= jti or current.refresh_expires_at ~= expiry then return 0 end
redis.call('SET', KEYS[1], ARGV[2], 'PXAT', string.format('%.0f', replacement.refresh_expires_at * 1000))
return 1
