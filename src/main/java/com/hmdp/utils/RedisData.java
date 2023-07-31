package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author codeep
 * @description: 持有 Bean 实体，扩展：组合内聚优于继承
 */

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
