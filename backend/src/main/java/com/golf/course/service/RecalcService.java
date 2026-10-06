package com.golf.course.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.dto.RecalcBatchView;
import com.golf.course.entity.Player;
import com.golf.course.entity.RecalcBatch;
import com.golf.course.entity.RecalcBatchFailure;
import com.golf.course.repository.HandicapRoundRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.RecalcBatchFailureRepository;
import com.golf.course.repository.RecalcBatchRepository;

/**
 * 差点重算批次：全量重算按赛事日期分批，异步执行并记录逐球员失败明细。
 *
 * <p>性能设计（十万条逐洞成绩）：
 * <ol>
 *   <li>先用一条聚合 SQL 重建涉及日期区间的 {@code handicap_round} 派生层（不逐球员扫成绩）；</li>
 *   <li>球员集合来自区间内 {@code distinct player_id}，而非遍历全部球员；</li>
 *   <li>每个球员独立事务重算链，单个失败只记 {@code recalc_batch_failure}，不回滚整批。</li>
 * </ol>
 */
@Service
public class RecalcService {

    private static final Logger log = LoggerFactory.getLogger(RecalcService.class);
    private static final DateTimeFormatter NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final LocalDate FAR_PAST = LocalDate.of(1900, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2999, 12, 31);

    private final RecalcBatchRepository batchRepository;
    private final RecalcBatchFailureRepository failureRepository;
    private final HandicapRoundRepository roundRepository;
    private final PlayerRepository playerRepository;
    private final HandicapService handicapService;
    private final RecalcBatchStateService stateService;

    public RecalcService(RecalcBatchRepository batchRepository,
                         RecalcBatchFailureRepository failureRepository,
                         HandicapRoundRepository roundRepository,
                         PlayerRepository playerRepository,
                         HandicapService handicapService,
                         RecalcBatchStateService stateService) {
        this.batchRepository = batchRepository;
        this.failureRepository = failureRepository;
        this.roundRepository = roundRepository;
        this.playerRepository = playerRepository;
        this.handicapService = handicapService;
        this.stateService = stateService;
    }

    /**
     * 创建全量重算批次（可带日期区间做分批执行）。立即返回「排队中」批次，后台异步跑。
     */
    @Transactional
    public RecalcBatch createFullBatch(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BizException("日期区间非法：开始日期晚于结束日期");
        }
        return batchRepository.save(newBatch(Dict.BATCH_FULL, from, to, null, null));
    }

    /**
     * 定向重算：补齐梯台参数后，只重算受影响球员。
     *
     * @param playerId 指定单个球员；为空则按梯台反查所有绑定该梯台的球员
     * @param ratingId 刚补齐/修正的梯台（配合 playerId 为空时圈定球员）
     */
    @Transactional
    public RecalcBatch createTargetBatch(Long playerId, Long ratingId) {
        if (playerId == null && ratingId == null) {
            throw new BizException("定向重算必须指定球员或梯台");
        }
        if (playerId != null) {
            playerRepository.findById(playerId)
                    .orElseThrow(() -> new BizException("球员不存在：" + playerId));
        }
        return batchRepository.save(newBatch(Dict.BATCH_TARGET, null, null, playerId, ratingId));
    }

    private RecalcBatch newBatch(String type, LocalDate from, LocalDate to,
                                 Long playerId, Long ratingId) {
        RecalcBatch b = new RecalcBatch();
        b.setBatchNo("RC-" + type.charAt(0) + "-" + LocalDateTime.now().format(NO));
        b.setBatchType(type);
        b.setStatus(Dict.BATCH_QUEUED);
        b.setDateFrom(from);
        b.setDateTo(to);
        b.setScopePlayerId(playerId);
        b.setScopeRatingId(ratingId);
        b.setTotalPlayers(0);
        b.setSuccessPlayers(0);
        b.setFailedPlayers(0);
        b.setTotalLogs(0);
        b.setFailCount(0);
        b.setCreatedAt(LocalDateTime.now());
        return b;
    }

    // ================================================================
    // 异步执行（控制器在批次事务提交后调用，异步线程能查到批次行）
    // ================================================================

    @Async("handicapRecalcExecutor")
    public void runBatch(Long batchId) {
        RecalcBatch batch;
        try {
            batch = batchRepository.findById(batchId).orElse(null);
            if (batch == null || !Dict.BATCH_QUEUED.equals(batch.getStatus())) {
                return;
            }
        } catch (Exception e) {
            log.error("重算批次 {} 无法加载", batchId, e);
            return;
        }
        stateService.markRunning(batchId);
        try {
            if (Dict.BATCH_TARGET.equals(batch.getBatchType())) {
                runTarget(batch);
            } else {
                runFull(batch);
            }
        } catch (Exception e) {
            log.error("重算批次 {} 整体失败", batchId, e);
            stateService.recordFailure(batchId, null, null, "BATCH", safe(e.getMessage()));
            stateService.finish(batchId, Dict.BATCH_FAILED, "批次执行中断：" + safe(e.getMessage()));
        }
    }

    private void runFull(RecalcBatch batch) {
        // 1) 区间内派生层一次性重建（一条聚合 SQL；服务端按球员归组，不逐球员扫全部成绩）
        List<Long> players;
        try {
            handicapService.rebuildDerivedRoundsInNewTx(
                    null, batch.getDateFrom(), batch.getDateTo());
            players = roundRepository.findPlayerIdsInRange(
                    batch.getDateFrom() == null ? FAR_PAST : batch.getDateFrom(),
                    batch.getDateTo() == null ? FAR_FUTURE : batch.getDateTo());
        } catch (Exception e) {
            stateService.recordFailure(batch.getId(), null, null, "REBUILD_ROUNDS", safe(e.getMessage()));
            stateService.finish(batch.getId(), Dict.BATCH_FAILED, "派生层重建失败：" + safe(e.getMessage()));
            return;
        }

        int[] counters = runPlayers(batch.getId(), players, batch.getDateFrom(), Dict.TRIGGER_FULL);
        stateService.updateCounters(batch.getId(), players.size(), counters[0], counters[1], counters[2]);
        stateService.finish(batch.getId(),
                counters[1] == 0 ? Dict.BATCH_DONE : Dict.BATCH_PARTIAL,
                counters[1] == 0 ? null : counters[1] + " 名球员重算失败，详见失败明细");
    }

    private void runTarget(RecalcBatch batch) {
        Set<Long> playerIds = new LinkedHashSet<>();
        try {
            if (batch.getScopePlayerId() != null) {
                handicapService.rebuildDerivedRoundsInNewTx(batch.getScopePlayerId(), null, null);
                playerIds.add(batch.getScopePlayerId());
            } else {
                // 补齐梯台参数：先重建绑定该梯台的全部已签核卡，再按梯台反查受影响球员
                handicapService.rebuildDerivedRoundsForRatingInNewTx(batch.getScopeRatingId());
                playerIds.addAll(handicapService.playerIdsByRating(batch.getScopeRatingId()));
            }
        } catch (Exception e) {
            stateService.recordFailure(batch.getId(), batch.getScopePlayerId(), null,
                    "REBUILD_ROUNDS", safe(e.getMessage()));
            stateService.finish(batch.getId(), Dict.BATCH_FAILED,
                    "定向重建派生层失败：" + safe(e.getMessage()));
            return;
        }

        List<Long> players = new ArrayList<>(playerIds);
        int[] counters = runPlayers(batch.getId(), players, null, Dict.TRIGGER_TARGET);
        stateService.updateCounters(batch.getId(), players.size(), counters[0], counters[1], counters[2]);
        stateService.finish(batch.getId(),
                counters[1] == 0 ? Dict.BATCH_DONE : Dict.BATCH_PARTIAL,
                counters[1] == 0 ? null : counters[1] + " 名球员重算失败，详见失败明细");
    }

    /** 逐球员独立事务重算；返回 [成功数, 失败数, 新日志数]。 */
    private int[] runPlayers(Long batchId, List<Long> playerIds, LocalDate from, String trigger) {
        int success = 0;
        int failed = 0;
        int totalLogs = 0;
        for (Long playerId : playerIds) {
            try {
                totalLogs += handicapService.rebuildChainInNewTx(playerId, from, trigger, batchId);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("重算批次 {} 球员 {} 失败：{}", batchId, playerId, e.getMessage());
                stateService.recordFailure(batchId, playerId, null, "CALC_CHAIN", safe(e.getMessage()));
            }
        }
        return new int[]{success, failed, totalLogs};
    }

    // ---------------- 查询 ----------------

    public List<RecalcBatch> listBatches() {
        return batchRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public RecalcBatchView getBatchView(Long id) {
        RecalcBatch b = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("重算批次不存在：" + id));
        List<RecalcBatchFailure> raw = failureRepository.findByBatchIdOrderByIdAsc(id);
        List<Long> pids = raw.stream().map(RecalcBatchFailure::getPlayerId)
                .filter(Objects::nonNull).distinct().toList();
        var players = new java.util.HashMap<Long, String>();
        for (Player p : playerRepository.findAllById(pids)) {
            players.put(p.getId(), p.getName());
        }
        List<RecalcBatchView.FailureView> failures = new ArrayList<>();
        for (RecalcBatchFailure f : raw) {
            failures.add(new RecalcBatchView.FailureView(
                    f.getId(), f.getPlayerId(),
                    f.getPlayerId() == null ? null : players.get(f.getPlayerId()),
                    f.getAsOfDate(), f.getStage(), f.getErrorMessage()));
        }
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return new RecalcBatchView(
                b.getId(), b.getBatchNo(), b.getBatchType(), b.getStatus(),
                b.getDateFrom(), b.getDateTo(), b.getScopePlayerId(), b.getScopeRatingId(),
                b.getTotalPlayers(), b.getSuccessPlayers(), b.getFailedPlayers(),
                b.getTotalLogs(), b.getFailCount(), b.getFailDetail(),
                b.getStartedAt() == null ? null : b.getStartedAt().format(dtf),
                b.getFinishedAt() == null ? null : b.getFinishedAt().format(dtf),
                failures);
    }

    private String safe(String msg) {
        if (msg == null) {
            return "未知错误";
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
