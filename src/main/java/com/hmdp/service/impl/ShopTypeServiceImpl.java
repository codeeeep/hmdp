package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List getTypeList() {

        String key = "cache:shop_list";
        // 1. 从 Redis 中查询商铺缓存
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否命中
        if (StrUtil.isNotBlank(shopTypeListJson)) {
            // 3. 命中返回店铺信息
            return JSONUtil.toList(shopTypeListJson, ShopType.class);
        }
        // 4. 未命中直接查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 4.1 不存在直接返回 404
        if (shopTypeList.isEmpty()) {
            return null;
        }
        // 4.2 存在则将列表存入 Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));
        // 5. 返回店铺信息
        return shopTypeList;
    }
}
