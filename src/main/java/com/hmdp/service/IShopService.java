package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 */

public interface IShopService extends IService<Shop> {

    //根据id查商铺
    Result queryById(Long id);

    //更新商铺
    Result update(Shop shop);

    //根据店铺类型分页查询店铺信息
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
