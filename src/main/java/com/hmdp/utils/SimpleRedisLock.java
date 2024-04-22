package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";
    private String key;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String key, StringRedisTemplate stringRedisTemplate) {
        this.key = key;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程 id 作为 value 表示该线程获得了锁  集群模式或分布式系统还应该加上机器的表示 这里省略
        long threadId = Thread.currentThread().getId();

        // set key value nx ex time
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + key, "threadId = " + threadId, timeoutSec, TimeUnit.SECONDS);

        // 防止自动拆箱时空指针异常 不能直接返回 success
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + key);
    }
}
