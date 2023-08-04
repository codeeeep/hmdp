package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
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
    private RedisIdWorker redisIdWorker;

    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("活动时间未开始");
        }
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束");
        }
        // 3. 判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if (stock < 1) {
            return Result.fail("已抢完，下次再来吧");
        }
        // 4. 扣减库存(使用 CAS 法乐观锁解决超卖问题，stock 相当于版本号)
        boolean success = seckillVoucherService.update()
                // set stock = stock - 1
                .setSql("stock = stock - 1")
                // where id = ? and stock = ?
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("已抢完，下次再来吧");
        }
        // 5. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 5.1 订单 id
        long orderId = redisIdWorker.getId("order");
        voucherOrder.setId(orderId);
        // 5.2 用户 id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 5.3 代金券 id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 6. 返回订单 id
        return Result.ok(orderId);
    }
}
