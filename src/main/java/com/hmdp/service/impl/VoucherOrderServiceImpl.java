package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *  服务实现类
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

    //类加载便初始化Lua脚本，不要每次释放锁再去加载
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua")); //到类路径下找脚本
        SECKILL_SCRIPT.setResultType(Long.class); //脚本执行后返回值类型
    }

    //线程池(只用一个线程(够用了)，开启一个子线程来将阻塞队列中的订单信息真正写入数据库中，而不让主线程来干，让主线程先返回，异步编程提高性能)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //当前类初始化完毕后执行
    private void init(){
        //当前类初始化后就开启一个线程去等待阻塞队列中的数据并执行
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //内部类(线程任务，从阻塞队列中获取信息创建订单写入数据库)
    /*private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的订单信息(若阻塞队列中没有信息则线程会阻塞等待)
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.info("处理订单异常", e);
                }
            }
        }
    }*/

    //内部类(线程任务，从消息队列中获取信息创建订单写入数据库)
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析消息中的订单信息
                    //String是消息id，然后三个key，三个value，有点像redis的hash结构，key中又有key再是value
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    //转成voucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK消息确认 SACK stream.orders g1 id(消息id)
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.info("处理订单异常", e);
                    //出异常了，消息没有被确认，还在pendingList中，需要从pendingList中取出来继续执行
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pendingList中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //如果获取失败，说明pendingList没有异常消息，结束循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    //String是消息id，然后三个key，三个value，有点像redis的hash结构，key中又有key再是value
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    //转成voucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK消息确认 SACK stream.orders g1 id(消息id)
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.info("处理pendingList订单异常", e);
                    //出异常了，消息没有被确认，还在pendingList中，需要从pendingList中取出来继续执行
                    // handlePendingList(); 不用再去递归了，while循环会再去判断
                    try {
                        Thread.sleep(20); //休眠一下，不要太频繁地去执行
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();

        //2.采用自定义锁方法
        // 其实这里可以不用加锁，因为前面在Redis中用Lua脚本确保了原子性，并且现在是单线程不会出现并发问题，这里加锁主要是以防万一，防止redis出现问题
        //方案一、创建锁对象(自定义锁)
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate); //用用户id做key，给同一个用户加锁，不同用户不加锁
        //方案二、创建锁对象(redisson自带的分布式锁)
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断是否获取锁成功
        if (!isLock){
            //获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }


    private IVoucherOrderService proxy; //代理对象

    //根据id查秒杀卷(方案三用Lua脚本+消息队列)
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisIdWorker.nextId("order");

        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, //Lua脚本
                Collections.emptyList(), //脚本中不用传key，但参数需要集合类型，这里为一个空集合，不要用null
                voucherId.toString(), userId.toString(), String.valueOf(orderId) //value参数
        );

        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            //2.1不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //3.获取代理对象，要在主线程中获取(Spring中要通过代理对象去调方法才具有事务功能，若不用代理对象而直接去调，则是目标对象调的方法，没有事务特性)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //4.返回订单id
        return Result.ok(orderId);
    }

    //根据id查秒杀卷(方案二用Lua脚本+阻塞队列)
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, //Lua脚本
                Collections.emptyList(), //脚本中不用传key，但参数需要集合类型，这里为一个空集合，不要用null
                voucherId.toString(), userId.toString() //value参数
        );

        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            //2.1不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3设置订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4设置用户id
        voucherOrder.setUserId(userId);
        //2.5设置秒杀卷id
        voucherOrder.setVoucherId(voucherId);
        //2.6放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.获取代理对象，要在主线程中获取(Spring中要通过代理对象去调方法才具有事务功能，若不用代理对象而直接去调，则是目标对象调的方法，没有事务特性)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //4.返回订单id
        return Result.ok(orderId);
    }*/

    //根据id查秒杀卷(方案一用java代码)
    /*@Override
    public Result seckillVoucher(Long voucherId) {

        //1.查询秒杀卷
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀未开始！");
        }

        ///3.判断秒杀是否已经过期
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已结束
            return Result.fail("秒杀已结束！");
        }

        //4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();

        *//*
            锁加在方法外面保证先提交事务再释放锁
            这里将userId转换为字符串是为了获得引用对象，具体的字符串内容并不是关键。因此，使用intern()方法将
            转换后的字符串添加到内部字符串池中，这样可以确保不同线程获取到的锁对象是同一个引用。
            使用synchronized这种方法在单体系统没问题，但在分布式系统或集群下存在问题
        *//**//*
        synchronized (userId.toString().intern()) { //给同一个用户id加锁，不同用户不加锁；intern从常量池中拿数据，保证同一个用户id只有一个对象
            //获取代理对象(Spring中要通过代理对象去调方法才具有事务功能，若不用代理对象而直接去调，则是目标对象调的方法，没有事务特性)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*

        //采用自定义锁方法
        //方案一、创建锁对象(自定义锁)
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate); //用用户id做key，给同一个用户加锁，不同用户不加锁
        //方案二、创建锁对象(redisson自带的分布式锁)
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock){
            //获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单！");
        }
        try {
            //获取代理对象(Spring中要通过代理对象去调方法才具有事务功能，若不用代理对象而直接去调，则是目标对象调的方法，没有事务特性)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        //5.1查询订单(根据用户id和优惠卷id查)
//        Long userId = UserHolder.getUser().getId(); //不能通过线程去取用户id了，因为这是子线程
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2判断是否存在(不太可能出现重复，因为Redis已经做了判断，以防万一)
        if (count > 0){
            //用户已抢到过卷
            log.error("用户已经购买过一次！");
            return;
        }

        //6.扣减库存(使用乐观锁将SQL语句的判断条件加上库存值，只有库存值大于0才能扣减成功，避免超卖问题)
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) //where voucher_id = ? and stock > 0
                .update();
        if (!success){ //不太可能出现库存不足，因为Redis已经做了判断，以防万一
            //扣减失败
            log.error("库存不足！");
            return;
        }

        //7.保存订单到数据库
        save(voucherOrder);
    }
}
