package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorkder {
    // 基于 Redis 的全局唯一 ID 生成器


    // 初始时间戳（没必要，但是视频里就是这样写的）
    private static final long BEGIN_TIMESTAMP = 1704067200L;
    // 序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    // prefix: 因为基于 Redis 的 incr 实现唯一 id，需要有一个 key，用 prefix 区分不同业务
    public long nextId(String prefix) {
        // 1. 生成时间戳  当前时间 - 初始时间戳 单位:秒
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;


        // 2. 生成序列号
        // 不能永远使用同一个 key，需要在 key 里拼上日期信息
        //     1. 这样的话序列号每天都会重置 防止超出 32 位
        //     2. 方便通过 key 统计每天/每月/每年的订单量
        // 2.1 获取当前日期 精确到天
        //     为方便 Redis 客户端里查看 要加上 : 分隔
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长
        Long count = stringRedisTemplate.opsForValue().increment("incr:" + prefix + ":" + date);


        // 3. 拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);  // 初始时间：1704067200
    }
}
