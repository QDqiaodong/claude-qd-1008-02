package com.golf.course.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.dto.HandicapProfile;
import com.golf.course.entity.CourseRating;
import com.golf.course.entity.HandicapIndexLog;
import com.golf.course.entity.HandicapIndexRound;
import com.golf.course.entity.HandicapRound;
import com.golf.course.entity.HandicapRule;
import com.golf.course.entity.Player;
import com.golf.course.entity.Scorecard;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.CourseRatingRepository;
import com.golf.course.repository.HandicapIndexLogRepository;
import com.golf.course.repository.HandicapIndexRoundRepository;
import com.golf.course.repository.HandicapRoundRepository;
import com.golf.course.repository.HandicapRuleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScorecardRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 差点指数重算核心。
 *
 * <h2>口径</h2>
 * <ol>
 *   <li><b>只认已签核轮次</b>：派生层 {@code handicap_round} 一行一卡，数据来自签核固化快照；
 *       草稿 / 待确认 / 争议中的卡一律缺席。</li>
 *   <li><b>规则生效边界</b>：计算某截止日 asOfDate 时，只选 {@code effective_date ≤ asOfDate}
 *       的最新启用规则；新规则永远算不进它生效日之前的历史节点，历史差点因此可按当时规则回放。</li>
 *   <li><b>差值</b>：{@code diff = 113 / slope ×（封顶后总杆 - courseRating）}，一位小数四舍五入。
 *       梯台缺失的轮次不带差值，窗口内占位展示并标注「难度参数缺失」。</li>
 *   <li><b>样本</b>：取最近 {@code windowRounds} 轮（含缺参数轮）；缺参数与异常差值轮不计入有效样本；
 *       有效样本少于 {@code minRounds}（默认 3）记 INSUFFICIENT「样本不足」。</li>
 *   <li><b>选轮</b>：按规则选轮表（WHS 口径，样本 3 取最低 1 个…20 取 10 个）选最低差值，
 *       平均后减 2（WHS 差值调整），四舍五入一位小数。</li>
 *   <li><b>异常防护</b>：某轮差值高出「窗口内最佳差值」超 {@code maxDiffBelowIndex}
 *       即判异常排除（防单洞录错把总杆抬爆）；单次重算指数相对上一节点下调超过
 *       {@code maxDecreasePerRound} 则截断（防录入错误让差点突然失真），上调不限制。</li>
 * </ol>
 *
 * <h2>可追溯</h2>
 * 每次计算追加 {@link HandicapIndexLog} + 逐轮 {@link HandicapIndexRound}（采用/排除原因），
 * 球员表四个指数字段只是当前链最新节点的缓存，任何接口都不允许直接写。
 * 重算（定向/全量）先把球员 LIVE 链整体标 SUPERSEDED（保留行、指向批次），再追加新链，
 * 历史指数永不被新口径物理改写。
 */
@Service
public class HandicapService {

    private static final BigDecimal SLOPE_STANDARD = new BigDecimal("113");
    private static final BigDecimal DIFF_ADJUSTMENT = new BigDecimal("2.0");

    private final HandicapRoundRepository roundRepository;
    private final HandicapRuleRepository ruleRepository;
    private final HandicapIndexLogRepository logRepository;
    private final HandicapIndexRoundRepository indexRoundRepository;
    private final CourseRatingRepository ratingRepository;
    private final PlayerRepository playerRepository;
    private final TournamentRepository tournamentRepository;
    private final ScorecardRepository scorecardRepository;
    private final ObjectMapper objectMapper;

    public HandicapService(HandicapRoundRepository roundRepository,
                           HandicapRuleRepository ruleRepository,
                           HandicapIndexLogRepository logRepository,
                           HandicapIndexRoundRepository indexRoundRepository,
                           CourseRatingRepository ratingRepository,
                           PlayerRepository playerRepository,
                           TournamentRepository tournamentRepository,
                           ScorecardRepository scorecardRepository,
                           ObjectMapper objectMapper) {
        this.roundRepository = roundRepository;
        this.ruleRepository = ruleRepository;
        this.logRepository = logRepository;
        this.indexRoundRepository = indexRoundRepository;
        this.ratingRepository = ratingRepository;
        this.playerRepository = playerRepository;
        this.tournamentRepository = tournamentRepository;
        this.scorecardRepository = scorecardRepository;
        this.objectMapper = objectMapper;
    }

    // ================================================================
    // 派生层：签核卡 -> handicap_round
    // ================================================================

    /**
     * 按单张已签核卡重建派生行（签核动作实时调用，或补梯台后定向调用）。
     * 总杆/逐洞只读固化快照；梯台缺失时 {@code ratingMissing=true}、diff 留空。
     */
    @Transactional
    public HandicapRound rebuildDerivedRound(Long scorecardId) {
        HandicapRoundRepository.SignedRoundAggregate agg =
                roundRepository.aggregateSignedCards(null, scorecardId, null, null)
                        .stream().findFirst()
                        .orElseThrow(() -> new BizException("记分卡 " + scorecardId + " 尚不是已签核状态，不能派生差点轮次"));
        return upsertDerived(agg);
    }

    /**
     * 批量重建派生层（定向/全量重算前调用）。取数是一条 SQL，
     * 十万条逐洞成绩下也只扫 scorecard 主单 + 解析快照，不会逐球员反复扫描全部成绩。
     *
     * @return 本次受影响的球员 id（去重）
     */
    @Transactional
    public List<Long> rebuildDerivedRounds(Long playerId, LocalDate from, LocalDate to) {
        List<HandicapRoundRepository.SignedRoundAggregate> aggs =
                roundRepository.aggregateSignedCards(playerId, null, from, to);
        List<Long> playerIds = new ArrayList<>();
        for (HandicapRoundRepository.SignedRoundAggregate agg : aggs) {
            upsertDerived(agg);
            if (!playerIds.contains(agg.getPlayerId())) {
                playerIds.add(agg.getPlayerId());
            }
        }
        return playerIds;
    }

    private HandicapRound upsertDerived(HandicapRoundRepository.SignedRoundAggregate agg) {
        SnapshotHoles snapshot = parseSnapshot(agg.getSnapshotJson(), agg.getScorecardId());

        HandicapRound hr = roundRepository.findByScorecardId(agg.getScorecardId())
                .orElseGet(HandicapRound::new);
        hr.setScorecardId(agg.getScorecardId());
        hr.setTournamentId(agg.getTournamentId());
        hr.setPlayerId(agg.getPlayerId());
        hr.setRoundNo(agg.getRoundNo());
        hr.setPlayDate(agg.getPlayDate());
        hr.setTotalStrokes(agg.getTotalStrokes());
        hr.setTotalPar(snapshot.totalPar());
        hr.setUpdatedAt(LocalDateTime.now());

        CourseRating rating = agg.getCourseRatingId() == null ? null
                : ratingRepository.findById(agg.getCourseRatingId()).orElse(null);
        hr.setCourseRating(rating);
        if (rating == null) {
            // 旧数据没有球场难度参数：明确缺项，差值留空并置缺项标记（页面可据此提示补齐）
            hr.setRatingMissing(true);
            hr.setAdjustedStrokes(agg.getTotalStrokes());
            hr.setHoleCap(null);
            hr.setDiffValue(null);
        } else {
            hr.setRatingMissing(false);
            // 缓存的 diff 按派生时刻生效规则的封顶计算（实时签核口径）；
            // 历史链重放时以「节点当时规则」的封顶重算 diff（effectiveDiff），保证规则边界不被新口径穿越。
            Integer cap = currentCap(agg.getPlayDate());
            hr.setHoleCap(cap);
            int adjusted = capStrokes(snapshot, cap);
            hr.setAdjustedStrokes(adjusted);
            hr.setDiffValue(diff(adjusted, rating));
        }
        return roundRepository.save(hr);
    }

    private Integer currentCap(LocalDate playDate) {
        return ruleRepository.findEffective(playDate).stream().findFirst()
                .map(HandicapRule::getHoleScoreCap).orElse(null);
    }

    // ================================================================
    // 实时签核：在球员链上追加一个节点（不动历史）
    // ================================================================

    /**
     * 记分卡签核成功后调用：先派生本轮，再以本轮完赛日为截止日追加一个 LIVE 节点。
     * 与签核同事务，失败会随签核一起回滚。
     */
    @Transactional
    public void onSignedOff(Scorecard card) {
        rebuildDerivedRound(card.getId());
        LocalDate asOf = roundDate(card);
        appendChainNode(card.getPlayer().getId(), asOf, Dict.TRIGGER_SIGNOFF, null);
        refreshPlayerCache(card.getPlayer().getId());
    }

    // ================================================================
    // 链重放 / 重算：作废 LIVE 链 -> 按每个完赛日重算节点
    // ================================================================

    /**
     * 重建球员整条差点链：现有 LIVE 行整体标 SUPERSEDED（不删，指向批次），
     * 然后从其最早一个已签核轮次起，按完赛日逐个重算节点。
     *
     * @param from 只重建该日期及之后的节点；更早的 LIVE 历史保持不变（分批重算互不覆盖）。null=全量
     * @return 新产生的日志条数
     */
    @Transactional
    public int rebuildChain(Long playerId, LocalDate from, String triggerType, Long batchId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new BizException("球员不存在：" + playerId));

        List<HandicapRound> all = roundRepository.findByPlayerIdOrderByPlayDateDescIdDesc(playerId);
        if (all.isEmpty()) {
            return 0;
        }
        LocalDate earliest = all.get(all.size() - 1).getPlayDate();
        LocalDate cutFrom = from == null || from.isBefore(earliest) ? earliest : from;

        // 作废截止日 >= cutFrom 的 LIVE 节点（更早的历史节点原样保留）
        List<HandicapIndexLog> live = logRepository.findLiveChain(playerId);
        List<Long> toDrop = new ArrayList<>();
        for (HandicapIndexLog node : live) {
            if (!node.getAsOfDate().isBefore(cutFrom)) {
                toDrop.add(node.getId());
            }
        }
        if (!toDrop.isEmpty()) {
            // 逐行更新 supersededBy（JPQL update 不方便带日期条件时用实体更新，节点数 ≤ 轮次数，量级小）
            Long marker = batchId;
            for (Long id : toDrop) {
                logRepository.findById(id).ifPresent(n -> {
                    n.setChain(Dict.CHAIN_SUPERSEDED);
                    n.setSupersededBy(marker);
                });
            }
            indexRoundRepository.deleteByLogIds(toDrop);
        }

        // 每个完赛日一个节点（同日多轮合并为一次计算）
        List<LocalDate> dates = all.stream()
                .map(HandicapRound::getPlayDate)
                .distinct()
                .sorted()
                .filter(d -> !d.isBefore(cutFrom))
                .toList();
        int logs = 0;
        for (LocalDate date : dates) {
            appendChainNode(player.getId(), date, triggerType, batchId);
            logs++;
        }
        refreshPlayerCache(player.getId());
        return logs;
    }

    /**
     * 以 asOfDate 为截止日算一个节点并追加（不取消任何旧节点）。
     * 这是唯一写 handicap_index_log / handicap_index_round 的入口。
     */
    private HandicapIndexLog appendChainNode(Long playerId, LocalDate asOfDate,
                                             String triggerType, Long batchId) {
        List<HandicapRound> windowCandidates = roundRepository.findWindow(playerId, asOfDate);

        // 规则：只取生效日 <= 截止日的最新启用规则；规则没生效前不出指数
        Optional<HandicapRule> ruleOpt = ruleRepository.findEffective(asOfDate).stream().findFirst();

        HandicapIndexLog log = new HandicapIndexLog();
        log.setPlayerId(playerId);
        log.setAsOfDate(asOfDate);
        log.setChain(Dict.CHAIN_LIVE);
        log.setBatchId(batchId);
        log.setTriggerType(triggerType);
        log.setCreatedAt(LocalDateTime.now());
        // 上一节点指数（同日重建时旧节点已被作废，取严格更早日期）
        List<HandicapIndexLog> prev = logRepository.findPreviousLive(playerId, asOfDate);
        if (!prev.isEmpty()) {
            log.setPreviousIndex(prev.get(0).getFinalIndex());
        }

        List<HandicapIndexRound> lines = new ArrayList<>();
        if (ruleOpt.isEmpty()) {
            log.setRule(null);
            log.setStatus(Dict.INDEX_NO_RULE);
            log.setSampleCount(0);
            log.setUsedCount(0);
            log.setCappedNote("截止日 " + asOfDate + " 之前没有已生效的差点计算规则");
            log = logRepository.save(log);
            return log;
        }

        HandicapRule rule = ruleOpt.get();
        log.setRule(rule);

        // 窗口：最近 N 轮（缺参数轮也保留在窗口内，用于页面展示与异常判定占位）
        int window = Math.max(1, rule.getWindowRounds());
        List<HandicapRound> windowRounds = windowCandidates.size() > window
                ? windowCandidates.subList(0, window) : windowCandidates;

        // 历史回放：差值必须按「本节点当时规则」的逐洞封顶重算（派生层缓存的是最近口径）。
        Map<Long, BigDecimal> effDiff = new LinkedHashMap<>();
        for (HandicapRound r : windowRounds) {
            if (r.getRatingMissing() || r.getCourseRating() == null) {
                continue;
            }
            Integer ruleCap = rule.getHoleScoreCap();
            boolean sameCap = java.util.Objects.equals(ruleCap, r.getHoleCap());
            if (sameCap) {
                effDiff.put(r.getId(), r.getDiffValue());
            } else {
                SnapshotHoles sh = parseSnapshot(scorecardSnapshot(r.getScorecardId()), r.getScorecardId());
                int adj = capStrokes(sh, ruleCap);
                effDiff.put(r.getId(), diff(adj, r.getCourseRating()));
            }
        }

        // 有差值的轮次（缺参数轮被排除）
        List<HandicapRound> calculable = windowRounds.stream()
                .filter(r -> !r.getRatingMissing() && effDiff.get(r.getId()) != null)
                .toList();

        // 异常判定：差值高出窗口最佳差值超阈值的轮判异常（只防「异常高」，不防打好）
        BigDecimal anomalyFloor = null;
        if (!calculable.isEmpty()) {
            BigDecimal best = calculable.stream()
                    .map(r -> effDiff.get(r.getId())).min(Comparator.naturalOrder()).orElse(null);
            anomalyFloor = best.add(rule.getMaxDiffBelowIndex());
        }
        List<HandicapRound> valid = new ArrayList<>();
        List<HandicapRound> anomalies = new ArrayList<>();
        for (HandicapRound r : calculable) {
            if (anomalyFloor != null && effDiff.get(r.getId()).compareTo(anomalyFloor) > 0) {
                anomalies.add(r);
            } else {
                valid.add(r);
            }
        }

        int sampleCount = windowRounds.size();
        log.setSampleCount(sampleCount);

        // 先把所有窗口轮按时间正序写留痕（采用与否稍后回填）
        List<HandicapRound> chronological = new ArrayList<>(windowRounds);
        chronological.sort(Comparator.comparing(HandicapRound::getPlayDate)
                .thenComparing(HandicapRound::getId));
        Map<Long, HandicapIndexRound> lineByRound = new LinkedHashMap<>();
        for (HandicapRound r : chronological) {
            HandicapIndexRound line = new HandicapIndexRound();
            line.setHandicapRoundId(r.getId());
            line.setTournamentId(r.getTournamentId());
            line.setRoundNo(r.getRoundNo());
            line.setPlayDate(r.getPlayDate());
            line.setTotalStrokes(r.getTotalStrokes());
            line.setDiffValue(effDiff.get(r.getId()));
            line.setIncluded(false);
            if (r.getRatingMissing()) {
                line.setExclusionReason(Dict.EX_RATING_MISSING);
            } else if (anomalies.contains(r)) {
                line.setExclusionReason(Dict.EX_ANOMALY);
            }
            lines.add(line);
            lineByRound.put(r.getId(), line);
        }

        if (valid.size() < rule.getMinRounds()) {
            log.setStatus(Dict.INDEX_INSUFFICIENT);
            log.setUsedCount(0);
            long missing = windowRounds.stream().filter(HandicapRound::getRatingMissing).count();
            StringBuilder note = new StringBuilder("有效样本 " + valid.size()
                    + " 轮，少于最低 " + rule.getMinRounds() + " 轮，样本不足");
            if (missing > 0) {
                note.append("（其中 ").append(missing).append(" 轮缺球场难度参数）");
            }
            if (!anomalies.isEmpty()) {
                note.append("；").append(anomalies.size()).append(" 轮差值异常已排除");
            }
            log.setCappedNote(note.toString());
            // 样本不足：所有可算轮也标注未采用原因
            for (HandicapRound r : valid) {
                HandicapIndexRound line = lineByRound.get(r.getId());
                if (line != null && line.getExclusionReason() == null) {
                    line.setExclusionReason(Dict.EX_INSUFFICIENT);
                }
            }
            log = logRepository.save(log);
            saveLines(log.getId(), lines);
            return log;
        }

        // 选轮：样本数 -> 采用最低差值个数
        int used = pickUsedCount(rule, valid.size());
        List<HandicapRound> sorted = new ArrayList<>(valid);
        sorted.sort(Comparator.comparing((HandicapRound r) -> effDiff.get(r.getId()))
                .thenComparing(HandicapRound::getPlayDate, Comparator.reverseOrder())
                .thenComparing(HandicapRound::getId));
        List<HandicapRound> chosen = sorted.subList(0, Math.min(used, sorted.size()));

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < chosen.size(); i++) {
            HandicapRound r = chosen.get(i);
            sum = sum.add(effDiff.get(r.getId()));
            HandicapIndexRound line = lineByRound.get(r.getId());
            if (line != null) {
                line.setIncluded(true);
                line.setRankNo(i + 1);
                line.setExclusionReason(null);
            }
        }
        // 未入选的有效轮标注排名原因
        for (HandicapRound r : sorted.subList(chosen.size(), sorted.size())) {
            HandicapIndexRound line = lineByRound.get(r.getId());
            if (line != null && line.getExclusionReason() == null) {
                line.setExclusionReason(Dict.EX_RANK);
            }
        }

        // 平均差值 - 2（WHS 差值调整），一位小数
        BigDecimal raw = sum.divide(new BigDecimal(chosen.size()), 2, RoundingMode.HALF_UP)
                .subtract(DIFF_ADJUSTMENT).setScale(1, RoundingMode.HALF_UP);
        BigDecimal finalIndex = raw;
        List<String> caps = new ArrayList<>();

        // 单次下调幅度限制（只限制下调，上调放行）
        if (log.getPreviousIndex() != null
                && finalIndex.compareTo(log.getPreviousIndex().subtract(rule.getMaxDecreasePerRound())) < 0) {
            BigDecimal limited = log.getPreviousIndex().subtract(rule.getMaxDecreasePerRound())
                    .setScale(1, RoundingMode.HALF_UP);
            caps.add("单次下调超 " + rule.getMaxDecreasePerRound().toPlainString()
                    + "，已限制为 " + limited.toPlainString());
            finalIndex = limited;
        }
        // 区间钳制
        if (finalIndex.compareTo(rule.getIndexMin()) < 0) {
            finalIndex = rule.getIndexMin();
        }
        if (finalIndex.compareTo(rule.getIndexMax()) > 0) {
            finalIndex = rule.getIndexMax();
        }

        log.setStatus(Dict.INDEX_CALCULATED);
        log.setUsedCount(chosen.size());
        log.setRawIndex(raw);
        log.setFinalIndex(finalIndex);
        log.setCappedNote(caps.isEmpty() ? null : String.join("；", caps));
        log = logRepository.save(log);
        saveLines(log.getId(), lines);
        return log;
    }

    private void saveLines(Long logId, List<HandicapIndexRound> lines) {
        for (HandicapIndexRound line : lines) {
            line.setLogId(logId);
            indexRoundRepository.save(line);
        }
    }

    /** WHS 选轮表：样本数 → 采用最低差值个数；表中缺样本数档位时退化为「全部采用」。 */
    private int pickUsedCount(HandicapRule rule, int sample) {
        try {
            JsonNode table = objectMapper.readTree(rule.getCountTableJson());
            JsonNode node = table.get(String.valueOf(sample));
            if (node != null && node.canConvertToInt()) {
                int v = node.asInt();
                return Math.max(1, Math.min(v, sample));
            }
        } catch (JsonProcessingException e) {
            throw new BizException("规则「" + rule.getName() + "」的选轮表 JSON 无法解析：" + e.getOriginalMessage());
        }
        return sample;
    }

    /** 刷新球员表的当前指数缓存（只允许本服务写）。 */
    private void refreshPlayerCache(Long playerId) {
        List<HandicapIndexLog> live = logRepository.findLiveChain(playerId);
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null || live.isEmpty()) {
            return;
        }
        HandicapIndexLog latest = live.get(live.size() - 1);
        player.setHandicapIndex(latest.getFinalIndex());
        player.setIndexStatus(latest.getStatus());
        player.setIndexAsOf(latest.getAsOfDate());
        player.setIndexUpdatedAt(LocalDateTime.now());
        playerRepository.save(player);
    }

    // ================================================================
    // 差值 / 封顶 / 快照
    // ================================================================

    /** 差点差值：113 / slope ×（adjustedStrokes - courseRating），一位小数。 */
    private BigDecimal diff(int adjustedStrokes, CourseRating rating) {
        BigDecimal factor = SLOPE_STANDARD
                .divide(new BigDecimal(rating.getSlopeRating()), 6, RoundingMode.HALF_UP);
        return factor.multiply(new BigDecimal(adjustedStrokes).subtract(rating.getCourseRatingValue()))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 逐洞封顶（Net Double Bogey 简化口径：par + capOffset，v2 配置为 par+2 与封顶值取小由配置决定，
     * 此处 cap 为绝对杆数上限）。cap 为 null 时不封顶。
     */
    private int capStrokes(SnapshotHoles snapshot, Integer cap) {
        if (cap == null) {
            return snapshot.strokes().stream().mapToInt(Integer::intValue).sum();
        }
        return snapshot.strokes().stream().mapToInt(s -> Math.min(s, cap)).sum();
    }

    private String scorecardSnapshot(Long scorecardId) {
        String json = scorecardRepository.findSnapshotJsonById(scorecardId);
        if (json == null || json.isBlank()) {
            throw new BizException("记分卡 " + scorecardId + " 缺少固化快照，无法按当时规则重算差值");
        }
        return json;
    }

    private SnapshotHoles parseSnapshot(String json, Long scorecardId) {        if (json == null || json.isBlank()) {
            throw new BizException("记分卡 " + scorecardId + " 缺少固化快照，无法派生差点轮次");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode holes = root.path("holes");
            if (!holes.isArray() || holes.isEmpty()) {
                throw new BizException("记分卡 " + scorecardId + " 快照没有逐洞数据");
            }
            List<Integer> strokes = new ArrayList<>();
            int totalPar = 0;
            for (JsonNode h : holes) {
                strokes.add(h.path("strokes").asInt());
                totalPar += h.path("par").asInt();
            }
            return new SnapshotHoles(strokes, totalPar);
        } catch (JsonProcessingException e) {
            throw new BizException("记分卡 " + scorecardId + " 快照无法解析：" + e.getOriginalMessage());
        }
    }

    private record SnapshotHoles(List<Integer> strokes, int totalPar) {
    }

    private LocalDate roundDate(Scorecard card) {
        if (card.getPlayDate() != null) {
            return card.getPlayDate();
        }
        if (card.getTournament() != null && card.getTournament().getStartDate() != null) {
            return card.getTournament().getStartDate();
        }
        return LocalDate.now();
    }

    // ================================================================
    // 批次入口（每个球员/梯台独立事务，单个失败不回滚整批）
    // ================================================================

    @Transactional
    public void rebuildDerivedRoundsInNewTx(Long playerId, LocalDate from, LocalDate to) {
        rebuildDerivedRounds(playerId, from, to);
    }

    /** 梯台参数补齐/修正后，重建所有绑定该梯台的已签核卡派生行。 */
    @Transactional
    public void rebuildDerivedRoundsForRatingInNewTx(Long ratingId) {
        if (ratingId == null) {
            throw new BizException("必须指定梯台");
        }
        ratingRepository.findById(ratingId)
                .orElseThrow(() -> new BizException("梯台不存在：" + ratingId));
        for (HandicapRoundRepository.SignedRoundAggregate agg
                : roundRepository.aggregateSignedCardsByRating(ratingId)) {
            upsertDerived(agg);
        }
    }

    @Transactional
    public List<Long> playerIdsByRating(Long ratingId) {
        return roundRepository.findPlayerIdsByRatingId(ratingId);
    }

    @Transactional
    public int rebuildChainInNewTx(Long playerId, LocalDate from, String triggerType, Long batchId) {
        return rebuildChain(playerId, from, triggerType, batchId);
    }

    /** 启动回填：若系统里一张差点日志都没有（首次升级），为全部有已签核卡的球员建初始链。 */
    @Transactional
    public int bootstrapIfEmpty() {
        if (logRepository.count() > 0) {
            return 0;
        }
        rebuildDerivedRounds(null, null, null);
        List<Long> playerIds = roundRepository.findDistinctPlayerIds();
        int logs = 0;
        for (Long pid : playerIds) {
            logs += rebuildChain(pid, null, Dict.TRIGGER_BOOTSTRAP, null);
        }
        return logs;
    }

    // ================================================================
    // 查询：球员画像（当前指数 / 最近变化 / 采用与排除轮次 / 规则）
    // ================================================================

    @Transactional(readOnly = true)
    public HandicapProfile profile(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new BizException("球员不存在：" + playerId));
        List<HandicapIndexLog> live = logRepository.findLiveChain(playerId);
        List<HandicapRound> missing =
                roundRepository.findByPlayerIdAndRatingMissingTrueOrderByPlayDateDescIdDesc(playerId);

        HandicapIndexLog latest = live.isEmpty() ? null : live.get(live.size() - 1);
        HandicapIndexLog previous = live.size() < 2 ? null : live.get(live.size() - 2);
        BigDecimal change = null;
        if (latest != null && previous != null
                && latest.getFinalIndex() != null && previous.getFinalIndex() != null) {
            change = latest.getFinalIndex().subtract(previous.getFinalIndex());
        }

        List<HandicapProfile.HistoryNode> history = live.stream()
                .map(l -> toHistoryNode(l, false))
                .toList();

        List<HandicapProfile.RoundLine> currentRounds = latest == null ? List.of()
                : toRoundLines(latest.getId());
        List<HandicapProfile.MissingRound> missingRounds = toMissingRounds(missing);

        return new HandicapProfile(
                player.getId(), player.getName(),
                player.getHandicapIndex(), player.getIndexStatus(),
                player.getIndexAsOf(),
                latest == null ? null : latest.getPreviousIndex(),
                change,
                latest != null && latest.getRule() != null ? latest.getRule().getId() : null,
                latest != null && latest.getRule() != null ? latest.getRule().getName() : null,
                latest != null && latest.getRule() != null ? latest.getRule().getEffectiveDate() : null,
                latest == null ? null : latest.getCappedNote(),
                history, currentRounds, missingRounds);
    }

    /** 含已作废旧链的全量追溯（页面「历史口径」折叠区）。 */
    @Transactional(readOnly = true)
    public List<HandicapProfile.HistoryNode> fullHistory(Long playerId) {
        playerRepository.findById(playerId).orElseThrow(() -> new BizException("球员不存在：" + playerId));
        return logRepository.findAllForPlayer(playerId).stream()
                .map(l -> toHistoryNode(l, true))
                .toList();
    }

    private HandicapProfile.HistoryNode toHistoryNode(HandicapIndexLog l, boolean withRounds) {
        return new HandicapProfile.HistoryNode(
                l.getId(), l.getAsOfDate(), l.getStatus(), l.getRawIndex(), l.getFinalIndex(),
                l.getPreviousIndex(), l.getTriggerType(),
                l.getRule() == null ? null : l.getRule().getId(),
                l.getRule() == null ? null : l.getRule().getName(),
                l.getRule() == null ? null : l.getRule().getEffectiveDate(),
                l.getChain(), l.getCappedNote(),
                withRounds ? toRoundLines(l.getId()) : List.of());
    }

    private List<HandicapProfile.RoundLine> toRoundLines(Long logId) {
        List<HandicapIndexRound> lines =
                indexRoundRepository.findByLogIdOrderByIncludedDescPlayDateAscIdAsc(logId);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Long> tournamentIds = lines.stream().map(HandicapIndexRound::getTournamentId).distinct().toList();
        Map<Long, Tournament> tournaments = tournamentRepository.findAllById(tournamentIds).stream()
                .collect(java.util.stream.Collectors.toMap(Tournament::getId, t -> t, (a, b) -> a));
        List<Long> roundIds = lines.stream().map(HandicapIndexRound::getHandicapRoundId).distinct().toList();
        Map<Long, HandicapRound> rounds = roundRepository.findAllById(roundIds).stream()
                .collect(java.util.stream.Collectors.toMap(HandicapRound::getId, r -> r, (a, b) -> a));
        return lines.stream().map(line -> {
            Tournament t = tournaments.get(line.getTournamentId());
            HandicapRound hr = rounds.get(line.getHandicapRoundId());
            String tee = hr != null && hr.getCourseRating() != null
                    ? hr.getCourseRating().getDisplayName() : null;
            return new HandicapProfile.RoundLine(
                    line.getHandicapRoundId(), line.getTournamentId(),
                    t == null ? null : t.getName(), line.getRoundNo(), line.getPlayDate(),
                    line.getTotalStrokes(), hr == null ? null : hr.getTotalPar(),
                    tee, line.getDiffValue(),
                    Boolean.TRUE.equals(line.getIncluded()), line.getRankNo(),
                    line.getExclusionReason());
        }).toList();
    }

    private List<HandicapProfile.MissingRound> toMissingRounds(List<HandicapRound> rounds) {
        if (rounds.isEmpty()) {
            return List.of();
        }
        Map<Long, Tournament> tournaments = tournamentRepository
                .findAllById(rounds.stream().map(HandicapRound::getTournamentId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Tournament::getId, t -> t, (a, b) -> a));
        return rounds.stream().map(r -> new HandicapProfile.MissingRound(
                r.getScorecardId(), r.getTournamentId(),
                tournaments.get(r.getTournamentId()) == null ? null
                        : tournaments.get(r.getTournamentId()).getName(),
                r.getRoundNo(), r.getPlayDate(), r.getTotalStrokes())).toList();
    }
}
