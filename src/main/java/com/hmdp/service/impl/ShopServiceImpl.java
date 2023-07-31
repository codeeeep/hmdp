package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 存储空值解决缓存穿透(代码存档以表纪念 :) )
        // Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺 id 不存在");
        }
        return Result.ok(shop);
    }


    /**
     * 缓存击穿解决方案
     * 使用互斥锁防止大量线程进行缓存重建时打到数据库
     * @param id 店铺 id
     * @return 店铺实体类
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从 Redis 中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中(会放行空字符串和真 null)
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中则返回店铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 混入的空字符串,判断是否为空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 4. 实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                // 4.3 失败则返回休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // ================== DOUBLE CHECK =================
            // 4.4.0 (DoubleCheck)获取成功锁还得取查查缓存是否有来决定是否进行缓存重建
            // 因为如果上个人重建完毕，你刚好抢到，你已经经过了缓存判定，属于漏网之鱼了，虽然只能来一个线程，但重建缓存又是是个耗时活，也要掐死
            // 1. 从 Redis 中查询商铺缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 2. 判断缓存是否命中(会放行空字符串和真 null)
            if (StrUtil.isNotBlank(shopJson)) {
                // 3. 命中则返回店铺信息
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            // 混入的空字符串,判断是否为空值
            if (shopJson != null) {
                // 返回一个错误信息
                return null;
            }
            // ================== DOUBLE CHECK =================

            // 4.4 成功则根据 id 查询数据库
            shop = getById(id);
            // =========== 因为这里查询很快，需要模拟重建时间延时很长的情况才可能发生缓存击穿 ============
            Thread.sleep(200);
            // 5. 判断店铺是否存在
            if (shop == null) {
                // 5.1 不存在返回 404 (缓存穿透更新：还需要将空值放入缓存)
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5.2 存在将店铺数据存入 Redis，返回店铺信息
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 释放互斥锁
            unlock(lockKey);
        }
        // 7. 返回
        return shop;
    }

    /**
     * archive：『缓存穿透存档』
     * 封装缓存穿透解决方案(已集成至『queryWithMutex』)
     * 利用缓存空值来解决缓存穿透
     * @param id 店铺 id
     * @return 店铺实体类
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从 Redis 中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中(会放行空字符串和真 null)
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中则返回店铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 混入的空字符串
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 4. 未命中根据 id 查询数据库
        Shop shop = getById(id);
        // 5. 判断店铺是否存在
        if (shop == null) {
            // 5.1 不存在返回 404 (缓存穿透更新：还需要将空值放入缓存)
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.2 存在将店铺数据存入 Redis，返回店铺信息
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺 id 不能为空");
        }
        // 1. 写入数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 获取锁
     * @param key 业务相关拼接键（别和存店铺实体那个键一样，这只是一个工具人）
     * @return 是否获取到锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 注意上面 stringRedisTemplate 给我们返回的是 boolean 的包装类型，而我们需要的是 基本类型，直接返回会拆箱，极易产生空指针
        // 所以使用 Hutool 的工具类：只有在包装类型为 True 的时候才返回 true，null 和 False 都是 false
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key 业务相关拼接键（别和存店铺实体那个键一样，这只是一个工具人）
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
