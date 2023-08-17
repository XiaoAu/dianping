package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 *  Redis工具类
 */

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //将Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    //将Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));//当前时间加上参数时长
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit){ //定义泛型<R,ID>；函数方法Function<参数, 返回值>
        String key = keyPrefix + id; //key加上业务前缀以示区分
        //1.从redis查询商铺缓存(存的是对象，一般用hash，但这里用字符串(opsForValue)，每种数据结构演示一遍)
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)){ //是否不为空(isNotBlank方法只有参数中双引号中有内容才会返回true，null、""和"\t\n"都会返回false)
            //3.存在，直接返回
             return JSONUtil.toBean(json, type); //转成对象
        }
        //判断命中的是否是空值
        if (json != null ){ //为空但是不等于null，就只剩""(空字符串)了，匹配到缓存穿透，可以用"".equals(json)
            //不能用json去调equals方法，因为第一次查询时redis中没有key，则json为null，null去调方法会报空指针异常
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库查询不存在，返回错误
        if (r == null){
            //将空值写入redis，防止缓存穿透，过期时间应设短一点
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis(对象要转成json)
        this.set(key, r, time, unit);
        //7.返回
        return r;
    }


    //根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id; //key加上业务前缀以示区分
        //1.从redis查询商铺缓存(存的是对象，一般用hash，但这里用字符串(opsForValue)，每种数据结构演示一遍)
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)){ //是否不为空(isNotBlank方法只有参数中双引号中有内容才会返回true，null、""和"\t\n"都会返回false)
            //3.存在，判断是否过期
            //3.1把json反序列化为对象
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            R r = JSONUtil.toBean((JSONObject) redisData.getData(), type); //店铺信息
            LocalDateTime expireTime = redisData.getExpireTime(); //逻辑过期时间
            if (expireTime == null){ //如果没有逻辑过期时间属性，证明不是热点数据，直接返回便是
                return JSONUtil.toBean(json, type);
            }
            //3.2判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) { //过期时间是否在当前时间之后
                //3.2.1未过期，直接返回店铺信息
                return r;
            }
            //3.2.2已过期，需要缓存重建
            //a.获取互斥锁
            String lockKey = lockKeyPrefix + id;
            boolean isLock = tryLock(lockKey);
            //b.判断是否获取锁成功
            if (isLock) {
                //其实获取锁成功后，应该再次检查redis缓存是否过期，若已经被重建好了不过期了则无需再做重建
                //c.获取成功，开启新线程(使用线程池，不要自己去频繁创建销毁线程)实现缓存重建，原线程直接返回旧数据
                CACHE_REBUILD_EXECUTOR.submit(() ->{ //向线程池提交任务
                    try {
                        //重建缓存
                        R r1 = dbFallback.apply(id); //查询数据库
                        this.setWithLogicalExpire(key, r1, time, unit); //写入Redis
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        //释放锁
                        unlock(lockKey);
                    }
                });
            }
            //d.获取失败，证明已经有线程在进行缓存重建，直接返回旧数据
            return r;
        }
        //判断命中的是否是空值
        if (json != null ){ //为空但是不等于null，就只剩""(空字符串)了，匹配到缓存穿透，可以用"".equals(shopJson)
            //不能用shopJson去调equals方法，因为第一次查询时redis中没有key，则shopJson为null，null去调方法会报空指针异常
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库(针对非热点数据)
        R r = dbFallback.apply(id);
        //5.数据库查询不存在，返回错误
        if (r == null){
            //将空值写入redis，防止缓存穿透，过期时间应设短一点
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis(对象要转成json)
        this.set(key, r, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return r;
    }

    //获取锁
    private boolean tryLock(String key){
        //setIfAbsent方法就是setnx，redis中不存在这个key才能加入成功，模拟互斥锁
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); //如果包装类Boolean对象为null，会抛出空指针异常，所有这里用工具类来判断
    }

    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
