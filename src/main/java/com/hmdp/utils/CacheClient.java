package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author codeep
 * @date 2023/8/2 9:06
 * @description:
 */

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意 Java 对象序列化为 json 并存储在 String 类型的 key 中，并且可以设置 TTL 过期时间
     * @param key 键
     * @param value 任意类型值
     * @param time 过期时间
     * @param timeUnit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        String json = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, json, time, timeUnit);
    }


    /**
     * 将任意 Java 对象序列化为 json 并存储在 string 类型的 key 中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     * @param key 键
     * @param value 任意类型值
     * @param time 过期时间
     * @param timeUnit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的 key 查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透的问题
     * @param prefix key 值前缀
     * @param id 查询的 id
     * @param type 返回的实体 class 类型
     * @param dbFallback 函数式编程：传入操作具体表的查函数
     * @param time 过期时间
     * @param timeUnit 过期单位
     * @param <R> 返回的实体类型
     * @param <ID> id 类型
     * @return 查询的实体对象
     */
    public <R, ID> R queryWithPassThrough(
            String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = prefix + id;
        // 1. 从 Redis 中查询实体缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中(会放行空字符串和真 null)
        if (StrUtil.isNotBlank(json)) {
            // 3. 命中则返回店铺信息
            return JSONUtil.toBean(json, type);
        }
        // 混入的空字符串
        if (json != null) {
            // 返回一个错误信息
            return null;
        }
        // 4. 未命中根据 id 查询数据库
        R r = dbFallback.apply(id);
        // 5. 判断实体是否存在
        if (r == null) {
            // 5.1 不存在返回 404 (缓存穿透更新：还需要将空值放入缓存)
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }
        // 5.2 存在将店铺数据存入 Redis，返回实体信息
        this.set(key, r, time, timeUnit);
        return r;
    }

    private static final ThreadPoolExecutor CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            5,
            10,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 根据指定的 key 查询缓存，并反序列化为指定类型，并且可以利用逻辑过期时间来解决缓存击穿问题
     * @param prefix key 值前缀
     * @param id 查询的 id
     * @param type 返回的实体 class 类型
     * @param dbFallback 函数式编程：传入操作具体表的查函数
     * @param time 过期时间
     * @param timeUnit 过期单位
     * @param <R> 返回的实体类型
     * @param <ID> id 类型
     * @return 查询的实体对象
     */
    public <R, ID> R queryWithLogicalExpire
            (String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = prefix + id;
        // 1. 从 Redis 中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中
        if (StrUtil.isBlank(json)) {
            // 3. 未命中则直接返回空，不用管啥缓存穿透，因为我热点数据会事先进行缓存预热“永久“存进 Redis
            return null;
        }
        // 4. 命中需要将 json 反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 注意不是默认的 Object，需要强转为本身的 JSONObject 类型(IDE 有提示，貌似 data 也可以转为 String)
        Object data = redisData.getData();
        R r = JSONUtil.toBean((JSONObject) data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. 判断是否缓存过期
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 5.1 未过期直接返回店铺信息(这就是存 LocalDateTime 的好处，比较时间直接调用 isAfter、isBefore)
            return r;
        }
        // 5.2 过期则进行缓存重建
        // 6. 缓存重建
        // 7. 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 8. 判断获取互斥锁是否成功
        if (isLock) {
            // ================== DOUBLE CHECK =================
            // 这个 DoubleCheck 则和互斥锁相比的那个有点点区别(可以不用考虑查不到缓存穿透的问题)
            json = stringRedisTemplate.opsForValue().get(key);
            RedisData newRedisData = JSONUtil.toBean(json, RedisData.class);
            LocalDateTime newExpireTime = newRedisData.getExpireTime();
            if (LocalDateTime.now().isBefore(newExpireTime)) {
                // 未过期直接返回店铺信息(这就是存 LocalDateTime 的好处，比较时间直接调用 isAfter、isBefore)
                unlock(lockKey);
                return r;
            }
            // ================== DOUBLE CHECK =================
            // 也还是过期的话则还是老老实实缓存重建吧
            // 8.1 成功则开启从线程池中拿独立线程进行缓存重建（查数据库 -> 写入 Redis 并设置新的逻辑过期时间）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 缓存重建
                try {
                    // 查询数据库
                    R apply = dbFallback.apply(id);
                    // 写入缓存
                    this.setWithLogicalExpire(key, apply, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 9. 返回过期的店铺信息
        return r;
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 注意上面 stringRedisTemplate 给我们返回的是 boolean 的包装类型，而我们需要的是 基本类型，直接返回会拆箱，极易产生空指针
        // 所以使用 Hutool 的工具类：只有在包装类型为 True 的时候才返回 true，null 和 False 都是 false
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
