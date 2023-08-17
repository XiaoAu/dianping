package com.hmdp.config;

import com.hmdp.common.LoginInterceptor;
import com.hmdp.common.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //配置拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //拦截器的执行顺序通过order决定，order值越小越先执行，默认值为0。order相等时先添加的先执行。

        //第一个拦截器(token刷新拦截器)拦截所有请求
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        //第二个拦截器(登录验证拦截器)拦截指定请求
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns( //不拦截的路径
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);
    }
}
