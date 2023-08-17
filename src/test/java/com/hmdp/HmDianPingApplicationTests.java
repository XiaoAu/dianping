package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //线程池
    private ExecutorService es = Executors.newFixedThreadPool(500);

    //测试缓存预热
    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 20L, TimeUnit.SECONDS);
    }

    //测试全局ID生成
    @Test
    void testIdWorker() throws InterruptedException {
        //控制多个线程之间的同步(让主线程等待线程池中的任务执行完毕)，创建了一个初始计数为300的CountDownLatch对象
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> { //定义任务
            for (int i = 0; i < 100; i++) {
                Long id = redisIdWorker.nextId("order"); //测试结束后redis中这个key的value为30000，自增长了30000次
                System.out.println("id = " + id);
            }
            latch.countDown(); //减少计数器的值，表示一个操作已经完成
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task); //通过循环提交了300个任务到线程池中执行
        }
        latch.await(); //主线程等待计数器减少到 0
        long end = System.currentTimeMillis();

        System.out.println("time = " + (end - begin)); //在所有的任务执行完成后计算总共所花费的时间
    }

    //将店铺数据导入到Redis中的GEO
    @Test
    void loadShopData(){
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.把店铺分组，按照typeId分组，id一致的放到一个集合(key：店铺类型id value：同一类型的店铺列表)
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) { //map.entrySet()获取map的所有键值对
            //3.1获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //3.3写入redis GEOADD key 经度 纬度 member(店铺id)，经纬度会通过算法变成一个值，存在Redis中score属性
            for (Shop shop : value) {
                //这个是一个一个添加的，效率慢
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            //批量添加
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    //测试百万数据的统计
    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++){
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999){
                //发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }

}
