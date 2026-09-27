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
local function byId(raw)
    if raw:find(string.char(92), 1, true) then return nil end
    local ok, value = pcall(cjson.decode, raw)
    if not ok or type(value) ~= 'table' or value.schema_version ~= 2 or not uuid(value.user_id)
        or type(value.created_at) ~= 'number' or value.created_at % 1 ~= 0
        or value.created_at <= 0 or value.created_at > 253402300799 then return nil end
    local count, seen = 0, {}
    for key in raw:gmatch('"([^"\\]+)"%s*:') do
        if seen[key] or (key ~= 'schema_version' and key ~= 'user_id' and key ~= 'created_at') then return nil end
        seen[key] = true
        count = count + 1
    end
    local fields = 0
    for _ in pairs(value) do fields = fields + 1 end
    if count ~= 3 or fields ~= 3 then return nil end
    return value
end
local user, digest = ARGV[1], ARGV[2]
if #KEYS ~= 2 or #ARGV ~= 2 or not uuid(user) or not hash(digest)
    or KEYS[1] ~= 'auth:session:{login}:by-id:' .. digest
    or KEYS[2] ~= 'auth:session:{login}:by-user:' .. user then return -2 end
local raw = redis.pcall('GET', KEYS[1])
if type(raw) == 'table' then return -2 end
if not raw then return 0 end
local record = byId(raw)
local idTtl = redis.call('PTTL', KEYS[1])
if not record or record.user_id ~= user or idTtl <= 0 then return -2 end
local indexed = redis.pcall('GET', KEYS[2])
if type(indexed) == 'table' then return -2 end
if indexed then
    local index = current(indexed)
    local userTtl = redis.call('PTTL', KEYS[2])
    if not index or userTtl <= 0 then return -2 end
    if index.session_hash == digest then
        if idTtl ~= userTtl then return -2 end
        redis.call('DEL', KEYS[1], KEYS[2])
        return 0
    end
end
-- A replaced ID cannot delete the new user's index.
redis.call('DEL', KEYS[1])
return 0
