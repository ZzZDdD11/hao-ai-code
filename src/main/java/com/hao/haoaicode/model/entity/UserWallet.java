package com.hao.haoaicode.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

import java.io.Serial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户钱包表 实体类。
 *
 * @author hao
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_wallet")
public class UserWallet implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 用户id
     */
    @Column("userId")
    private Long userId;

    /**
     * 当前积分余额
     */
    private Long balance;

    /**
     * 总消耗积分
     */
    @Column("totalConsumed")
    private Long totalConsumed;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}
