package com.golf.course.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.golf.course.service.HandicapService;

/**
 * 启动回填：首次升级到差点功能、库里还没有任何计算日志时，
 * 依据已签核记分卡一次性建好全部球员的初始差点链（按各自完赛日回放当时规则）。
 * 之后重启不再执行，历史链以日志表为准。
 */
@Component
public class HandicapBootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(HandicapBootstrapRunner.class);

    private final HandicapService handicapService;

    public HandicapBootstrapRunner(HandicapService handicapService) {
        this.handicapService = handicapService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            int logs = handicapService.bootstrapIfEmpty();
            if (logs > 0) {
                log.info("差点指数初始回填完成，共生成 {} 个历史计算节点", logs);
            }
        } catch (Exception e) {
            // 回填失败不阻断应用启动；管理员仍可用全量重算手动触发
            log.error("差点指数初始回填失败（不影响启动，可通过全量重算重试）：{}", e.getMessage(), e);
        }
    }
}
