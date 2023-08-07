-- KEYS[1] 传锁的键， ARGV[1] 传线程标识
-- 获取锁中的线程标识，看看是否和传的线程标识一致
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    return redis.call('DEL', KEYS[1])
end
--  删除成功 Redis 返回 1，失败我就模仿返回 0
return 0