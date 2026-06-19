package com.fitness.admin.system.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 调 Nacos Open API 用的 RestTemplate 配置。
 * 设短超时,避免 Nacos 不可达时把保存接口拖到默认 30s 才返回。
 */
@Configuration
public class NacosRestTemplateConfig {

    @Bean(name = "nacosRestTemplate")
    public RestTemplate nacosRestTemplate(RestTemplateBuilder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return builder.requestFactory(() -> factory).build();
    }
}