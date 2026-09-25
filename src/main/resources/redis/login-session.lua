-- Both KEYS use the literal {login} hash tag. Validate before either SET.
local function hash(value)
    return type(value) == 'string' and #value == 64 and value:match('^[0-9a-f]+$') ~= nil
end
local function uuid(value)
    return type(value) == 'string' and #value == 36
        and value:match('^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
        and value == value:lower()
end
local function current(raw)
    if raw:find(string.char(92), 1, true) then return nil end
    local ok, value = pcall(cjson.decode, raw)
    if not ok or type(value) ~= 'table' or value.schema_version ~= 2 or not hash(value.session_hash) then return nil end
    local count, seen = 0, {}
    for key in raw:gmatch('"([^"\\]+)"%s*:') do
        if seen[key] or (key ~= 'schema_version' and key ~= 'session_hash') then return nil end
        seen[key] = true
        count = count + 1
    end
    local fields = 0
    for _ in pairs(value) do fields = fields + 1 end
    if count ~= 2 or fields ~= 2 then return nil end
    return value
end
local user, digest = ARGV[1], ARGV[2]
if #KEYS ~= 2 or #ARGV ~= 2 or not uuid(user) or not hash(digest)
    or KEYS[1] ~= 'auth:session:{login}:by-id:' .. digest
    or KEYS[2] ~= 'auth:session:{login}:by-user:' .. user then return -2 end
local new = redis.pcall('GET', KEYS[1])
local old = redis.pcall('GET', KEYS[2])
if type(new) == 'table' or type(old) == 'table' then return -2 end
if old and (not current(old) or redis.call('PTTL', KEYS[2]) <= 0) then return -2 end
if new then return 0 end
local time = redis.call('TIME')
local seconds = tonumber(time[1])
local expires = seconds * 1000 + math.floor(tonumber(time[2]) / 1000) + 1209600000
if seconds <= 0 or expires > 253402300799999 then return -2 end
local record = cjson.encode({schema_version=2, user_id=user, created_at=seconds})
local index = cjson.encode({schema_version=2, session_hash=digest})
local expiry = string.format('%.0f', expires)
redis.call('SET', KEYS[1], record, 'PXAT', expiry)
redis.call('SET', KEYS[2], index, 'PXAT', expiry)
return expires
