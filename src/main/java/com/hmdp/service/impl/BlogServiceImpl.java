package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
        Long userId = UserHolder.getUser().getId();

        // 判断当前用户是否已经点赞过
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        // 如果点过赞，设置 isLike
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 判断当前用户是否已经点赞过
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember)) {
            // 如果未点赞，可以点赞
            // 数据库点赞数 + 1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            // 保存用户到 redis 的 set 集合
            if (success) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            // 如果已点赞，取消点赞
            // 数据库点赞数 - 1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            // 从 redis 的 set 集合里移除用户
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }

        // 这种实现有两个问题：
        // 1. 直接去 mysql 修改点赞数，耗时会比较高，应该把点赞数缓存在 redis 里，直接修改 redis 里的点赞数，然后开定时任务定期同步到 mysql
        // 2. 查询是否点赞点赞、修改点赞 这两步应该保证原子性 不然可能会产生并发安全问题
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        // 查询 blog 相关的用户信息（用户昵称、头像） 并设置进去
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
