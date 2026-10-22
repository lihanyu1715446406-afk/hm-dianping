package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final String QUEUE_NAME = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        try {
            // 消费组不存在时创建；已存在会抛 BUSYGROUP，忽略即可
            stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, GROUP_NAME);
        } catch (Exception e) {
            log.debug("消费组已存在: {}", e.getMessage());
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //处理异常消息（pending-list）
                    handlePendingList();
                }
            }
        }

        /**
         * 处理 pending-list 中已投递但未 ACK 的消息
         */
        private void handlePendingList() {
            while(true){
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                    );
                    //没有未确认的消息，退出
                    if(list==null||list.isEmpty()){
                        break;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    //获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常",e);
//                }
//
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
       if (!isLock) {
            //获取锁失败，返回错误或重试
           log.error("不允许重复下单!");
            return;
        }
       try{
           proxy.createVoucherOrder(voucherOrder);
       }finally {
           //释放锁
           lock.unlock();
       }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //获取代理对象(事务)，必须在发送消息前赋值，否则消费者可能拿不到 proxy
        proxy =(IVoucherOrderService) AopContext.currentProxy();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),
                String.valueOf(orderId)
        );
        //判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            //不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //返回订单id
        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        //判断结果是否为0
//        int r = result.intValue();
//        if(r!=0){
//            //不为0，代表没有购买资格
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //为0，有购买资格，把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder=new VoucherOrder();
//        //订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        //获取代理对象(事务)
//        proxy =(IVoucherOrderService) AopContext.currentProxy();
//        //返回订单id
//        return Result.ok(orderId);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始!");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //已经结束
//            return Result.fail("秒杀已经结束!");
//        }
//        //判断库存是否充足
//        if (voucher.getStock()<1) {
//            //库存不足
//            return  Result.fail("库存不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//       //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if (!isLock) {
//            //获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单！");
//        }
//       try{
//           //获取代理对象(事务)
//           IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
//           return proxy.createVoucherOrder(voucherId);
//       }finally {
//           //释放锁
//           lock.unlock();
//       }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //判断是否存在
        if(count>0){
            //用户已经购买过
            log.error("用户已经购买过一次！");
            return;
        }
        //扣减库存
        boolean success=seckillVoucherService.update()
                .setSql("stock=stock-1")// set stock=stock-1
                .eq("voucher_id", voucherId).gt("stock",0)// where id = ? and stock>0
                .update();
        if (!success) {
            //扣减失败
          log.error("库存不足");
          return;
        }
        //保存订单
        save(voucherOrder);
    }
}
