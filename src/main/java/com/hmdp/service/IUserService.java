package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 *  服务类
 */

public interface IUserService extends IService<User> {

    //发送手机验证码
    Result sendCode(String phone, HttpSession session);

    //登录功能
    Result login(LoginFormDTO loginForm, HttpSession session);

    //签到
    Result sign();

    //统计本月从今天往前连续签到天数
    Result signCount();
}
