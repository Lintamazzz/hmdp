package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorkder;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorkder redisIdWorkder;

    @Override
    @Transactional  // 同时更新两张表 失败时回滚？
    public Result seckillVoucher(Long voucherId) {
        // 1. 根据 id 查优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 判断秒杀是否开始、是否已结束
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        if (beginTime.isAfter(now)) {
            return Result.fail("秒杀尚未开始!");
        }
        if (endTime.isBefore(now)) {
            return Result.fail("秒杀已经结束!");
        }

        // 3. 判断优惠券库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }

        // 4. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).update();
        if (!success) {
            return Result.fail("扣减失败!");
        }

        // 5. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorkder.nextId("order");
        voucherOrder.setId(orderId);   // 订单id
        voucherOrder.setVoucherId(voucherId);  // 优惠券id
        voucherOrder.setUserId(UserHolder.getUser().getId());  // 用户id
        save(voucherOrder);

        // 6. 返回订单id
        return Result.ok(orderId);
    }
}
