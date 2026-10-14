package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //1.从redis查询店铺类型缓存（存的是list结构，每个元素是一条ShopType的json串）
        List<String> jsonList = stringRedisTemplate.opsForList().range(SHOP_TYPE_KEY, 0, -1);
        //2.命中缓存，把json反序列化回对象后直接返回
        if (!CollectionUtils.isEmpty(jsonList)) {
            return jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
        }
        //3.未命中，查询数据库（sort 是前端分类栏的展示顺序）
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4.库里也没有。店铺类型是基础数据，正常不会为空，所以这里不做缓存空值处理
        if (CollectionUtils.isEmpty(typeList)) {
            return Collections.emptyList();
        }
        //5.写入redis缓存。这批数据几乎不变，不设TTL，等后台改动时再删掉缓存
        stringRedisTemplate.opsForList().rightPushAll(SHOP_TYPE_KEY,
                typeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList()));
        //6.返回
        return typeList;
    }
}
