package com.hao.haoaicode.service.impl;

import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import com.hao.haoaicode.model.entity.UserWallet;
import com.hao.haoaicode.mapper.UserWalletMapper;
import com.hao.haoaicode.service.UserWalletService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 用户钱包表 服务层实现。
 *
 * @author hao
 */
@Slf4j
@Service
public class UserWalletServiceImpl extends ServiceImpl<UserWalletMapper, UserWallet>  implements UserWalletService{
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserWalletMapper userWalletMapper;
    @Resource(name = "ioExecutor")
    private Executor ioExecutor;

    private static final String WALLET_KEY_PREFIX = "user:wallet:";
    private static final long CACHE_EXPIRE_SECONDS = 3600;
    private static final int MAX_RETRY = 3; // 乐观锁重试次数


    // Lua脚本：原子性扣减积分
    private static final String DEDUCT_SCRIPT = 
        // 获取当前余额
        "local balance = redis.call('get', KEYS[1]) " +
        // 如果不存在， 返回 -1， 需要从DB加载
        "if not balance then " +
        "    return -1 " +  // 缓存未命中
        "end " +
        // 余额不足，返回0
        "if tonumber(balance) < tonumber(ARGV[1]) then " +
        "    return 0 " +   // 余额不足
        "end " +
        // 扣减积分
        "redis.call('decrby', KEYS[1], ARGV[1]) " +
        "return 1";         // 扣减成功


    /**
     * 进行积分扣除（Redis预扣费）
     * @param userId
     * @param cost
     * @return
     */
    @Override
    public boolean tryDeduct(long userId, int cost) {
        String key = WALLET_KEY_PREFIX + userId;

        // 执行lua脚本,一次性完成查询和扣减
        Long result = stringRedisTemplate.execute(
                new DefaultRedisScript<>(DEDUCT_SCRIPT, Long.class),
                Collections.singletonList(key),
                String.valueOf(cost)
        );

        if (result == null){
            return false;
        }

        // 根据返回值处理
        if (result == -1) {
            // 缓存未命中，从DB加载到Redis
            if (!loadBalanceToRedis(userId)) {
                return false; // 用户不存在或余额为0
            }
            // 重试扣减
            return tryDeduct(userId, cost);
        }

        if (result != null && result == 1) {
                    // 扣减成功，【异步】更新数据库
                    CompletableFuture.runAsync(() -> {
                        updateDbWithOptimisticLock(userId, cost);
                    }, ioExecutor).exceptionally(e -> {
                        log.error("异步更新钱包数据库失败，userId: {}", userId, e);
                        // 保存到失败表，后续补偿
                        saveToFailureLog(userId, cost);                        return null;
                    });

                    return true;
        }

        return result == 1;  // 只有扣减成功才返回 true

    }

    /**
     * 从数据库加载数据到redis
     * @param userId
     * @return
     */
    private boolean loadBalanceToRedis(long userId) {

        UserWallet userWallet = userWalletMapper.selectOneById(userId);
        if(userWallet == null || userWallet.getBalance() <= 0){
            return false;
        }
        // 构建key
        String key = WALLET_KEY_PREFIX + userId;
        // 更新Redis缓存
        stringRedisTemplate.opsForValue().set(
            key, 
            String.valueOf(userWallet.getBalance()), 
            CACHE_EXPIRE_SECONDS, 
            TimeUnit.SECONDS);

        return true;

    }

    /**
     * 预扣失败进行回滚
     * @param userId
     * @param cost
     */
    @Override
    public void rollback(long userId, int cost) {
        String key = "user:credit:" + userId;
        stringRedisTemplate.opsForValue().increment(key, cost);
        log.info("用户 {} 积分已回滚/退款: +{}", userId, cost);
    }

    /**
     * 使用乐观锁更新数据库，防止同一用户快速点击的并发情况
     * @param userId
     * @param deductAmount  本次扣减金额
     * @return 是否更新成功
     */
    @Override
    public boolean updateDbWithOptimisticLock(long userId, long deductAmount) {
        for (int i = 0; i < MAX_RETRY; i++) {
            // 1. 查询当前数据包括version
            UserWallet userWallet = userWalletMapper.selectOneById(userId);
            if(userWallet == null){
                log.error("用户钱包不存在,userId: {}", userId);
                return false;
            }
            
            // 2. 检查余额是否充足
            if (userWallet.getBalance() < deductAmount) {
                log.warn("数据库余额不足, userId: {}, balance: {}, cost: {}", 
                    userId, userWallet.getBalance(), deductAmount);
                return false;
            }  
            
            // 3.计算新余额
            long newBalance = userWallet.getBalance() - deductAmount;
            long newTotalConsumed = userWallet.getTotalConsumed() + deductAmount;

            // 4.设置更新的内容
            UserWallet updateEntity = new UserWallet();

            updateEntity.setBalance(newBalance);
            updateEntity.setTotalConsumed(newTotalConsumed);
            updateEntity.setVersion(userWallet.getVersion() + 1);
            
            // 使用 QueryWrapper 设置更新的 WHERE 条件
            QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(UserWallet::getUserId, userId)
                .eq(UserWallet::getVersion, userWallet.getVersion()); // 乐观锁关键
            
            // updateByQuery 方法：执行SQL， 根据 QueryWrapper 更新
            // affectedRows： SQL 执行后影响的行数。
            int affectedRows = userWalletMapper.updateByQuery(updateEntity, queryWrapper);
            
            if (affectedRows == 1) {
                log.info("乐观锁更新成功, userId: {}, 扣减: {}, 剩余: {}, 重试次数: {}", 
                    userId, deductAmount, newBalance, i);
                return true;
            } else if (affectedRows > 1) {
                // 这种情况理论上不应该出现
                log.error("严重错误：更新了多行数据！userId: {}, affectedRows: {}", userId, affectedRows);
                throw new IllegalStateException("数据异常：更新了 " + affectedRows + " 行");
            }
            
            // 5. 更新失败，说明version已变化，重试
            log.warn("乐观锁更新失败，版本冲突, userId: {}, 重试: {}/{}", 
                userId, i + 1, MAX_RETRY);
            
            // 短暂休眠后重试（递增退避）
            try {
                Thread.sleep(50 * (i + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        log.error("乐观锁更新失败，超过最大重试次数, userId: {}", userId);
        return false;
    }



    /**
     * 保存失败记录，用于后续补偿
     */
    private void saveToFailureLog(long userId, long cost) {
        // 保存到 wallet_sync_failure 表
        // 定时任务会扫描这张表，重试失败的记录
    }
}
