package com.golf.course.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.entity.RecalcBatch;
import com.golf.course.entity.RecalcBatchFailure;
import com.golf.course.repository.RecalcBatchFailureRepository;
import com.golf.course.repository.RecalcBatchRepository;

/**
 * 重算批次状态短事务：异步线程内逐次更新状态/计数/失败明细，
 * 独立成 bean 是为了让 {@code @Transactional} 经代理生效（避免同类自调用失效）。
 */
@Service
public class RecalcBatchStateService {

    private final RecalcBatchRepository batchRepository;
    private final RecalcBatchFailureRepository failureRepository;

    public RecalcBatchStateService(RecalcBatchRepository batchRepository,
                                   RecalcBatchFailureRepository failureRepository) {
        this.batchRepository = batchRepository;
        this.failureRepository = failureRepository;
    }

    @Transactional
    public void markRunning(Long batchId) {
        batchRepository.findById(batchId).ifPresent(b -> {
            b.setStatus("执行中");
            b.setStartedAt(LocalDateTime.now());
        });
    }

    @Transactional
    public void updateCounters(Long batchId, int total, int success, int failed, int logs) {
        batchRepository.findById(batchId).ifPresent(b -> {
            b.setTotalPlayers(total);
            b.setSuccessPlayers(success);
            b.setFailedPlayers(failed);
            b.setTotalLogs(logs);
        });
    }

    @Transactional
    public void recordFailure(Long batchId, Long playerId, java.time.LocalDate asOf,
                              String stage, String msg) {
        RecalcBatchFailure f = new RecalcBatchFailure();
        f.setBatchId(batchId);
        f.setPlayerId(playerId);
        f.setAsOfDate(asOf);
        f.setStage(stage);
        f.setErrorMessage(msg);
        f.setCreatedAt(LocalDateTime.now());
        failureRepository.save(f);
        batchRepository.findById(batchId).ifPresent(b ->
                b.setFailCount((b.getFailCount() == null ? 0 : b.getFailCount()) + 1));
    }

    @Transactional
    public void finish(Long batchId, String status, String detail) {
        RecalcBatch b = batchRepository.findById(batchId).orElse(null);
        if (b == null) {
            return;
        }
        b.setStatus(status);
        b.setFailDetail(detail);
        b.setFinishedAt(LocalDateTime.now());
    }
}
