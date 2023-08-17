package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动分页结果
 */

@Data
public class ScrollResult {
    private List<?> list; //查询的数据
    private Long minTime; //数据中的最小时间
    private Integer offset; //偏移量
}
