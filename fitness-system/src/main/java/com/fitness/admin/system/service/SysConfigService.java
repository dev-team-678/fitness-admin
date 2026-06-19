package com.fitness.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fitness.admin.system.entity.SysConfig;
import com.fitness.admin.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysConfigService {

    public static final String CACHE_NAME = "dict";
    public static final String CACHE_KEY = "'sysConfig:all'";

    private final SysConfigMapper sysConfigMapper;

    @Cacheable(value = CACHE_NAME, key = CACHE_KEY)
    public List<SysConfig> list() {
        return sysConfigMapper.selectList(null);
    }

    public List<SysConfig> listByKeyPrefix(String keyPrefix) {
        QueryWrapper<SysConfig> wrapper = new QueryWrapper<>();
        wrapper.likeRight("config_key", keyPrefix);
        return sysConfigMapper.selectList(wrapper);
    }

    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void save(SysConfig config) {
        if (config.getId() == null) {
            sysConfigMapper.insert(config);
        } else {
            sysConfigMapper.updateById(config);
        }
    }

    /**
     * 批量按 key 写入,避免循环里逐条 selectOne+updateById 造成 N+1。
     * 先一次查出所有匹配 key,内存里分流 insert/update,最后批量执行。
     * 入参 map 为空时直接返回,无需任何 DB 交互。
     */
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public void saveByKey(String configKey, String configValue, String description) {
        QueryWrapper<SysConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_key", configKey);
        SysConfig existing = sysConfigMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setConfigValue(configValue);
            if (description != null) {
                existing.setDescription(description);
            }
            sysConfigMapper.updateById(existing);
        } else {
            SysConfig config = new SysConfig();
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setDescription(description);
            sysConfigMapper.insert(config);
        }
    }

    /**
     * 批量 upsert:一次 SELECT + 内存分流 + 批量 INSERT/UPDATE,事务内执行。
     * N 个 key 只产生 1 次 SELECT + 最多 N 次 UPDATE + N 次 INSERT,不再循环 N 次 SELECT。
     */
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public void saveByKeyBatch(Map<String, String> keyValueMap) {
        if (keyValueMap == null || keyValueMap.isEmpty()) {
            return;
        }
        QueryWrapper<SysConfig> wrapper = new QueryWrapper<>();
        wrapper.in("config_key", keyValueMap.keySet());
        List<SysConfig> existingList = sysConfigMapper.selectList(wrapper);
        Map<String, SysConfig> existingMap = new HashMap<>(existingList.size() * 2);
        for (SysConfig c : existingList) {
            existingMap.put(c.getConfigKey(), c);
        }
        for (Map.Entry<String, String> e : keyValueMap.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            SysConfig existing = existingMap.get(key);
            if (existing != null) {
                existing.setConfigValue(value);
                sysConfigMapper.updateById(existing);
            } else {
                SysConfig config = new SysConfig();
                config.setConfigKey(key);
                config.setConfigValue(value);
                if (StringUtils.hasText(key) && key.length() <= 256) {
                    // description 由 controller 单独处理,这里不写
                }
                sysConfigMapper.insert(config);
            }
        }
    }

    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void delete(Long id) {
        sysConfigMapper.deleteById(id);
    }
}
