package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.YuYue;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface YuYueMapper extends BaseMapper<YuYue> {

    @Select("select * from tb_yuyue")
    List<YuYue> selectAll();



}
