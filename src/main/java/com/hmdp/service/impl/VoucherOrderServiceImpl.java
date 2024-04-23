package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorkder;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorkder redisIdWorkder;


    // 这里还有一个 Spring 事务失效的问题，因为 @Transactional 实现事务是基于动态代理实现的
    // 而在同一个类内部调用事务方法，走的是 this.事务方法，而不是 代理对象.事务方法，会绕过动态代理类，所以事务会失效
    // 在类内部注入自己是网上找到的一种简单的解决方法，spring ioc 内部的三级缓存可以保证不会出现循环依赖问题
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取阻塞队列中的订单信息
                    log.debug("正在等待订单");
                    VoucherOrder voucherOrder = orderTasks.take();
                    log.debug("获取到异步订单: {}", voucherOrder);
                    // 2. 创建订单
                    voucherOrderService.handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.debug("处理订单异常", e);
                }
            }
        }
    }

    @Transactional
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 扣减库存
        boolean stockSuccess = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // set stock = stock - 1 where id = ? and stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)   // mysql 本身就会用行锁来保证 update 语句的原子性，确保起串行执行，所以只需要在 sql 里判断库存 > 0 即可解决超卖
                .update();
        if (!stockSuccess) {
            log.debug("扣减库存失败!");
            return;
        }

        // 创建订单
        boolean orderSuccess = save(voucherOrder);
        if (!orderSuccess) {
            log.debug("创建订单失败!");
            return;
        }
        log.debug("mysql 扣减库存 创建订单成功");
    }

    @PostConstruct  // Spring Bean生命周期的知识点
    private void init() {
        // 当前类初始化完毕后，就开启一个线程用于处理异步下单任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    static {
        // 提前加载 lua 脚本
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 执行 Lua 脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());

        // 2. 判断是否秒杀成功
        int r = result.intValue();
        // 2.1 返回非 0 说明秒杀失败，根据返回值返回错误信息
        if (r != 0) {
            if (r == 1) return Result.fail("库存不足");
            if (r == 2) return Result.fail("不能重复下单");
        }
        // 2.2 返回 0 说明秒杀成功，把下单信息保存到阻塞队列，执行异步下单流程
        // 生成订单id
        long orderId = redisIdWorkder.nextId("order");
        // 将下单信息（优惠券id、用户id、订单id）保存到阻塞队列，执行异步下单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);   // 订单id
        voucherOrder.setVoucherId(voucherId);  // 优惠券id
        voucherOrder.setUserId(userId);  // 用户id
        log.debug("添加订单到阻塞队列 订单id = {}", orderId);
        orderTasks.add(voucherOrder);

        // 3、返回订单id
        log.debug("秒杀成功，订单id = {}", orderId);
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 根据 id 查优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 2. 判断秒杀是否开始、是否已结束
//        LocalDateTime now = LocalDateTime.now();
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        if (beginTime.isAfter(now)) {
//            return Result.fail("秒杀尚未开始!");
//        }
//        if (endTime.isBefore(now)) {
//            return Result.fail("秒杀已经结束!");
//        }
//
//        // 3. 判断优惠券库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足!");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象  注意 key 里要包含 userId，同一个用户单独用一个锁
//        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        // 尝试获取锁
//        boolean isLock = lock.tryLock();  // 默认非阻塞  锁到期时间30s
//        if (!isLock) {
//            // 获取锁失败说明是来自同一个用户的并发请求
//            // 由于一个人最多下一单，所以这里没有必要进行重试，直接返回错误
//            return Result.fail("一个人只允许下一单");
//        }
//
//        // 保证异常时能手动释放锁
//        try {
//            return voucherOrderService.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    // 单机解决一人一单问题  保存注释
//    Long userId = UserHolder.getUser().getId();
    // 1. 为什么是 userId.toString().intern()
    //    我们的目的是保证：同一个用户的请求，单独使用一把锁
    //    不能直接用 synchronized(userId) 是因为每次请求都会创建一个新的 Long 对象，这样锁的就不是同一个对象了
    //    而 intern 方法是从常量池里获取字符串对象，如果常量池没有，就创建一个并返回其引用
    //    也就是说，相同用户的不同请求，能从常量池中拿到相同的字符串对象，即 userId 值相同，用的就是同一把锁
    // 2. 为什么要提取成函数再加 synchronized ? 不能直接用 synchronized 吗 ?
    //    首先这个方法原来是加了 @Transactional 的，因为扣减库存和创建订单需要放到一个事务里 保证同时成功或同时失败
    //    而在 @Transactional 函数内部加锁，可能会导致：
    //    锁释放了，但是事务还没提交，即新增的订单还没有实际写到数据库里，此时其他线程拿到锁后开始执行，去查询订单，发现不存在，于是就会导致重复下单
    //    所以要提取出来，写成函数，在函数外部加锁，这样就能保证：
    //    当其他线程获取到锁后，事务已经提交，即新增订单已经实际写到数据库里了，此时就不会查到订单不存在
//    synchronized (userId.toString().intern()) {
//        return voucherOrderService.createVoucherOrder(voucherId);
//    }

    @Transactional  // 扣减库存和创建订单需要放到一个事务里  保证同时成功或同时失败（要么全部执行成功，要么全部不执行）
    public Result createVoucherOrder(Long voucherId) {
        // 4. 一人限一单
        Long userId = UserHolder.getUser().getId();

        // 4.1 查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 4.2 判断是否存在
        if (count > 0) {
            // 用户已经抢到优惠券了，不能再多抢了
            return Result.fail("用户已经购买过了");
        }

        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // set stock = stock - 1 where id = ? and stock > 0
                .eq("voucher_id", voucherId)
                .gt("stock", 0)   // mysql 本身就会用行锁来保证 update 语句的原子性，确保起串行执行，所以只需要在 sql 里判断库存 > 0 即可
                .update();
        if (!success) {
            log.debug("扣减失败!");
            return Result.fail("扣减失败!");
        }

        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorkder.nextId("order");
        voucherOrder.setId(orderId);   // 订单id
        voucherOrder.setVoucherId(voucherId);  // 优惠券id
        voucherOrder.setUserId(userId);  // 用户id
        save(voucherOrder);

        // 7. 返回订单id
        return Result.ok(orderId);
    }
}
