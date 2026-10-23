package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.IntegerOutput;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShop(){
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void testLoadShopGeoData(){
        shopService.loadShopGeoData();
    }

    /**
     * HyperLogLog 百万数据测试：往同一个 key 写入 100 万个不同的 userId，
     * 验证统计误差、耗时和内存占用（约 12KB，且不随数据量增长）。
     */
    @Test
    void testHyperLogLogMillion() {
        String key = "test:uv:hyperloglog";
        stringRedisTemplate.delete(key);

        // 每攒满 1000 条批量写入一次，减少网络往返
        String[] values = new String[1000];
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1_000_000; i++) {
            int j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add(key, values);
            }
        }
        long cost = System.currentTimeMillis() - start;

        Long count = stringRedisTemplate.opsForHyperLogLog().size(key);
        // MEMORY USAGE 返回该 key 实际占用的字节数
        Long memory = memoryUsage(key);

        double error = Math.abs(count - 1_000_000) * 100.0 / 1_000_000;
        System.out.printf("写入 1000000 条，耗时 %d ms%n", cost);
        System.out.printf("HyperLogLog 统计结果：%d，相对误差：%.3f%%%n", count, error);
        System.out.printf("内存占用：%d 字节（约 %.1f KB）%n", memory, memory / 1024.0);
        System.out.printf("对比：用 Set 存 100 万个 userId（按每个 long 8 字节算）至少约 %d MB%n",
                1_000_000L * 8 / 1024 / 1024);

        // HyperLogLog 标准误差约 0.81%，这里放宽到 2%
        assertTrue(error < 2, "HyperLogLog 统计误差超过 2%");
        // 官方实现中 HLL 固定占用约 12KB
        assertTrue(memory < 20 * 1024, "HyperLogLog 内存占用异常");
    }

    /**
     * 执行 MEMORY USAGE 命令。
     * MEMORY 是整数回复，Spring Data Redis 的 execute(String, byte[]...) 不知道返回类型，
     * 需要显式指定 IntegerOutput，否则解码时会抛 IllegalStateException。
     */
    private Long memoryUsage(String key) {
        RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            // 只有 LettuceConnection 提供了带 CommandOutput 的 execute 重载
            return (Long) ((LettuceConnection) connection).execute("MEMORY",
                    new IntegerOutput<>(ByteArrayCodec.INSTANCE),
                    "USAGE".getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8));
        } finally {
            connection.close();
        }
    }
}
