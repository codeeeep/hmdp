package com.hmdp.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author codeep
 * @date 2023/8/7 10:43
 * @description:
 */
@SpringBootTest
class ShopServiceImplTest {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testLock() {
        boolean success = shopService.saveLock("test");
        System.out.println(success);
    }

}