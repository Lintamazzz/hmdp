package com.hmdp;

import com.hmdp.utils.RedisIdWorkder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorkder redisIdWorkder;


    private ExecutorService es = Executors.newFixedThreadPool(500);


    @Test
    void testIdWorker1() {
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 30000; i++) {
            long id = redisIdWorkder.nextId("order");
            System.out.println("id = " + id);
        }
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testIdWorker2() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorkder.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.wait();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

}
