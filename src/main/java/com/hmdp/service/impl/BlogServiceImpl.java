package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);  // 设置blog的用户名和头像字段
            this.isBlogLiked(blog);    // 设置isLike字段 有没有被当前用户点赞
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询 blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 查询 blog 相关的用户信息
        queryBlogUser(blog);
        // 查询 blog 有没有被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }

        // 判断当前用户是否已经点赞过
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());

        // 如果点过赞，设置 isLike
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 判断当前用户是否已经点赞过
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 如果未点赞，可以点赞
            // 数据库点赞数 + 1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            // 保存用户到 redis 的 set 集合
            if (success) {
                // 用 zset 代替 set，存入 blog 的点赞用户 和 点赞时的时间戳，按点赞时间戳从小到大排序
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 如果已点赞，取消点赞
            // 数据库点赞数 - 1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            // 从 redis 的 set 集合里移除用户
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        // 这种实现有两个问题：
        // 1. 直接去 mysql 修改点赞数，耗时会比较高，应该把点赞数缓存在 redis 里，直接修改 redis 里的点赞数，然后开定时任务定期同步到 mysql
        // 2. 查询是否点赞点赞、修改点赞 这两步应该保证原子性 不然可能会产生并发安全问题
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 查询 Top5 的点赞用户  ZRANGE key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 获取用户id列表
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 查询用户
        // 不能用 userService.listByIds(ids)
        // 因为sql语句是 IN (5, 1) 返回顺序可能是 1 5 导致排行榜顺序出问题
        // 需要在后面加上 ORDER BY FIELD(5, 1) 强制返回顺序为 5 1
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if (!success) {
            return Result.fail("新增笔记失败");
        }
        // 查询所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送笔记 id 给所有粉丝（保存到 zset 收件箱里）
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override  // 分页查询收件箱中的博客id
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 查询收件箱
        // 关键命令：ZREVRANGE key max min WITHSCORES LIMIT offset count
        // 关键参数：max、offset  之后需要计算并返回前端 以供下一次分页查询
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        
        // 解析数据：博客 id、这一次查询最小时间戳（用作下一次查询的最大时间戳）、计算下一次的 offset（这一次查询中最小时间戳重复的个数）
        List<Long> ids = new ArrayList<>();
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 获取 blog id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            // 计算 offset
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                // 获取最小时间戳  最后一个一定是最小的
                minTime = time;
                os = 1;
            }

        }
        
        // 根据 id 查询博客 注意要保证顺序
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        for (Blog blog : blogs) {
            // 查询 blog 相关的用户信息
            queryBlogUser(blog);
            // 查询 blog 有没有被当前用户点赞
            isBlogLiked(blog);
        }

        // 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        // 查询 blog 相关的用户信息（用户昵称、头像） 并设置进去
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
