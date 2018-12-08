package cjl.Utils;

import java.util.Collections;
import java.util.UUID;

import com.alibaba.fastjson.JSON;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Created by liuchunlong on 2018/9/4.
 * <p>
 * 基于redis的分布式锁 v2
 * <p>
 * 不需要客户端时间同步
 */
public class DistributedLock {

    private static final long RETRY_BARRIER = 600; // 重试屏障，单位毫秒
    private static final long INTERVAL_TIMES = 200; // 下一次重试等待，单位毫秒

    private final JedisPool jedisPool; // redis连接池
    private final String lockKey; // lock Key
    private final long lockExpiryInMillis; // 锁的过期时长，单位纳秒

    private final ThreadLocal<Lock> lockThreadLocal = new ThreadLocal<Lock>();

    /**
     * 构造方法
     *
     * @param jedisPool          redis连接池
     * @param lockKey            锁的Key
     * @param lockExpiryInMillis 锁的过期时长，单位毫秒
     */
    public DistributedLock(JedisPool jedisPool, String lockKey, long lockExpiryInMillis) {
        this.jedisPool = jedisPool;
        this.lockKey = lockKey;
        this.lockExpiryInMillis = lockExpiryInMillis;
    }

    /**
     * 构造方法
     * <p>
     * 使用锁默认的过期时长Integer.MAX_VALUE，即锁永远不会过期
     *
     * @param jedisPool redis连接池
     * @param lockKey   锁的Key
     */
    public DistributedLock(JedisPool jedisPool, String lockKey) {
        this(jedisPool, lockKey, Integer.MAX_VALUE);
    }

    /**
     * 获取锁在redis中的Key标记
     *
     * @return locks key
     */
    public String getLockKey() {
        return this.lockKey;
    }

    /**
     * 锁的过期时长
     *
     * @return
     */
    public long getLockExpiryInMillis() {
        return lockExpiryInMillis;
    }

    /**
     * can override
     *
     * @param jedis
     * @return
     */
    private String nextUid(Jedis jedis) {
        // 可以考虑雪花算法..
        return UUID.randomUUID().toString();
    }

    private synchronized Jedis getClient() {
        return jedisPool.getResource();
    }

    private synchronized void closeClient(Jedis jedis) {
        jedis.close();
    }

    /**
     * 请求分布式锁，不会阻塞，直接返回
     *
     * @param jedis redis 连接
     * @return 成功获取锁返回true, 否则返回false
     */
    private boolean tryAcquire(Jedis jedis) {

        final Lock nLock = new Lock(nextUid(jedis));
        String result = jedis.set(this.lockKey, nLock.toString(), "NX", "PX", this.lockExpiryInMillis);
        if ("OK".equals(result)) {
            lockThreadLocal.set(nLock);
            return true;
        }
        return false;
    }

    /**
     * 请求分布式锁，不会阻塞，直接返回
     *
     * @return 成功获取锁返回true, 否则返回false
     */
    public boolean tryAcquire() {

        Jedis jedis = null;
        try {
            jedis = getClient();
            return tryAcquire(jedis);
        } finally {
            if (jedis != null) {
                closeClient(jedis);
            }
        }
    }

    /**
     * 超时请求分布式锁，会阻塞
     *
     * 采用"自旋获取锁"的方式，直至获取锁成功或者请求锁超时
     *
     * @param acquireTimeoutInMillis 锁的请求超时时长
     * @return
     */
    public boolean acquire(long acquireTimeoutInMillis) throws InterruptedException {

        Jedis jedis = null;
        try {

            jedis = getClient();

            long acquireTime = System.currentTimeMillis();
            long expiryTime = System.currentTimeMillis() + acquireTimeoutInMillis; // 锁的请求到期时间

            while (expiryTime >= System.currentTimeMillis()) {
                boolean result = tryAcquire(jedis);
                if (result) { // 获取锁成功直接返回，否则循环重试
                    return true;
                }

                Thread.sleep(INTERVAL_TIMES);
            }

        } finally {
            if (jedis != null) {
                closeClient(jedis);
            }
        }
        return false;
    }

    /**
     * 释放锁
     *
     * @return
     */
    public boolean release() throws InterruptedException {
        return release(Integer.MAX_VALUE);
    }

    /**
     * 释放锁
     *
     * @return
     */
    public boolean release(long releaseTimeoutInMillis) throws InterruptedException {

        Jedis jedis = null;
        try {
            jedis = getClient();
            return release(jedis, releaseTimeoutInMillis);
        } finally {
            if (jedis != null) {
                closeClient(jedis);
            }
        }
    }

    /**
     * 释放锁
     *
     * @param jedis
     * @param releaseTimeoutInMillis
     * @return
     */
    private boolean release(Jedis jedis, long releaseTimeoutInMillis) throws InterruptedException {
        Lock cLock = lockThreadLocal.get();
        if (cLock == null) {
            System.out.println("lock is null!");
        }
        if (cLock != null) {
            String luaScript = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

            long releaseTime = System.currentTimeMillis();
            long expiryTime = System.currentTimeMillis() + releaseTimeoutInMillis; // 锁的释放到期时间

            while (expiryTime >= System.currentTimeMillis()) {
                Object result = jedis.eval(luaScript, Collections.singletonList(this.lockKey),
                        Collections.singletonList(cLock.toString()));
                if (((Long) result) == 1L) {
                    lockThreadLocal.remove();
                    return true;
                }

                Thread.sleep(INTERVAL_TIMES);
            }
        }
        return false;
    }


    /**
     * 锁
     */
    protected static class Lock {

        private String uid; // lock 唯一标识

        Lock(String uid) {
            this.uid = uid;
        }

        public String getUid() {
            return uid;
        }

        @Override
        public String toString() {
            return JSON.toJSONString(this, false);
        }
    }

}
