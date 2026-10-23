package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final DateTimeFormatter SIGN_MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMM");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //0.归一化：输入法可能会打出全角数字，先转成半角再校验
        phone = toHalfWidth(phone);
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码保存到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送短信验证码成功！验证码:{}",code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = toHalfWidth(loginForm.getPhone());
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //3.TODO 校验验证码
        String code = toHalfWidth(loginForm.getCode());//用户输入的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);//实际发的验证码
        //4.不一致，报错
        if(cacheCode==null || !cacheCode.equals(code)){
            return Result.fail("验证码错误！");
        }
        //5.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //6.判断用户是否存在
        if(user==null){
            //7.如果不存在，创建新用户保存到数据库
            user=createUserWithPhone(phone);
        }
        //8.保存用户到redis中
        //8.1随机生成token作为登录令牌
        String token = UUID.randomUUID().toString();
        //8.2将user对象作为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //注意：StringRedisTemplate 的 hash value 序列化器是 StringRedisSerializer，
        //它在运行时会把每个 value 强转成 String。userDTO.id 是 Long，直接 putAll 会抛
        //ClassCastException。这里统一转成字符串，顺带用 setIgnoreNullValue 跳过 icon 等空字段
        //（空字段会变成 null 值，hMSet 同样会报错）。
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //8.3存储
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //8.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //9.返回token
        return Result.ok(token);
    }

    @Override
    public Result queryUserById(Long id) {
        User user = getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        return Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        String key = signKey(userId, now);

        // 偏移量从 0 开始：1 号 -> 0，31 号 -> 30
        stringRedisTemplate.opsForValue()
                .setBit(key, now.getDayOfMonth() - 1, true);

        // 保留两个月：当月 + 上个月，为跨月连续统计预留数据
        stringRedisTemplate.expire(key, 62, TimeUnit.DAYS);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        String key = signKey(userId, now);
        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return Result.ok(0);
        }

        long num = result.get(0);
        int count = 0;
        // 最低位对应今天，数末尾连续的 1
        while ((num & 1) == 1) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private String signKey(Long userId, LocalDate date) {
        return USER_SIGN_KEY + userId + ":" + date.format(SIGN_MONTH_FORMATTER);
    }

    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));

        save(user);
        return user;
    }

    /**
     * 全角字符转半角。
     * 输入法处在全角模式时，打出的数字是 U+FF10~U+FF19，和半角 U+0030~U+0039
     * 是两个不同的字符，肉眼几乎一样但 equals() 不相等，会导致验证码比对永远失败。
     */
    private static String toHalfWidth(String str) {
        if (str == null) {
            return null;
        }
        char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == 0x3000) {
                // 全角空格
                chars[i] = ' ';
            } else if (chars[i] >= 0xFF01 && chars[i] <= 0xFF5E) {
                // 全角 '!'~'~' (U+FF01~U+FF5E) 与半角对应字符相差 0xFEE0
                chars[i] = (char) (chars[i] - 0xFEE0);
            }
        }
        return new String(chars);
    }
}
