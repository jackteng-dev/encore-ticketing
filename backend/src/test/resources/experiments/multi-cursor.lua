-- C3 TEST ONLY: original FROZEN ledger READ ONLY; sidecar is evidence, never spendable stock.
if #KEYS~=2 or #ARGV~=4 then return 'INVALID' end
local ok,p=pcall(cjson.decode,ARGV[1]);local ok2,prior=pcall(cjson.decode,ARGV[4])
if not ok or not ok2 or p.algorithm~='c3-frozen-evidence-v1' or #p.claims<1 or #p.claims>8 or #prior>16 then return 'INVALID' end
for _,k in ipairs(KEYS) do
 local typ=redis.call('TYPE',k).ok
 if typ=='none' then return 'MISSING' end
 if typ~='hash' or redis.call('PTTL',k)~=-1 then return 'CORRUPT' end
end
local v=redis.call('HMGET',KEYS[1],'_generation','_phase','_baseline','_available','_held','_committed','_entries','_limit')
for _,i in ipairs({1,3,4,5,6,7,8}) do
 local n=tonumber(v[i]);if not n or n~=math.floor(n) or n<0 or n>1000000000 then return 'CORRUPT' end
end
if tonumber(v[1])~=p.generation then return 'FENCED' end
if v[2]~='FROZEN' then return 'NOT_FROZEN' end
if tonumber(v[5])~=0 then return 'ACTIVE_HOLDS' end
if tonumber(v[3])~=p.baseline or tonumber(v[4])~=p.baseline-p.committed or tonumber(v[6])~=p.committed
 or tonumber(v[7])~=#p.claims or not tonumber(v[8]) or tonumber(v[8])<#p.claims
 or tonumber(v[8])>4096 or redis.call('HLEN',KEYS[1])~=8+#p.claims then return 'STOCK_CONFLICT' end
-- Check ALL original receipt identities and terminal decisions, not merely equal totals.
for _,claim in ipairs(p.claims) do
 local raw=redis.call('HGET',KEYS[1],'r:'..claim.requestId)
 if not raw then return 'RECEIPT_MISSING' end
 if #raw>512 then return 'CORRUPT' end
 local valid,r=pcall(cjson.decode,raw);if not valid or type(r)~='table' then return 'CORRUPT' end
 local n=0;for _ in pairs(r) do n=n+1 end
 if n~=7 then return 'CLAIM_CONFLICT' end
 for field,value in pairs(claim) do if r[field]~=value then return 'CLAIM_CONFLICT' end end
end
local c=redis.call('HMGET',KEYS[2],'_generation','_revision','_available','_last_request','_count')
if tonumber(c[1])~=p.generation then return 'FENCED' end
for _,marker in ipairs(prior) do
 if redis.call('HGET',KEYS[2],'a:'..marker.request)~=marker.digest then return 'COMMAND_CONFLICT' end
end
local last='NONE';if #prior>0 then last=prior[#prior].request end
local mark=redis.call('HGET',KEYS[2],'a:'..ARGV[2])
if mark then
 if mark~=ARGV[3] or tonumber(c[2])~=p.to or tonumber(c[3])~=p.afterAvailable
  or c[4]~=ARGV[2] or tonumber(c[5])~=#prior+1 or redis.call('HLEN',KEYS[2])~=6+#prior then return 'COMMAND_CONFLICT' end
 return 'REPLAY'
end
if tonumber(c[2])~=p.from then return 'CURSOR_GAP' end
if tonumber(c[3])~=p.beforeAvailable or c[4]~=last or tonumber(c[5])~=#prior
 or redis.call('HLEN',KEYS[2])~=5+#prior then return 'COMMAND_CONFLICT' end
-- The change is ONLY to the virtual PG evidence snapshot. It cannot fund a HOLD.
redis.call('HSET',KEYS[2],'_revision',p.to,'_available',p.afterAvailable,'_last_request',ARGV[2],
 '_count',#prior+1,'a:'..ARGV[2],ARGV[3])
return 'APPLIED'
