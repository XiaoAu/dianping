package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    //关注或取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        //2.判断到底是关注还取关
        if (isFollow) {
            //3.关注，新增数据到关系表
            Follow follow = new Follow();
            follow.setUserId(userId); //要关注的用户(当前用户)
            follow.setFollowUserId(followUserId); //被关注的用户
            boolean isSuccess = save(follow);
            if (isSuccess){
                //把关注用户的id存入Redis的set集合中 sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            //4.取关，从关系表删除数据 delete from tb_follow where userId = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess){
                //把取关用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    //是否关注
    @Override
    public Result isFollow(Long followUserId) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3.判断
        return Result.ok(count > 0);
    }

    //共同关注
    @Override
    public Result followCommons(Long id) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        //2.求交集(Redis中)
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            //无交集，返回空集合
            return Result.ok(Collections.emptyList());
        }
        //3.解析id集合(String转成Long)
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户(user对象转成UserDTO)
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());//收集成集合
        return Result.ok(users);
    }
}
