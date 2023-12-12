package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.hash.BloomFilter;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.YuYue;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.YuYueMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IYuYueService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 *  服务实现类
 */

@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient; //自定义Redis工具类

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private BloomFilter<Long> bloomFilter;

    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //根据id查商铺
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透(使用缓存空对象)
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,
//                id, Shop.class, this::getById, CACHE_NULL_TTL, TimeUnit.MINUTES);

        //解决缓存穿透(使用缓存空对象)+缓存击穿(使用互斥锁)
//        Shop shop = queryWithMutex(id);

        //先过布隆过滤器
        boolean flag = bloomFilter.mightContain(id);
        if (!flag){
            return Result.fail("店铺不存在");
        }

        //解决缓存穿透(使用缓存空对象)+缓存击穿(使用逻辑过期)
//        Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY,
                id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null){
            return Result.fail("店铺不存在！");
        }

        //7.返回
        return Result.ok(shop);
    }

    //解决缓存穿透(使用缓存空对象)
    /*public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id; //key加上业务前缀以示区分
        //1.从redis查询商铺缓存(存的是对象，一般用hash，但这里用字符串(opsForValue)，每种数据结构演示一遍)
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){ //是否不为空(isNotBlank方法只有参数中双引号中有内容才会返回true，null、""和"\t\n"都会返回false)
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class); //转成shop对象
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null ){ //为空但是不等于null，就只剩""(空字符串)了，匹配到缓存穿透，可以用"".equals(shopJson)
            //不能用shopJson去调equals方法，因为第一次查询时redis中没有key，则shopJson为null，null去调方法会报空指针异常
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.数据库查询不存在，返回错误
        if (shop == null){
            //将空值写入redis，防止缓存穿透，过期时间应设短一点
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis(对象要转成json)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }*/

    //解决缓存穿透(使用缓存空对象)+缓存击穿(使用互斥锁)
    /*public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id; //key加上业务前缀以示区分
        //1.从redis查询商铺缓存(存的是对象，一般用hash，但这里用字符串(opsForValue)，每种数据结构演示一遍)
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){ //是否不为空(isNotBlank方法只有参数中双引号中有内容才会返回true，null、""和"\t\n"都会返回false)
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class); //转成shop对象
            return shop;
        }
        //判断命中的是否是空值(解决缓存穿透
        // )
        if (shopJson != null ){ //为空但是不等于null，就只剩""(空字符串)了，匹配到缓存穿透，可以用"".equals(shopJson)
            //不能用shopJson去调equals方法，因为第一次查询时redis中没有key，则shopJson为null，null去调方法会报空指针异常
            //返回错误信息
            return null;
        }
        //4.实现缓存重建(防止缓存击穿)
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if (!isLock){
                //4.3获取锁失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id); //用递归来实现重试
            }
            //4.4获取锁成功，根据id查询数据库(其实成功获取到锁之后，最好再判断一下缓存中是否已经有值了，若有值了就不用再去查了)
            shop = getById(id);
            //模拟重建缓存的延时，更多的线程来执行，检验锁的控制
            Thread.sleep(200);
            //5.数据库查询不存在，返回错误
            if (shop == null){
                //将空值写入redis，防止缓存穿透，过期时间应设短一点
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在，写入redis(对象要转成json)
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回
        return shop;
    }*/

    //解决缓存穿透(使用缓存空对象)+缓存击穿(使用逻辑过期，会先对热点数据进行缓存预热，所以访问的是热点数据都能在redis中命中。
    // 热点数据会多一个逻辑过期时间属性值，非热点数据则没有)
    /*public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id; //key加上业务前缀以示区分
        //1.从redis查询商铺缓存(存的是对象，一般用hash，但这里用字符串(opsForValue)，每种数据结构演示一遍)
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){ //是否不为空(isNotBlank方法只有参数中双引号中有内容才会返回true，null、""和"\t\n"都会返回false)
            //3.存在，判断是否过期
            //3.1把json反序列化为对象
            RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
            Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class); //店铺信息
            LocalDateTime expireTime = redisData.getExpireTime(); //逻辑过期时间
            if (expireTime == null){ //如果没有逻辑过期时间属性，证明不是热点数据，直接返回便是
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //3.2判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) { //过期时间是否在当前时间之后
                //3.2.1未过期，直接返回店铺信息
                return shop;
            }
            //3.2.2已过期，需要缓存重建
            //a.获取互斥锁
            String lockKey = LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            //b.判断是否获取锁成功
            if (isLock) {
                //其实获取锁成功后，应该再次检查redis缓存是否过期，若已经被重建好了不过期了则无需再做重建
                //c.获取成功，开启新线程(使用线程池，不要自己去频繁创建销毁线程)实现缓存重建，原线程直接返回旧数据
                CACHE_REBUILD_EXECUTOR.submit(() ->{
                    try {
                        //重建缓存
                        saveShop2Redis(id, 20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        //释放锁
                        unlock(lockKey);
                    }
                });
            }
            //d.获取失败，证明已经有线程在进行缓存重建，直接返回旧数据
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null ){ //为空但是不等于null，就只剩""(空字符串)了，匹配到缓存穿透，可以用"".equals(shopJson)
            //不能用shopJson去调equals方法，因为第一次查询时redis中没有key，则shopJson为null，null去调方法会报空指针异常
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.数据库查询不存在，返回错误
        if (shop == null){
            //将空值写入redis，防止缓存穿透，过期时间应设短一点
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入redis(对象要转成json)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }*/

    //获取锁
    /*private boolean tryLock(String key){
        //setIfAbsent方法就是setnx，redis中不存在这个key才能加入成功，模拟互斥锁
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag); //如果包装类Boolean对象为null，会抛出空指针异常，所有这里用工具类来判断
    }*/

    //释放锁
    /*private void unlock(String key){
        stringRedisTemplate.delete(key);
    }*/

    //缓存预热/重建
    /*public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        //模拟重建缓存的延时，更多的线程来执行，检验锁的控制
        Thread.sleep(200);
        //2.封装数据
        RedisData redisData = new RedisData();
        //封装店铺数据
        redisData.setData(shop);
        //封装逻辑过期时间(当前时间加上自定义时长)
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis(对象要转成json)
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/


    //更新商铺
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空！");
        }

        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    //根据店铺类型分页查询店铺信息
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x == null || y == null){
            //不需要坐标查询，按数据库查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        //GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y), //圆心
                        new Distance(5000), //半径5000m
                        //距离+分页(这里的分页只能设定截到哪，不能设定从哪开始截)
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4.解析出id
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //判断查到的数据条数是否小于分页查询的起始位置(如我要第二页数据，起始位置从5开始，但查到的总数据只有4条，则第二页没有数据)
        if (list.size() <= from){
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        //4.1截取from ~ end的部分(使用stream流的skip方法跳过前from条数据)
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //4.2获取店铺id
            String shopIdStr = result.getContent().getName(); //存的name就是id
            ids.add(Long.valueOf(shopIdStr));
            //4.3获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5.根据id查询Shop(保证id有序)
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }

    //将店铺id存到布隆过滤器中
    @PostConstruct
    @Scheduled(cron = "0 */30 * * * *") //每30分钟执行一次，重构布隆过滤器
    public void putBloomData(){
        log.info("重构布隆过滤器...");
        //查询所有店铺id
        List<Long> shopIds = shopMapper.selectAllIds();
        for (Long shopId : shopIds) {
            bloomFilter.put(shopId);
        }
    }


    @Resource
    private IYuYueService yuYueService;

    //预约活动
    @Scheduled(cron = "0 * * * * ?") //每分钟执行一次
    public void test(){
        //所有活动
        List<YuYue> actives = yuYueService.list();
        //现在时间
        LocalDateTime now = LocalDateTime.now();
        for (YuYue active : actives) {
            if (now.isAfter(active.getBeginTime().minusMinutes(10)) && now.isBefore(active.getBeginTime())){
                //模拟发短信
                log.info("{}，您预约的{}活动即将开始，请尽快前往参加", active.getPhone(), active.getActiveName());
                //发完短信后应该删掉预约表中的对应数据
                yuYueService.remove(new LambdaQueryWrapper<YuYue>().eq(YuYue::getPhone, active.getPhone())
                        .eq(YuYue::getActiveName, active.getActiveName()));
            }
        }
    }
}
