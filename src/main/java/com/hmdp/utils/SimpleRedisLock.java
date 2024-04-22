package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        // 提前加载 lua 脚本
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private String key;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String key, StringRedisTemplate stringRedisTemplate) {
        this.key = key;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程 id 作为 value 表示该线程获得了锁  集群模式或分布式系统还应该加上机器的标识
        // 这里使用 uuid 作为 JVM id 和 线程id 拼接作为 标识
        long threadId = Thread.currentThread().getId();

        // set key value nx ex time
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + key, ID_PREFIX + threadId, timeoutSec, TimeUnit.SECONDS);

        // 防止自动拆箱时空指针异常 不能直接返回 success
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用 Lua 脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + key), ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 获取锁标识、线程标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + key);
//        long threadId = Thread.currentThread().getId();
//        // 判断标识是否一致
//        if ((ID_PREFIX + threadId).equals(id)) {
//            // 一致才释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + key);
//        }
//    }
}
