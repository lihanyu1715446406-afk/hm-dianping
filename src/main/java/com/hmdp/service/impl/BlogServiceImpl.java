package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
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
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int FEED_PAGE_SIZE = SystemConstants.DEFAULT_PAGE_SIZE;

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result saveBlog(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);

        // 1. 先落库，失败直接返回
        boolean saved = save(blog);
        if (!saved) {
            return Result.fail("发布笔记失败");
        }

        // 2. 查询作者的所有粉丝：tb_follow.follow_user_id = 作者
        List<Follow> followers = followService.query()
                .eq("follow_user_id", userId)
                .list();

        // 3. 一条笔记给所有粉丝使用同一个时间戳，写入各自的收件箱
        long now = System.currentTimeMillis();
        String blogId = blog.getId().toString();
        for (Follow follower : followers) {
            String key = RedisConstants.FEED_KEY + follower.getUserId();
            try {
                stringRedisTemplate.opsForZSet().add(key, blogId, now);
            } catch (Exception e) {
                // 尽力而为：Redis 失败不影响已经成功的数据库写入
                log.warn("feed push failed, followerId={}, blogId={}",
                        follower.getUserId(), blog.getId(), e);
            }
        }
        return Result.ok(blog.getId());
    }

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

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;

        long maxScore = (max == null) ? System.currentTimeMillis() : max;
        int start = (offset == null || offset < 0) ? 0 : offset;

        // ZREVRANGEBYSCORE feed:{userId} maxScore 0 LIMIT start 5
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, maxScore, start, FEED_PAGE_SIZE);

        // 空收件箱也要返回非 null 的 ScrollResult
        if (tuples == null || tuples.isEmpty()) {
            ScrollResult empty = new ScrollResult();
            empty.setList(Collections.emptyList());
            empty.setMinTime(maxScore);
            empty.setOffset(start);
            return Result.ok(empty);
        }

        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0L;
        int sameScoreCount = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long score = tuple.getScore().longValue();
            if (score == minTime) {
                sameScoreCount++;
            } else {
                minTime = score;
                sameScoreCount = 1;
            }
        }

        // 下一页游标：边界分数相同则累加已消费条数，否则重置为当前分数段条数
        int nextOffset = (minTime == maxScore)
                ? start + sameScoreCount
                : sameScoreCount;

        // listByIds 不保证顺序，按 ZSet 的 ids 顺序重建
        Map<Long, Blog> blogMap = listByIds(ids).stream()
                .collect(Collectors.toMap(Blog::getId, Function.identity()));
        List<Blog> blogs = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Blog blog = blogMap.get(id);
            if (blog == null) {
                continue; // 数据库已删除但收件箱还有，跳过
            }
            queryBlogUser(blog);
            isBlogLiked(blog);
            blogs.add(blog);
        }

        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(nextOffset);
        return Result.ok(result);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}
