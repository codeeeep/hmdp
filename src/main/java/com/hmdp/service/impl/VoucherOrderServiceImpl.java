package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        // TODO:获取锁, 为避免死锁设置了超时时间，按道理应该是业务时间的 10 倍左右，但是因为后面要打断点，故我把时间设置长点
        boolean isLock = lock.tryLock(1200);
        // 判断是否获取锁成功
        if (!isLock) {
            // 失败则返回异常或者重试
            return Result.fail("请勿重复抢票");
        }
        try {
            // 获取代理对象（事务）事务失效的几种情况
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 4. 一人一单
        Long userId = UserHolder.getUser().getId();
        // 4.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 4.2 判断是否存在
        if (count > 0) {
            return Result.fail("请勿重复购买");
        }
        // 4.3 不存在扣减库存
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
        voucherOrder.setUserId(userId);
        // 5.3 代金券 id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 6. 返回订单 id
        return Result.ok(orderId);
    }
}
