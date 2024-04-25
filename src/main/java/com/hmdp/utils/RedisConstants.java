package com.hmdp.utils;

public class RedisConstants {
    // 一般 Redis key 用到的前缀和常量都会抽取出来，保存在一个工具类里实现复用

    public static final String LOGIN_CODE_KEY = "login:code:";    // 验证码  login:code:手机号
    public static final Long LOGIN_CODE_TTL = 2L;                 // 验证码有效期 2分钟
    public static final String LOGIN_USER_KEY = "login:token:";   // 获取用户信息 判断登录状态 login:token:<token值>
    public static final Long LOGIN_USER_TTL = 36000L;             // token有效期 10h 超过这个时间没有任何操作就会登出  一般这个时间会取长一点 比如几天

    public static final Long CACHE_NULL_TTL = 2L;                 // 空值有效期  为解决缓存穿透问题 如果数据库不存在 就在Redis里存个空值 避免下次又打到数据库

    public static final Long CACHE_SHOP_TTL = 30L;                // 商铺缓存有效期
    public static final String CACHE_SHOP_KEY = "cache:shop:";    // 商铺缓存 cache:shop:商铺id

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String FOLLOW_KEY = "follow:";            // 关注set
}
