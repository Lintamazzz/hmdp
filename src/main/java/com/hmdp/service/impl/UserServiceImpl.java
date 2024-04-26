package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 校验不通过，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 2. 校验通过，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 保存验证码到 Redis 并设置有效期
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4. 发送验证码
        log.debug("发送短信验证码成功，验证码为：{}", code);
        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        // 为什么还要校验？因为是两个不同的请求，第二次请求时可能会把手机号改成错的
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 校验不通过，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 2. 从 Redis 获取验证码并进行校验
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 验证码不一致，返回错误信息
            return Result.fail("验证码错误");
        }

        // 3. 验证码一致，根据手机号查询用户  select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 4. 判断用户是否存在
        if (user == null) {
            // 不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 5. 保存用户信息到 Redis 并以此来判断登录状态
        //    5.1 随机生成 token 作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //    5.2 将 User 转成 UserDTO 再存储为 Hash 类型
        //       转成 UserDTO 是因为 不需要保存全部的用户信息：1、占内存 2、不是所有信息都会用到 3、要防止敏感信息通过/user/me返回给前端
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //       也可以转成 JSON 字符串再以 String 类型存储，不过这里用 Hash 类型来存
        //       后面这一大堆是为了把 UserDTO 的属性全部转成 String 再放 map 里
        //       否则使用 StringSerializer 序列化 Long 类型的 id 会报错
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        //    5.3 保存到 Redis
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);

        //    5.4 设置 token 有效期
        //       之后每次访问都要更新有效期，来维持登录状态，不然经过一个 TTL 就会自动登出（在拦截器里实现）
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);


        // 6. 返回 token 给前端
        log.debug("登录成功，用户信息: {}", user);
        log.debug("token: {}", token);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();

        // 拼接 key
        String timeSuffix = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + timeSuffix;   // sign:userId:yyyy:MM

        // 保存到 redis 的 bitmap 里 SETBIT key offset 1
        int offset = now.getDayOfMonth() - 1;   // 返回1~31  所以要-1
        stringRedisTemplate.opsForValue().setBit(key, offset, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();

        // 拼接 key
        String timeSuffix = now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + timeSuffix;   // sign:userId:yyyy:MM

        // 获取本月截止今天的签到记录 BITFIELD key GET u[dayOfMonth] 0
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到数据
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        // 计算截止今天的连续签到次数
        int cnt = 0;
        while (num > 0) {
            if ((num & 1) == 0) break;
            cnt++;
            num >>>= 1;  // 无符号右移 左边补0
        }

        return Result.ok(cnt);
    }


    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 保存用户
        save(user);
        log.debug("新创建用户，手机号：{}  昵称：{}", phone, user.getNickName());
        return user;
    }
}
