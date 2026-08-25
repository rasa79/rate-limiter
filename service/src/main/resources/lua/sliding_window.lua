--[[
  sliding_window.lua

  Log-based sliding-window rate limiting over a sorted set.

  State (a sorted set whose members are per-request events, scored by the event
  timestamp):
    KEYS[1] -> ZSET of event members (score = event epoch-ms)
    KEYS[2] -> a monotonic counter used to give each request a unique member

  ARGV (all time INJECTED):
    ARGV[1] = limit          (max events allowed in any window)
    ARGV[2] = windowMillis   (rolling window length)
    ARGV[3] = now            (epoch-ms supplied by the caller)

  Returns a RESP array (ScriptOutputType.MULTI):
    [ allowed, remaining, resetAtMillis, retryAfterSeconds ]  (as strings)

  An event is in the window at 'now' if t >= now - windowMillis; old events are
  pruned with ZREMRANGEBYSCORE before counting. The script mirrors
  com.ratelimiter.service.algorithm.SlidingWindowLog exactly, so the Java spec and
  the Lua agree (verified by a randomized differential test). Unique members make
  two events in the same millisecond count separately. The whole read-prune-count-
  write is atomic, so concurrency can never over-grant.
]]

local limit     = tonumber(ARGV[1])
local window    = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])

local zsetKey   = KEYS[1]
local seqKey    = KEYS[2]

-- Prune events strictly older than the window (keep t >= now-window).
redis.call('ZREMRANGEBYSCORE', zsetKey, '-inf', '(' .. (now - window))

local count = redis.call('ZCARD', zsetKey)
local allowed = 0
if count < limit then
  allowed = 1
  local seq = redis.call('INCR', seqKey)
  redis.call('ZADD', zsetKey, now, tostring(now) .. ':' .. tostring(seq))
  redis.call('PEXPIRE', zsetKey, math.max(window, 1))
  count = count + 1
end

local remaining = 0
if allowed == 1 then
  remaining = limit - count
end

local resetAt = now
local retryAfter = 0
local range = redis.call('ZRANGE', zsetKey, 0, 0, 'WITHSCORES')
if range[2] ~= nil then
  local oldest = tonumber(range[2])
  resetAt = oldest + window
  if allowed == 0 then
    local millis = (oldest + window) - now
    retryAfter = math.max(1, math.ceil(millis / 1000.0))
  end
end

return { tostring(allowed), tostring(remaining), tostring(resetAt), tostring(retryAfter) }
