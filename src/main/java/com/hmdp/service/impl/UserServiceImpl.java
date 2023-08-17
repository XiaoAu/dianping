package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 服务实现类
 */

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate; //操作redis的工具类

    //发送手机验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.验证手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到session换成到redis，有效期2分钟
//        session.setAttribute("code", code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES); //key加上业务前缀以示区分

        //5.发送验证码
        log.info("发送短信验证码成功，验证码：" + code);

        //6.返回ok
        return Result.ok();
    }


    //登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1，校验手机号(与上面的方法是两次不同的请求，需要再做手机号校验，防止发了验证码后再修改手机号)
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //2.校验验证码(从session中获取改成从redis中获取)
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if (user == null){
            //6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到session中改为到redis中
        //7.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转为HashMap存储(将User对象转成UserDTO对象,隐藏用户敏感信息)
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //由于StringRedisTemplate规定了写入redis中的key和value都要字符串类型，但UserDTO中的id是Long类型，要进行转换
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), //将userDTO转为自定义的Map
                CopyOptions.create()
                .setIgnoreNullValue(true) //忽略空值
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); //修改字段值，将键值对中的值转为字符串类型
        //7.3存储
//        session.setAttribute("user", userDTO);
        String tokenKey = LOGIN_USER_KEY + token; //key加上业务前缀以示区分
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //7.4设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8.返回token
        return Result.ok(token);
    }

    //签到
    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM")); //截取到月，方便后面按月统计签到情况
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth(); //1~31天
        //5.写入Redis SETBIT Key offset 1  (Redis中offset是0~30，要减1)
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    //统计本月从今天往前连续签到天数
    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM")); //截取到月，方便后面按月统计签到情况
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth(); //1~31天
        //5.获取本月截止今天为止的所有签到记录，返回的是一个十进制的数字 BITFIELD sign:1010:202307 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }
        //取出签到记录(十进制)
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;
        while (true) {
            //6.1让这个数字与1做与运算，得到数字的最后一个bit位，判断这个bit位是否为0
            if ((num & 1) == 0) {
                //6.2如果为0，说明未签到，结束
                break;
            }else {
                //6.3如果不为0，说明已签到，计数器+1
                count++;
            }
            //6.4把数字右移一位，抛弃最后一个bit位，继续前一个bit位
            num >>>= 1; //右移一位再赋值给num
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10)); //随机生成用户名

        //2.保存用户
        save(user);
        return user;
    }

}
