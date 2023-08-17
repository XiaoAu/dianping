package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name; //锁的名字
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:"; //key的前缀
    //使用UUID给每个jvm生成标识(分布式系统或集群情况下)，final修饰在同一台jvm中不会变，在不同的jvm中生成的值是不同的
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //类加载便初始化Lua脚本，不要每次释放锁再去加载
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua")); //到类路径下找脚本
        UNLOCK_SCRIPT.setResultType(Long.class); //脚本执行后返回值类型
    }


    //尝试获取锁
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标示(用UUID加线程id表示，若只用线程id，在不同的jvm中可能回有相同id的线程，所以加上UUID)
        String threadId = ID_PREFIX + Thread.currentThread().getId(); //UUID来区分不同的jvm，线程id来区分不同的线程，避免删错锁
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); //不直接返回success，因为其是包装类而方法的返回类型是基本数据类型，自动拆箱装箱过程中可以回出现空指针
    }

    //释放锁(方法一，多行代码，可能出现误删其它线程锁的情况)
    /*@Override
    public void unlock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId(); //UUID来区分不同的jvm，线程id来区分不同的线程，避免删错锁
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标识是否一致
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/

    //释放锁(方法二，一行代码，使用Lua脚本保证释放锁操作的原子性，避免误删锁)
    @Override
    public void unlock() {
        //调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, //Lua脚本
                Collections.singletonList(KEY_PREFIX + name), //key参数，只需一个但需要集合类型所以使用单元素集合
                ID_PREFIX + Thread.currentThread().getId()); //value参数，线程标识
    }
}
