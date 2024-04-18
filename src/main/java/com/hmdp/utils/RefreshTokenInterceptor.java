package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    // 这里不能用 @Autowired StringRedisTemplate
    // 因为 LoginInterceptor 并没有被 Spring 接管，无法实现依赖注入
    // 所以我们利用 @Configuration 的 MvcConfig 获取到 StringRedisTemplate 并传进来
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取请求头中的 token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // token 为空 说明未登录 放行到下一个拦截器
            return true;
        }

        // 2. 使用 token 获取 Redis 里的用户信息
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3. 判断用户信息是否存在
        // entries 如果结果为 null 会返回一个空 map 所以不能用 null 来判断
        if (userMap.isEmpty()) {
            // map为空 即用户信息不存在 说明未登录 放行到下一个拦截器
            return true;
        }

        // 下一个拦截器根据 threadlocal 里有没有用户信息来判断是否拦截  用户存在 说明已登录 放行

        // 4. 保存用户信息到 threadlocal
        // 因为在 Redis 里使用 Hash 来保存用户数据，所以要把获取到的 Hash 数据转为 UserDTO 对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        // 5. 刷新 token 有效期，来维持登录状态
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 线程处理完之后执行  移除用户信息 避免内存泄漏
        UserHolder.removeUser();
    }
}