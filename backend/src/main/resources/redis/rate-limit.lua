local t=redis.call('TYPE',KEYS[1]).ok
if t~='none' and t~='hash' then return -1 end
local tm=redis.call('TIME');local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
local capacity,period=tonumber(ARGV[1]),tonumber(ARGV[2])
local values=redis.call('HMGET',KEYS[1],'tokens','last')
local tokens=tonumber(values[1]) or capacity;local last=tonumber(values[2]) or now
-- Do not grant tokens for a backwards wall clock jump.
tokens=math.min(capacity,tokens+math.max(0,now-last)*capacity/period)
local delay=0
if tokens>=1 then tokens=tokens-1 else delay=math.ceil((1-tokens)*period/capacity) end
redis.call('HSET',KEYS[1],'tokens',tokens,'last',math.max(last,now));redis.call('PEXPIRE',KEYS[1],period*2)
return delay
