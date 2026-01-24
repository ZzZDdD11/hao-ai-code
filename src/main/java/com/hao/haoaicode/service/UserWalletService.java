package com.hao.haoaicode.service;

import com.mybatisflex.core.service.IService;
import com.hao.haoaicode.model.entity.UserWallet;

/**
 * 用户钱包表 服务层。
 *
 * @author hao
 */
public interface UserWalletService extends IService<UserWallet> {

    /**
     * 尝试扣除积分
     *
     * @return
     */
    public boolean tryDeduct(long userId, int cost);

    /**
     * 扣除失败，回滚积分
     */
    public void rollback(long userId, int cost);

    /**
     * 乐观锁更新DB
     *
     * @param userId
     * @param deductAmount
     * @return
     */
    public boolean updateDbWithOptimisticLock(long userId, long deductAmount);
}
