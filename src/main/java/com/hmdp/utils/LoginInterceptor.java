package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 根据 threadlocal 里有没有用户信息来判断是否拦截  用户存在 说明已登录 放行
        if (UserHolder.getUser() == null) {
            // 用户信息不存在 说明未登录 需要拦截
            // 设置响应码 401表示未授权 告诉前端登录失败了 也可以抛一个异常
            response.setStatus(401);
            // 返回 false 表示拦截，返回 true 表示放行
            return false;
        }
        return true;
    }

}
