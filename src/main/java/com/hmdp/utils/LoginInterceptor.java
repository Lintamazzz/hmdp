package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取 session
        HttpSession session = request.getSession();
        // 2. 获取 session 中的用户信息
        Object user = session.getAttribute("user");
        // 3. 判断用户是否存在
        if (user == null) {
            // 4. 不存在 说明未登录 拦截
            response.setStatus(401);  // 设置响应码  表示未授权  告诉前端失败了  也可以抛一个异常
            return false;   // return false 表示拦截  return true 表示放行
        }
        // 5. 存在 保存用户信息到 threadlocal
        UserHolder.saveUser((UserDTO) user);
        // 6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 线程处理完之后执行  移除用户信息 避免内存泄漏
        UserHolder.removeUser();
    }
}
