-- One room, one hash slot; bounded queue/records. No ticket inventory here.
local kinds={'zset','zset','hash','string','zset'}
for i=1,5 do
 local t=redis.call('TYPE',KEYS[i]).ok
 if t~='none' and t~=kinds[i] then return cjson.encode({state='ERROR'}) end
end
local tm=redis.call('TIME');local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
local mode,member,request=ARGV[1],ARGV[2],ARGV[3]
local activeLimit,queueLimit=tonumber(ARGV[4]),tonumber(ARGV[5])
local permit,idle,maxWait,salesEnd=tonumber(ARGV[6]),tonumber(ARGV[7]),tonumber(ARGV[8]),tonumber(ARGV[9])
local function record(id)
 local raw=redis.call('HGET',KEYS[3],id)
 return raw and cjson.decode(raw) or nil
end
local function save(id,r) redis.call('HSET',KEYS[3],id,cjson.encode(r)) end
local function response(r)
 local position=0
 if r and r.state=='WAITING' then position=(redis.call('ZRANK',KEYS[1],member) or -1)+1 end
 return cjson.encode({state=r and r.state or 'NOT_JOINED',requestId=r and r.request or cjson.null,
  position=position,expiresAt=r and r.deadline or cjson.null,serverNow=now})
end
if mode=='CHECK' then
 local r=record(member)
 if r and r.state=='ADMITTED' and tonumber(r.deadline)>now and tonumber(redis.call('ZSCORE',KEYS[2],member) or 0)>now then return response(r) end
 return cjson.encode({state='DENIED',serverNow=now})
end
-- Garbage collect expired records after 30 minutes; no global scans/FLUSH commands.
for _,id in ipairs(redis.call('ZRANGEBYSCORE',KEYS[5],'-inf',now,'LIMIT',0,1000)) do
 redis.call('HDEL',KEYS[3],id);redis.call('ZREM',KEYS[5],id)
 redis.call('ZREM',KEYS[1],id);redis.call('ZREM',KEYS[2],id)
end
for _,id in ipairs(redis.call('ZRANGEBYSCORE',KEYS[2],'-inf',now,'LIMIT',0,220)) do
 local r=record(id)
 if r then r.state='EXPIRED';save(id,r) end
 redis.call('ZREM',KEYS[1],id);redis.call('ZREM',KEYS[2],id)
end
local r=record(member)
if mode=='JOIN' then
 -- Preserve retired IDs across explicit requeues; delayed old commands cannot resurrect.
 if r and r.retired and r.retired[request] then
  return response({state='EXPIRED',request=request,deadline=r.retired[request]})
 end
 if r and r.request~=request and r.state~='EXPIRED' then return cjson.encode({state='CONFLICT'}) end
 if not r or (r.state=='EXPIRED' and r.request~=request) then
  if redis.call('ZCARD',KEYS[1])>=queueLimit or (not r and redis.call('HLEN',KEYS[3])>=1000) then return cjson.encode({state='FULL'}) end
  local retired=r and r.retired or {}
  if r then
   local count=0;for _ in pairs(retired) do count=count+1 end
   if count>=20 then return cjson.encode({state='FULL'}) end
   retired[r.request]=r.deadline
  end
  local sequence=redis.call('INCR',KEYS[4])
  r={retired=retired,state='WAITING',request=request,joined=now,deadline=math.min(now+idle,now+maxWait,salesEnd)}
  save(member,r);redis.call('ZADD',KEYS[1],sequence,member);redis.call('ZADD',KEYS[2],r.deadline,member)
  redis.call('ZADD',KEYS[5],now+maxWait+1800000,member)
 end
end
-- Heartbeat only extends waiting liveness; it never extends an admitted permit.
if r and r.state=='WAITING' then
 r.deadline=math.min(now+idle,r.joined+maxWait,salesEnd)
 if r.deadline<=now then
  r.state='EXPIRED';redis.call('ZREM',KEYS[1],member);redis.call('ZREM',KEYS[2],member)
 else redis.call('ZADD',KEYS[2],r.deadline,member) end
 save(member,r)
end
local active=redis.call('ZCARD',KEYS[2])-redis.call('ZCARD',KEYS[1])
while active<activeLimit and now<salesEnd do
 local first=redis.call('ZRANGE',KEYS[1],0,0)[1]
 if not first then break end
 local head=record(first)
 if not head then return cjson.encode({state='ERROR'}) end
 head.state='ADMITTED';head.deadline=math.min(now+permit,salesEnd)
 save(first,head);redis.call('ZREM',KEYS[1],first);redis.call('ZADD',KEYS[2],head.deadline,first)
 active=active+1
end
-- All room keys have the same idle deadline, so sequence cannot reset under a live queue.
for i=1,5 do redis.call('PEXPIREAT',KEYS[i],now+3600000) end
return response(record(member))
