-- Both KEYS use the literal {login} hash tag. KEYS[2] is the by-id key read from the index.
local function hash(value)
    return type(value) == 'string' and #value == 64 and value:match('^[0-9a-f]+$') ~= nil
end
local function uuid(value)
    return type(value) == 'string' and #value == 36
        and value:match('^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
        and value == value:lower()
end
local user, digest = ARGV[1], ARGV[2]
if #ARGV ~= 2 or not uuid(user) or KEYS[1] ~= 'auth:session:{login}:by-user:' .. user then return -2 end
if digest == '' then
    if #KEYS ~= 1 then return -2 end
else
    if #KEYS ~= 2 or not hash(digest) or KEYS[2] ~= 'auth:session:{login}:by-id:' .. digest then return -2 end
    local raw = redis.pcall('GET', KEYS[2])
    if type(raw) == 'string' and not raw:find(string.char(92), 1, true) then
        local ok, record = pcall(cjson.decode, raw)
        if ok and type(record) == 'table' and record.user_id == user then redis.call('DEL', KEYS[2]) end
    end
end
redis.call('DEL', KEYS[1])
return 0
