--[[
  token_bucket.lua

  Atomically evaluates a token-bucket decision and updates bucket state.

  State lives in a single Valkey hash (KEYS[1]) with fields:
    tokens  -> current token balance (a number, stored as a string)
    ts      -> epoch-millis of the last refill/observation

  ARGV (all time is INJECTED; the script never reads the wall clock):
    ARGV[1] = capacity            (tokens the bucket can hold)
    ARGV[2] = refillPerSecond     (continuous refill rate)
    ARGV[3] = requestedTokens     (units this request requires)
    ARGV[4] = now                 (epoch-millis supplied by the caller)

  Returns a RESP array (ScriptOutputType.MULTI):
    [ allowed, remaining, resetAtMillis, retryAfterSeconds ]  (as strings)

  The script mirrors com.ratelimiter.service.algorithm.TokenBucket exactly, so
  the Lua and the Java spec agree — that is what makes the M5 randomized
  differential test meaningful. HSET inside the script is atomic: a request either
  fully executes or not at all, which is why concurrency can never over-drain.
]]

local capacity   = tonumber(ARGV[1])
local refillPerS = tonumber(ARGV[2])
local requested  = tonumber(ARGV[3])
local now        = tonumber(ARGV[4])

local tokens     = tonumber(redis.call('HGET', KEYS[1], 'tokens') or capacity)
local lastRefill = tonumber(redis.call('HGET', KEYS[1], 'ts') or now)

local refillPerMs = refillPerS / 1000.0
local elapsed     = math.max(0, now - lastRefill)
-- Clamp to [0, capacity] in both directions (same rationale as the Java code).
local available   = math.max(0, math.min(capacity, tokens + elapsed * refillPerMs))

local allowed   = 0
local remaining = available
if available >= requested then
  allowed   = 1
  remaining = available - requested
end

redis.call('HSET', KEYS[1], 'tokens', tostring(remaining), 'ts', tostring(now))

-- reset_at: epoch-millis when the bucket is next full (uses POST-decision balance).
local resetAt = 0
if refillPerS > 0 then
  local toFull = capacity - remaining
  if toFull <= 0 then
    resetAt = now
  else
    resetAt = now + math.ceil(toFull / refillPerMs)
  end
  -- rate == 0 means "never refills": resetAt stays 0 (no meaningful reset).
end

-- retry_after_seconds: whole seconds (rounded up, minimum 1) until available.
local retryAfter = 0
if allowed == 0 then
  if refillPerS > 0 then
    local missing = requested - available
    local millis  = (missing / refillPerMs) / 1000.0
    retryAfter = math.max(1, math.ceil(millis))
  else
    retryAfter = 2147483647 -- effectively never; edge case not exercised by tests
  end
end

return { tostring(allowed), tostring(remaining), tostring(resetAt), tostring(retryAfter) }
