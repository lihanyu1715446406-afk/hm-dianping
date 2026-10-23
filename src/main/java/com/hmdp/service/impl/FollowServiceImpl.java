package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
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

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己");
        }
        String key = RedisConstants.FOLLOW_KEY + userId;

        if (Boolean.TRUE.equals(isFollow)) {
            // 没有唯一索引，先查重防止重复插入
            Integer count = query()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
                    .count();
            if (count == null || count == 0) {
                save(new Follow()
                        .setUserId(userId)
                        .setFollowUserId(followUserId));
            }
            // 即使数据库已存在也写入，顺带修复 Redis 中缺失的成员
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            // ChainQuery 没有 remove，使用 IService.remove(Wrapper)
            remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            // 数据库无记录也删除，清理可能的脏成员
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count != null && count > 0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId;
        String otherKey = RedisConstants.FOLLOW_KEY + followUserId;

        // SINTER：两个用户关注集合的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, otherKey);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
