package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
//        config.useSingleServer().setAddress("redis://192.168.74.130:6379").setPassword("20020812");
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        //创建RedissonClient对象
        return Redisson.create(config);

    }

}
