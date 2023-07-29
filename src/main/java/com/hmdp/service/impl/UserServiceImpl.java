package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3.符合直接生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到 Redis,注意键的分层写法和过期时间的设置 set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码, 由于真实开发是调用第三方 API 去发送短信，故我节省时间直接打日志返回
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回 ok(当然也可以不直接基于初始化模板使用 ok，而是自己自定义 success)
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 0. 校验手机号，因为有可能后面发完验证码后又改手机号匹配验证码登录
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 1. 从 redis 中获取验证码, 注意别把键漏了层级，单单写个 phone
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if ( cacheCode == null || !code.equals(cacheCode)){
            // 2. 如果不一致则返回让用户重新提交
            return Result.fail("验证码错误，请重新输入");
        }
        // 3. 如果一致则根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 4. 判断用户是否存在
        if (user == null) {
            // 5. 不存在则创建新的用户并保存
            user = createUserWithPhone(phone);
        }
        // 6. 保存用户信息到 redis 中
        // 6.1 随机生成 token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 6.2 将 UserDTO 转为 HashMap 存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        // 6.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 6.4 设置有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 7.1 返回 token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 创建用户，存入 phone 和 随机名字即可
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(7));
        // 保存用户信息
        save(user);
        return user;
    }
}
