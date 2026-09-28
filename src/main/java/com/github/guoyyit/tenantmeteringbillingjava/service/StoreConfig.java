package com.github.guoyyit.tenantmeteringbillingjava.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地存储相关 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties(StoreProperties.class)
public class StoreConfig {

    @Bean
    public Engine engine(StoreProperties properties,
                         DefaultStateApplier applier,
                         RecoveryService recoveryService) {
        Engine engine = new Engine();
        engine.init(properties, applier, recoveryService);
        return engine;
    }
}
