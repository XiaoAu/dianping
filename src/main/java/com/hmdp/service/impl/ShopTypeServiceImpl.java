package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 *  服务实现类
 */

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //查询店铺类型
    @Override
    public Result queryShopType() {
        String key = CACHE_SHOP_TYPE_KEY; //key加上业务前缀以示区分

        //1.从redis查询商铺类型缓存(可能返回多个对象，用List)
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1); //查询整个列表

        //2.判断是否存在
        if (shopTypeJson.size() > 0){ //是否不为空
            //3.存在，直接返回
            List<ShopType> shopTypeList1 = new ArrayList<>();
            //得到的列表中的元素是String类型的，要一个个转成ShopType对象
            for (String s : shopTypeJson) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                shopTypeList1.add(shopType);
            }
            return Result.ok(shopTypeList1);
        }

        //4.不存在，根据id查询数据库
        List<ShopType> shopTypeList2 = query().orderByAsc("sort").list();

        //5.数据库查询不存在，返回错误
        if (shopTypeList2.isEmpty()){
            return Result.fail("店铺类型不存在！");
        }

        //6.存在，写入redis(对象要转成json)
        List<String> shopTypeList3 = new ArrayList<>();
        for (ShopType shopType : shopTypeList2) {
            String shopTypeString = JSONUtil.toJsonStr(shopType);
            shopTypeList3.add(shopTypeString);
        }
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList3);

        //7.返回
        return Result.ok(shopTypeList2);
    }
}
