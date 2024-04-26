package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorkder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorkder redisIdWorkder;
    private ExecutorService es = Executors.newFixedThreadPool(500);


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;


    @Test
    void loadShopGeoData() {
        // 1. 查询店铺数据
        List<Shop> list = shopService.list();
        // 2. 按 typeId 分组，放到各自的 geo 集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 分批写入 redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取 typeId 和 shop 列表
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();

            // 这种写法每个店铺都要发一次请求，效率很低
//            for (Shop shop : shops) {
//                stringRedisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_KEY + typeId, new Point(shop.getX(), shop.getY()), shop.getId().toString());
//            }

            // 一次性把该类型所有店铺都导入
            List<RedisGeoCommands.GeoLocation<String>> locations = shops.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_KEY + typeId, locations);
        }
    }


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
