package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于Redis的全局ID生成器
 */

@Component
public class RedisIdWorker {

    //开始时间戳(从2022-1-1 00:00:00开始)
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    //序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC); //当前时间转为秒数
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长(key对应的value增长，如果该键不存在，会将其初始化为 0，然后再进行递增+1，所以不会出现空指针。)
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date); //count会从0开始，执行一次increment就加1

        //3.拼接并返回(最终的id64位(long类型，打印出来会显示十进制)，所以先将时间戳左移32位(二进制)，现在低32位全为0.再与count(32位)做或运算，实现将序列号拼在时间戳后面)
        return timestamp << COUNT_BITS | count;
    }

    /*public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);//获取时间
        long second = time.toEpochSecond(ZoneOffset.UTC); //2022年1月1日的具体秒数
        System.out.println("second = " + second); //1640995200
    }*/

}
