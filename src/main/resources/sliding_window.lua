--[[
  Sliding Window Log Rate Limiter — Lua script executed atomically in Redis.

  Why Lua?
    Redis executes Lua scripts atomically — no other command can run between
    ZADD and ZCOUNT, so there is no race condition even under 1,000+ concurrent
    requests hitting the same key.

  Algorithm: Sliding Window Log
    1. Remove all timestamps older than (now - window) from the sorted set.
    2. Count the remaining entries — these are the requests in the current window.
    3. If count < limit, add the current timestamp and ALLOW the request.
    4. If count >= limit, DENY the request and return the retry-after time.

  Args (ARGV):
    ARGV[1] = current timestamp in milliseconds
    ARGV[2] = window size in milliseconds
    ARGV[3] = max requests allowed in the window

  Keys (KEYS):
    KEYS[1] = Redis sorted set key, e.g. "rate_limit:client_id:api_key"

  Returns:
    {allowed, current_count, limit, retry_after_ms}
      allowed        = 1 (allow) or 0 (deny)
      current_count  = how many requests are in the window after this one
      limit          = the configured limit
      retry_after_ms = ms until the oldest entry expires (0 if allowed)
--]]

local key        = KEYS[1]
local now        = tonumber(ARGV[1])
local window_ms  = tonumber(ARGV[2])
local limit      = tonumber(ARGV[3])

local window_start = now - window_ms

-- Step 1: Remove entries outside the window (older than window_start)
redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

-- Step 2: Count requests currently in the window
local current_count = redis.call('ZCARD', key)

-- Step 3: Decide
if current_count < limit then
    -- Add this request's timestamp as both score and member
    -- Append a random suffix to member to handle duplicate timestamps
    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
    -- Set TTL so idle keys are cleaned up automatically
    redis.call('PEXPIRE', key, window_ms)
    return {1, current_count + 1, limit, 0}
else
    -- Get the oldest entry to calculate retry-after
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local retry_after_ms = 0
    if oldest and #oldest >= 2 then
        local oldest_ts = tonumber(oldest[2])
        retry_after_ms = (oldest_ts + window_ms) - now
    end
    return {0, current_count, limit, retry_after_ms}
end
