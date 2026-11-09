-- M4D1 internal primitive: one bounded, non-expiring hash in one ticket hash slot.
-- Each mutation is one HSET of counters + receipt. Validate before any write;
-- Lua is not SQL rollback. Unexpected Redis/transport failures are UNKNOWN.
local tm=redis.call('TIME');local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
local function answer(code,s,r) return cjson.encode({code=code,snapshot=s or cjson.null,receipt=r or cjson.null,serverNow=now}) end
local function integer(v,lo,hi)
 local n=tonumber(v);if not n or n~=math.floor(n) or n<lo or n>hi then return nil end;return n
end
local function uuid(s)
 return type(s)=='string' and #s==36 and s:sub(9,9)=='-' and s:sub(14,14)=='-' and s:sub(19,19)=='-' and s:sub(24,24)=='-' and #s:gsub('-','')==32 and s:gsub('-',''):match('^[0-9a-f]+$')~=nil
end
if #KEYS~=1 then return answer('INVALID') end
local mode,g=ARGV[1],integer(ARGV[2],1,1000000000)
local arity={INIT=4,READ=2,OPEN=2,CLOSE=2,ROTATE=3,HOLD=7,LOOKUP=7,COMMIT=7,RELEASE=7}
if not g or not arity[mode] or #ARGV~=arity[mode] then return answer('INVALID') end
local base,limit
if mode=='INIT' or mode=='ROTATE' then
 base=integer(ARGV[3],0,1000000);limit=mode=='INIT' and integer(ARGV[4],1,4096) or nil
 if not base or (mode=='INIT' and not limit) then return answer('INVALID') end
end
local r
if #ARGV==7 then
 local qty,deadline=integer(ARGV[5],1,10),integer(ARGV[6],1,10000000000000)
 if not uuid(ARGV[3]) or not uuid(ARGV[4]) or not qty or not deadline or #ARGV[7]~=64 or not ARGV[7]:match('^[0-9a-f]+$') then return answer('INVALID') end
 r={generation=g,requestId=ARGV[3],owner=ARGV[4],quantity=qty,deadline=deadline,fingerprint=ARGV[7],status='HELD'}
end
local key=KEYS[1];local typ=redis.call('TYPE',key).ok
if typ~='none' and typ~='hash' then return answer('CORRUPT') end
if typ=='none' then
 if mode~='INIT' then return answer('MISSING') end
 redis.call('HSET',key,'_generation',g,'_phase','CLOSED','_baseline',base,'_available',base,'_held',0,'_committed',0,'_entries',0,'_limit',limit)
 return answer('OK',{generation=g,phase='CLOSED',baseline=base,available=base,held=0,committed=0,entries=0,limit=limit})
end
-- A TTL would silently erase both stock and deduplication history. Fail closed.
if redis.call('PTTL',key)~=-1 then return answer('CORRUPT') end
local v=redis.call('HMGET',key,'_generation','_phase','_baseline','_available','_held','_committed','_entries','_limit')
local s={generation=integer(v[1],1,1000000000),phase=v[2],baseline=integer(v[3],0,1000000),available=integer(v[4],0,1000000),held=integer(v[5],0,1000000),committed=integer(v[6],0,1000000),entries=integer(v[7],0,4096),limit=integer(v[8],1,4096)}
if not s.generation or (s.phase~='CLOSED' and s.phase~='OPEN' and s.phase~='FROZEN') or not s.baseline or not s.available or not s.held or not s.committed or not s.entries or not s.limit or s.entries>s.limit or s.baseline~=s.available+s.held+s.committed or redis.call('HLEN',key)~=8+s.entries then return answer('CORRUPT') end
if g~=s.generation then return answer('FENCED',s) end
if mode=='INIT' then
 if base~=s.baseline or limit~=s.limit then return answer('CONFLICT',s) end
 return answer('OK',s) -- idempotent seed never resets inventory or reopens a gate
end
if mode=='READ' then return answer('OK',s) end
if mode=='OPEN' or mode=='CLOSE' then
 -- Closing irreversibly freezes this generation; delayed OPEN cannot undo a recovery gate.
 if mode=='OPEN' and s.phase=='FROZEN' then return answer('CLOSED',s) end
 s.phase=mode=='OPEN' and 'OPEN' or 'FROZEN';redis.call('HSET',key,'_phase',s.phase);return answer('OK',s)
end
if mode=='ROTATE' then
 if s.phase=='OPEN' then return answer('CLOSED',s) end
 if s.held~=0 then return answer('ACTIVE_HOLDS',s) end
 if g==1000000000 then return answer('FULL',s) end
 s.generation=g+1;s.phase='CLOSED';s.baseline=base;s.available=base;s.committed=0
 redis.call('HSET',key,'_generation',s.generation,'_phase','CLOSED','_baseline',base,'_available',base,'_committed',0)
 return answer('OK',s) -- stays CLOSED; receipts/tombstones retained across generations
end
local field='r:'..r.requestId;local old=redis.call('HGET',key,field)
if old then
 if #old>512 then return answer('CORRUPT') end
 local ok,value=pcall(cjson.decode,old)
 if not ok or type(value)~='table' then return answer('CORRUPT') end
 local n=0;for _ in pairs(value) do n=n+1 end
 if n~=7 or not integer(value.generation,1,s.generation) or not uuid(value.requestId) or value.requestId~=r.requestId or not uuid(value.owner) or not integer(value.quantity,1,10) or not integer(value.deadline,1,10000000000000) or type(value.fingerprint)~='string' or #value.fingerprint~=64 or not value.fingerprint:match('^[0-9a-f]+$') or (value.status~='HELD' and value.status~='COMMITTED' and value.status~='RELEASED') then return answer('CORRUPT') end
 if value.generation~=g or value.owner~=r.owner or value.quantity~=r.quantity or value.deadline~=r.deadline or value.fingerprint~=r.fingerprint then return answer('CONFLICT',s) end
 r=value
elseif mode~='HOLD' then return answer('MISSING',s) end
if mode=='LOOKUP' or (mode=='HOLD' and old) then return answer('OK',s,r) end
if mode=='HOLD' then
 if s.phase~='OPEN' then return answer('CLOSED',s) end
 if r.deadline<=now or r.deadline>now+120000 then return answer('EXPIRED',s) end
 if s.entries>=s.limit then return answer('FULL',s) end
 if s.available<r.quantity then return answer('SOLD_OUT',s) end
 s.available=s.available-r.quantity;s.held=s.held+r.quantity;s.entries=s.entries+1
elseif mode=='COMMIT' or mode=='RELEASE' then
 local target=mode=='COMMIT' and 'COMMITTED' or 'RELEASED'
 if r.status==target then return answer('OK',s,r) end
 if r.status~='HELD' then return answer('TERMINAL',s,r) end
 if s.held<r.quantity then return answer('CORRUPT') end
 s.held=s.held-r.quantity;r.status=target
 if mode=='COMMIT' then s.committed=s.committed+r.quantity else s.available=s.available+r.quantity end
end
redis.call('HSET',key,field,cjson.encode(r),'_available',s.available,'_held',s.held,'_committed',s.committed,'_entries',s.entries)
return answer('OK',s,r)
