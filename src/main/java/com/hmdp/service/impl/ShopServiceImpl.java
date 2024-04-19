package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 1. 从 Redis 查询商铺缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中 并且不为空值 直接返回
            log.debug("命中 shop: {}", shopJson);
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 上面 isNotBlank 排除了 shopJson 为 null 或 空白字符串("", "  \t\n")
        // 这里还要判断命中的是否为空值 ""  如果是空值就不去查数据库 直接返回不存在
        if (shopJson != null) {
            log.debug("命中空值");
            return Result.fail("店铺不存在!");
        }

        // 4. 没命中 根据id查数据库
        Shop shop = getById(id);

        // 5. 数据库里没有 说明商铺不存在 返回错误
        if (shop == null) {
            // 将空值写入 Redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            log.debug("数据库中不存在，将空值写入Redis");
            return Result.fail("店铺不存在!");
        }

        // 6. 数据库里查到了 写缓存 然后返回商铺数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        log.debug("没命中 查询数据库 shop: {}", shop);
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 用事务保证数据库和缓存的操作同时成功或失败
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        // 1. 更新数据库
        updateById(shop);

        // 2. 删缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
