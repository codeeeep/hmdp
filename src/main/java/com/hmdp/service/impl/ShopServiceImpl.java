package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        String key = CACHE_SHOP_KEY + id;
        // 1. 从 Redis 中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中则返回店铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 4. 未命中根据 id 查询数据库
        Shop shop = getById(id);
        // 5. 判断店铺是否存在
        if (shop == null) {
            // 5.1 不存在返回 404
            return Result.fail("店铺不存在");
        }
        // 5.2 存在将店铺数据存入 Redis，返回店铺信息
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}
