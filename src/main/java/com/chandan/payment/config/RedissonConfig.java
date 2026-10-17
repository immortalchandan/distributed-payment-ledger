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
import java.util.ArrayList;
import java.util.List;
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
     * Standalone demo mode: returns dynamic mock RedissonClient with full Object method handling (hashCode, equals, toString).
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
                        String name = method.getName();
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        } else if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        } else if ("toString".equals(name)) {
                            return "MockRedissonClient@" + Integer.toHexString(System.identityHashCode(proxy));
                        } else if ("getLock".equals(name) && args != null && args.length > 0) {
                            String lockName = (String) args[0];
                            ReentrantLock internalLock = localLocks.computeIfAbsent(lockName, k -> new ReentrantLock(true));
                            return createMockRLock(internalLock);
                        } else if ("getMultiLock".equals(name)) {
                            if (args != null && args.length > 0 && args[0] instanceof RLock[]) {
                                return createMockMultiLock((RLock[]) args[0]);
                            } else if (args != null && args.length > 0) {
                                RLock[] locks = new RLock[args.length];
                                for (int i = 0; i < args.length; i++) {
                                    locks[i] = (RLock) args[i];
                                }
                                return createMockMultiLock(locks);
                            }
                            return createMockMultiLock(new RLock[0]);
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
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    } else if ("toString".equals(name)) {
                        return "MockRLock@" + Integer.toHexString(System.identityHashCode(proxy));
                    } else if ("tryLock".equals(name)) {
                        if (args != null && args.length >= 3) {
                            return internalLock.tryLock((Long) args[0], (TimeUnit) args[2]);
                        } else if (args != null && args.length >= 2) {
                            return internalLock.tryLock((Long) args[0], (TimeUnit) args[1]);
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

    private static RLock createMockMultiLock(RLock[] locks) {
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    } else if ("toString".equals(name)) {
                        return "MockMultiLock@" + Integer.toHexString(System.identityHashCode(proxy));
                    } else if ("tryLock".equals(name)) {
                        long waitTime = (args != null && args.length >= 1) ? (Long) args[0] : 0L;
                        TimeUnit unit = (args != null && args.length >= 3) ? (TimeUnit) args[2] :
                                (args != null && args.length >= 2 ? (TimeUnit) args[1] : TimeUnit.SECONDS);
                        long deadline = System.currentTimeMillis() + unit.toMillis(waitTime);

                        List<RLock> acquired = new ArrayList<>();
                        for (RLock lock : locks) {
                            long remainingMs = deadline - System.currentTimeMillis();
                            if (remainingMs <= 0) {
                                for (RLock acq : acquired) {
                                    if (acq.isHeldByCurrentThread()) acq.unlock();
                                }
                                return false;
                            }
                            boolean ok = lock.tryLock(remainingMs, TimeUnit.MILLISECONDS);
                            if (!ok) {
                                for (RLock acq : acquired) {
                                    if (acq.isHeldByCurrentThread()) acq.unlock();
                                }
                                return false;
                            }
                            acquired.add(lock);
                        }
                        return true;
                    } else if ("unlock".equals(name)) {
                        for (int i = locks.length - 1; i >= 0; i--) {
                            RLock lock = locks[i];
                            if (lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                        }
                        return null;
                    } else if ("isHeldByCurrentThread".equals(name)) {
                        for (RLock lock : locks) {
                            if (!lock.isHeldByCurrentThread()) {
                                return false;
                            }
                        }
                        return true;
                    }
                    return null;
                }
        );
    }
}
