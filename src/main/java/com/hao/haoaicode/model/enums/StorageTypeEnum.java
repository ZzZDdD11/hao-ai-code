package com.hao.haoaicode.model.enums;

import lombok.Getter;

/**
 * 消息存储类型枚举
 * DIRECT: 直接存储到 MySQL
 * COS: 完整内容存 COS，MySQL 只存摘要
 */
@Getter
public enum StorageTypeEnum {

    DIRECT("DIRECT", "MySQL直接存储"),
    COS("COS", "COS对象存储");

    private final String value;
    private final String desc;

    StorageTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 根据 value 获取枚举
     */
    public static StorageTypeEnum getEnumByValue(String value) {
        if (value == null) {
            return null;
        }
        for (StorageTypeEnum typeEnum : StorageTypeEnum.values()) {
            if (typeEnum.value.equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
