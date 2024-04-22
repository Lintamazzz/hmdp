package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * 尝试的含义是非阻塞，即立刻返回结果，获取成功就返回 true，获取失败就返回 false，不会阻塞等待
     * @param timeoutSec  锁的到期时间 过期后自动释放保证安全性
     * @return true 表示获取锁成功  false 表示获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
