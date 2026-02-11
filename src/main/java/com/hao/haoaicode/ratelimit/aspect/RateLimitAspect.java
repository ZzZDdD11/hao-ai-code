package com.hao.haoaicode.ratelimit.aspect;

import com.hao.haoaicode.exception.BusinessException;
import com.hao.haoaicode.exception.ErrorCode;
import com.hao.haoaicode.model.entity.User;
import com.hao.haoaicode.ratelimit.annotation.RateLimit;
import com.hao.haoaicode.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

@Aspect
@Component
@Slf4j
public class RateLimitAspect {
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private UserService userService;

    /**
     * 执行限流
     * @param point
     * @param rateLimit
     */
    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit){
        String key = generateRateLimitKey(point, rateLimit);
        // 使用Redission的分布式限流器
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.expire(Duration.ofHours(1)); // 1小时候过期
        // 设置限流器参数，
        rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(), RateIntervalUnit.SECONDS);
        // 尝试获取令牌
        if(!rateLimiter.tryAcquire()){
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
        }
    }

    /**
     * 生成不同类型的限流key
     * @param point
     * @param rateLimit
     * @return
     */
    private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append("rate_limit:");
        // 添加自定义前缀
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(":");
        }
        // 根据限流类型生成不同的key
        switch (rateLimit.limitType()) {
            case API:
                // 接口级别：方法名
                MethodSignature signature = (MethodSignature) point.getSignature();//返回的是一个 Signature 对象（接口， org.aspectj.lang.Signature ），它描述的是“这个连接点的静态信息”，主要是：
                // - 方法名
                // - 所在类
                // - 参数类型列表
                // - 返回值类型（在更具体的子类型里）
                // 就好像是给你一张“方法名片”：告诉你“我是哪个类里的哪个方法，长什么样”。
                Method method = signature.getMethod();
                keyBuilder.append("api:").append(method.getDeclaringClass().getSimpleName())//拿到类名
                        .append(".").append(method.getName());//拿到方法名
                break;
            case USER:
                // 用户级别：用户ID
                try {
                    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attributes != null) {
                        HttpServletRequest request = attributes.getRequest();
                        User loginUser = userService.getLoginUser(request);
                        keyBuilder.append("user:").append(loginUser.getId());
                    } else {
                        // 无法获取请求上下文，使用IP限流
                        keyBuilder.append("ip:").append(getClientIP());
                    }
                } catch (BusinessException e) {
                    // 未登录用户使用IP限流
                    keyBuilder.append("ip:").append(getClientIP());
                }
                break;
            case IP:
                // IP级别：客户端IP
                keyBuilder.append("ip:").append(getClientIP());
                break;
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
        }
        return keyBuilder.toString();
    }

    /**
     * 获取IP
     * @return
     */
    private String getClientIP() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        // 1.优先从X-Forwarded-For头获取IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // 2.再尝试从 X-Real-IP 头获取IP
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // 3.如果以上头都没有，都没有再用底层连接的 IP
            // 这是 Servlet 规范提供的，拿到的是“当前这条 TCP 连接的对端 IP”。
            // 如果你有 Nginx / 网关做转发，这里拿到的通常是 网关的 IP ，而不是用户真实 IP，所以放在最后兜底。
            ip = request.getRemoteAddr();
        }
        // 处理多级代理的情况,X-Forwarded-For 里有多个 IP 时，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }


}
