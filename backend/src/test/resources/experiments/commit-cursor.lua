-- Test-only two-key checkpoint. Never changes the original ledger or opens a gate.
-- KEYS share a ticket hash tag; not yet Redis Cluster tested.
local function integer(v,lo,hi)
 local n=tonumber(v);if not n or n~=math.floor(n) or n<lo or n>hi then return nil end;return n
end
if #KEYS~=2 or #ARGV~=11 then return 'INVALID' end
local g=integer(ARGV[1],1,1000000000)
local before=integer(ARGV[2],1,1000000000000);local after=integer(ARGV[3],1,1000000000000)
local qty=integer(ARGV[7],1,10);local deadline=integer(ARGV[8],1,10000000000000)
local baseline=integer(ARGV[10],1,1000000)
if not g or not before or not after or after~=before+1 or not qty or not deadline or not baseline then return 'INVALID' end
for _,key in ipairs(KEYS) do
 local typ=redis.call('TYPE',key).ok
 if typ=='none' then return 'MISSING' end
 if typ~='hash' or redis.call('PTTL',key)~=-1 then return 'CORRUPT' end
end
local v=redis.call('HMGET',KEYS[1],'_generation','_phase','_baseline','_available','_held','_committed','_entries','_limit')
local available=integer(v[4],0,1000000);local held=integer(v[5],0,1000000)
local committed=integer(v[6],0,1000000);local entries=integer(v[7],0,4096);local lim=integer(v[8],1,4096)
if not available or not held or not committed or not entries or not lim or entries>lim
 or tonumber(v[3])~=available+held+committed or redis.call('HLEN',KEYS[1])~=8+entries then return 'CORRUPT' end
if tonumber(v[1])~=g then return 'FENCED' end
if v[2]~='FROZEN' then return 'NOT_FROZEN' end
if held~=0 then return 'ACTIVE_HOLDS' end
-- This bounded proof covers exactly ONE registered receipt in an initially empty epoch.
if tonumber(v[3])~=baseline or available~=baseline-qty or committed~=qty or entries~=1 then return 'STOCK_CONFLICT' end
local raw=redis.call('HGET',KEYS[1],'r:'..ARGV[5])
if not raw then return 'RECEIPT_MISSING' end
if #raw>512 then return 'CORRUPT' end
local ok,r=pcall(cjson.decode,raw);if not ok or type(r)~='table' then return 'CORRUPT' end
local fields=0;for _ in pairs(r) do fields=fields+1 end
if fields~=7 or r.generation~=g or r.requestId~=ARGV[5] or r.owner~=ARGV[6]
 or r.quantity~=qty or r.deadline~=deadline or r.fingerprint~=ARGV[9] then return 'CLAIM_CONFLICT' end
if r.status~='COMMITTED' then return 'DECISION_CONFLICT' end
local c=redis.call('HMGET',KEYS[2],'_generation','_revision','_last_request')
local revision=integer(c[2],1,1000000000000)
if tonumber(c[1])~=g then return 'FENCED' end
if not revision then return 'CORRUPT' end
local mark=redis.call('HGET',KEYS[2],'a:'..ARGV[4])
if mark then
 if redis.call('HLEN',KEYS[2])~=4 or mark~=ARGV[11] or revision~=after or c[3]~=ARGV[4] then return 'COMMAND_CONFLICT' end
 return cjson.encode({code='REPLAY',receipt=raw})
end
if redis.call('HLEN',KEYS[2])~=3 or c[3]~='NONE' then return 'COMMAND_CONFLICT' end
if revision~=before then return 'CURSOR_GAP' end
-- A COMMITTED claim already represents the journal's negative available delta.
-- Advance its evidence cursor; NEVER apply that delta to stock a second time.
redis.call('HSET',KEYS[2],'_revision',after,'_last_request',ARGV[4],'a:'..ARGV[4],ARGV[11])
return cjson.encode({code='APPLIED',receipt=raw})
