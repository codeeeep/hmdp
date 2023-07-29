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

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * @author codeep
 * @date 2023/7/29 8:55
 * @description: 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取 请求头中的 token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            // 4. 不存在则拦截, 前端友好返回 401 状态码（具体的401错误是指:未授权,请求要求进行身份验证）
            response.setStatus(401);
            return false;
        }
        // 2. 根据层次键 token 获取 Redis 中的用户信息
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3. 判断用户是否存在，这里使用 IsEmpty 不用 null，因为返回的空 stringRedisTemplate 会自动包装成空的 map
        if (userMap.isEmpty()) {
            // 4. 不存在则拦截, 前端友好返回 401 状态码（具体的401错误是指:未授权,请求要求进行身份验证）
            response.setStatus(401);
            return false;
        }
        // 5. 存在则将保存用户信息到 ThreadLocal 中
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        //6. 刷新 token 有效期
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        // 6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，防止内存泄漏
        UserHolder.removeUser();
    }
}
