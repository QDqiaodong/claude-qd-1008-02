package com.golf.course.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 差点全量/定向重算为后台异步任务：一个批次一个线程串行执行，
 * 避免长批量计算阻塞页面请求；线程池仅 1 个活动线程，保证批次顺序可控。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("handicapRecalcExecutor")
    public Executor handicapRecalcExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("handicap-recalc-");
        executor.initialize();
        return executor;
    }
}
