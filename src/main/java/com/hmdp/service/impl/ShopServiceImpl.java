package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithPassMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop=cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL, TimeUnit.MINUTES);



        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        //返回
        return Result.ok(shop);
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key=CACHE_SHOP_KEY+id;
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //不存在，直接返回
            return null;
        }
        //命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return shop;
        }
        //已过期，需要缓存重建
        //获取互斥锁
        String lockKey= LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if(isLock){
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回过期的商铺信息
        return shop;
    }

    public Shop queryWithPassMutex(Long id){
        String key=CACHE_SHOP_KEY+id;
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在，直接返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        //判断命中的是否是空值
        if(shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //实现缓存重建
        //获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop=null;
        try{
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock) {
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithPassMutex(id);
            }
            //成功，根据id查询数据库
            shop = getById(id);
            //不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }
        //返回
        return shop;
    }

//    public Shop queryWithPassThrough(Long id){
//        String key=CACHE_SHOP_KEY+id;
//        //从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            //存在，直接返回
//            return JSONUtil.toBean(shopJson,Shop.class);
//        }
//        //判断命中的是否是空值
//        if(shopJson!=null){
//            //返回一个错误信息
//            return null;
//        }
//        //不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //不存在，返回错误
//        if (shop == null) {
//            //将空值写入redis
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            //返回错误信息
//            return null;
//        }
//        //存在，写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //返回
//        return shop;
//    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //2.设置过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public void loadShopGeoData() {
        // 1.按 typeId 分组，构造 GeoLocation（member=shopId，坐标=经度、纬度）
        Map<Long, List<RedisGeoCommands.GeoLocation<String>>> typeMap = new HashMap<>();
        for (Shop shop : list()) {
            if (shop.getTypeId() == null || shop.getX() == null || shop.getY() == null) {
                continue;
            }
            typeMap.computeIfAbsent(shop.getTypeId(), k -> new ArrayList<>())
                    .add(new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())));
        }

        // 2.按类型写入 Redis GEO，key = shop:geo:{typeId}
        GeoOperations<String, String> geoOps = stringRedisTemplate.opsForGeo();
        typeMap.forEach((typeId, locations) -> {
            String key = SHOP_GEO_KEY + typeId;
            // 全量重建：先删旧 key，避免已删除/改类型的店铺残留
            stringRedisTemplate.delete(key);
            for (int i = 0; i < locations.size(); i += 500) {
                int end = Math.min(i + 500, locations.size());
                geoOps.geoAdd(key, locations.subList(i, end));
            }
        });
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        int page = (current == null || current < 1) ? 1 : current;

        // 没有坐标：保留原来的数据库分页
        if (x == null || y == null) {
            Page<Shop> dbPage = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(page, pageSize));
            return Result.ok(dbPage.getRecords());
        }

        long offset = (long) (page - 1) * pageSize;
        // GeoRadiusCommandArgs.limit 只有 COUNT，没有 offset，先多取再截取
        long count = offset + pageSize;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().geoRadius(
                        SHOP_GEO_KEY + typeId,
                        new Circle(new Point(x, y),
                                new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(count));

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content =
                results == null ? Collections.emptyList() : results.getContent();
        if (content.size() <= offset) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageContent =
                content.subList((int) offset, (int) Math.min(content.size(), count));

        List<Long> ids = new ArrayList<>(pageContent.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> item : pageContent) {
            ids.add(Long.valueOf(item.getContent().getName()));
        }

        // listByIds 不保证顺序，按 GEO 的距离顺序重建
        Map<Long, Shop> shopMap = new HashMap<>();
        for (Shop shop : listByIds(ids)) {
            shopMap.put(shop.getId(), shop);
        }

        List<Shop> shops = new ArrayList<>(pageContent.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> item : pageContent) {
            Shop shop = shopMap.get(Long.valueOf(item.getContent().getName()));
            if (shop != null) {
                shop.setDistance(item.getDistance().getValue()); // 单位：米
                shops.add(shop);
            }
        }
        return Result.ok(shops);
    }

}
