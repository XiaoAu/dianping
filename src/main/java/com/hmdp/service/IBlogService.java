package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 */

public interface IBlogService extends IService<Blog> {

    //保存博客笔记
    Result saveBlog(Blog blog);

    //首页博客笔记列表分页查询
    Result queryHotBlog(Integer current);

    //根据id查询博客笔记
    Result queryBlogById(Long id);

    //点赞
    Result likeBlog(Long id);

    //根据博客id查询点赞列表
    Result queryBlogLikes(Long id);

    //滚动分页查询关注用户的博客笔记
    Result queryBlogOfFollow(Long max, Integer offset);
}
