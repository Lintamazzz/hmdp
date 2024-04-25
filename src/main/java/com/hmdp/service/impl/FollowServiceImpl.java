package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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

    @PostConstruct
    private void init() {
        // 把每个用户的关注列表加载到 redis 的 set 集合里
        List<Follow> list = query().list();
        for (Follow follow : list) {
            Long userId = follow.getUserId();
            Long followUserId = follow.getFollowUserId();
            stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
        }
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId;

        if (isFollow) {
            // 关注  新增关注关系
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if (success) {
                // 把关注用户的 id 放入 redis 里的 set 集合  sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取关  删除关注关系
            boolean success = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            // 从 set 中移除
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 关注相关操作 登录拦截器会拦截的  如果没有登录就会跳转到登录页面
        // 所以这里不需要判断是否登录  不过以防万一也可以判断一下 然后直接返回 Result.ok(false)
        Long userId = UserHolder.getUser().getId();

        // 查询当前用户是否关注了该用户
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollow(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String s1 = RedisConstants.FOLLOW_KEY + userId;
        String s2 = RedisConstants.FOLLOW_KEY + id;
        // 求交集
        Set<String> common = stringRedisTemplate.opsForSet().intersect(s1, s2);
        if (common == null || common.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 返回用户信息  转成 UserDTO
        List<Long> ids = common.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }
}
