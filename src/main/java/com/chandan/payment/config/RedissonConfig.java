package com.chandan.payment.config;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean
    @ConditionalOnProperty(name = "payment.standalone-demo.enabled", havingValue = "false", matchIfMissing = true)
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionMinimumIdleSize(5)
                .setConnectionPoolSize(25)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1000);
        return Redisson.create(config);
    }

    /**
     * Standalone demo mode: returns dynamic mock RedissonClient using standard Java dynamic proxy.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "payment.standalone-demo.enabled", havingValue = "true")
    public RedissonClient mockRedissonClient() {
        ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("getLock".equals(method.getName()) && args != null && args.length > 0) {
                            String lockName = (String) args[0];
                            ReentrantLock internalLock = localLocks.computeIfAbsent(lockName, k -> new ReentrantLock());
                            return createMockRLock(internalLock);
                        } else if ("getMultiLock".equals(method.getName())) {
                            return createMockMultiLock();
                        }
                        return null;
                    }
                }
        );
    }

    private static RLock createMockRLock(ReentrantLock internalLock) {
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("tryLock".equals(name)) {
                        if (args != null && args.length == 3) {
                            return internalLock.tryLock((Long) args[0], (TimeUnit) args[2]);
                        }
                        return internalLock.tryLock();
                    } else if ("unlock".equals(name)) {
                        if (internalLock.isHeldByCurrentThread()) {
                            internalLock.unlock();
                        }
                        return null;
                    } else if ("isHeldByCurrentThread".equals(name)) {
                        return internalLock.isHeldByCurrentThread();
                    }
                    return null;
                }
        );
    }

    private static RLock createMockMultiLock() {
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("tryLock".equals(name)) {
                        return true;
                    } else if ("isHeldByCurrentThread".equals(name)) {
                        return true;
                    }
                    return null;
                }
        );
    }
}
