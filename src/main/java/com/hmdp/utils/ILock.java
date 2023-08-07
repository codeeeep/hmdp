package com.hmdp.utils;

/**
 * @author codeep
 * @date 2023/8/6 9:32
 * @description:
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，到期自动释放
     * @return true 表示获取锁成功，false 代表获取锁失败
     */
    boolean tryLock(long timeoutSec);


    /**
     * 释放锁，防止出现死锁问题
     */
    void unlock();

}
