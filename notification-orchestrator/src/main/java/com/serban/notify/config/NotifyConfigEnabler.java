package com.serban.notify.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotifyConfig.class)
public class NotifyConfigEnabler {
}
