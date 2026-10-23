package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current){
        //根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //获取当页数据
        List<Blog> records = page.getRecords();
        //查询用户
        records.forEach(blog->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }



    @Override
    public Result queryBlogById(Long id){
        //查询blog
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("笔记不存在!");
        }
        //查询blog相关用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private String likedKey(Long blogId) {
        return RedisConstants.BLOG_LIKED_KEY + blogId;
    }

    private void isBlogLiked(Blog blog) {
        //未登录用户直接视为未点赞（/blog/hot 是公开接口）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        //判断是否已经点过赞
        Double score = stringRedisTemplate.opsForZSet()
                .score(likedKey(blog.getId()), user.getId().toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断是否已经点过赞
        String key = likedKey(id);
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //如果未点赞，可以点赞
            //数据库点赞数加一
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //保存用户到redis的set集合
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //如果已经点赞，取消点赞
            //数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            //把用户从redis的set集合移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //ZRANGE key 0 4：按点赞时间升序取前5个（最早点赞的5人）
        Set<String> idStrings = stringRedisTemplate.opsForZSet()
                .range(likedKey(id), 0, 4);
        if (idStrings == null || idStrings.isEmpty()) {
            //必须返回空列表，不能是 Result.ok()（data 会是 null）
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = idStrings.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        //listByIds 不保证顺序，按 ZSet 顺序重建
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<UserDTO> users = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            User user = userMap.get(userId);
            //用户已被删除则跳过
            if (user != null) {
                users.add(BeanUtil.copyProperties(user, UserDTO.class));
            }
        }
        return Result.ok(users);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}
