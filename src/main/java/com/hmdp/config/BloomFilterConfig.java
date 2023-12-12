package com.hmdp.config;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BloomFilterConfig {

    @Bean
    public BloomFilter<Long> bloomFilter() {
        // 预期插入数量
        long capacity = 10000L;
        // 错误比率
        double errorRate = 0.01;
        //创建BloomFilter对象，需要传入Funnel对象，预估的元素个数，错误率
        BloomFilter<Long> bloomFilter = BloomFilter.create(Funnels.longFunnel(), capacity, errorRate);
        return bloomFilter;
    }

}