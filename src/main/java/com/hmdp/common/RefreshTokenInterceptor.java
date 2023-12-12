package com.hmdp.common;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 第一个拦截器(拦截所有请求，刷新token有效期)
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    //这里用不了依赖注入注解，因为此类不是由Spring容器管理的，即没有被@Component、@Service或其他相关注解标识为Spring的Bean，
    // 那么在该类中使用依赖注入注解是无效的，在运行时会导致空指针异常
    private StringRedisTemplate stringRedisTemplate;

    //构造方法
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){ //判空
            return true; //放行
        }

        //2.基于token获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //3.判断用户是否存在
        if (userMap.isEmpty()){
            return true; //放行
        }

        //5.将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //6.存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        //7.刷新token有效期(在登录功能中设置了token有效期为30分钟，但这是指从token存入redis中开始算起30分钟自动失效。
        // 而我们要的是用户在30分钟内没有任何访问才使token失效，所以这里用户每访问一次就要刷新token有效期)
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //8.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //ThreadLocal中移除用户(防止ThreadLocal内存泄漏)
        UserHolder.removeUser();
    }
}
