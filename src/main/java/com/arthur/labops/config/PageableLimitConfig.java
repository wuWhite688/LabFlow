package com.arthur.labops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

@Configuration
public class PageableLimitConfig {

    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableMaxSize() {
        return resolver -> resolver.setMaxPageSize(100);
    }
}
