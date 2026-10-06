package com.golf.course.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.CourseRating;
import com.golf.course.entity.HandicapRule;
import com.golf.course.repository.CourseRatingRepository;
import com.golf.course.repository.HandicapRuleRepository;

/**
 * 管理员维护：球场难度参数（梯台）与版本化差点规则。
 *
 * <p>规则红线：
 * <ul>
 *   <li>生效日唯一，同一天不允许两条规则；</li>
 *   <li>已生效（生效日 ≤ 今天）的规则不可改不可删——它可能已被历史差点日志引用，
 *       要调整只能新建一条更晚生效的规则，新规则只影响生效日之后的结果；</li>
 *   <li>停用只影响后续计算选规则，历史日志仍指向旧规则，可追溯。</li>
 * </ul>
 */
@Service
public class HandicapAdminService {

    private final HandicapRuleRepository ruleRepository;
    private final CourseRatingRepository ratingRepository;
    private final ObjectMapper objectMapper;

    public HandicapAdminService(HandicapRuleRepository ruleRepository,
                                CourseRatingRepository ratingRepository,
                                ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.ratingRepository = ratingRepository;
        this.objectMapper = objectMapper;
    }

    // ---------------- 梯台 ----------------

    public List<CourseRating> listRatings() {
        return ratingRepository.findAllByOrderByCourseNameAscTeeNameAsc();
    }

    public CourseRating getRating(Long id) {
        return ratingRepository.findById(id)
                .orElseThrow(() -> new BizException("梯台难度参数不存在：" + id));
    }

    @Transactional
    public CourseRating createRating(CourseRating input) {
        validateRating(input);
        ratingRepository.findByCourseNameAndTeeName(input.getCourseName().trim(), input.getTeeName().trim())
                .ifPresent(r -> {
                    throw new BizException("场地「" + input.getCourseName() + "」已存在梯台「"
                            + input.getTeeName() + "」");
                });
        CourseRating r = new CourseRating();
        copyRating(input, r);
        r.setCreatedAt(LocalDateTime.now());
        return ratingRepository.save(r);
    }

    @Transactional
    public CourseRating updateRating(Long id, CourseRating input) {
        CourseRating r = getRating(id);
        validateRating(input);
        if (!r.getCourseName().equals(input.getCourseName().trim())
                || !r.getTeeName().equals(input.getTeeName().trim())) {
            ratingRepository.findByCourseNameAndTeeName(input.getCourseName().trim(), input.getTeeName().trim())
                    .ifPresent(x -> {
                        throw new BizException("场地+梯台名称组合已存在");
                    });
        }
        copyRating(input, r);
        return ratingRepository.save(r);
    }

    private void copyRating(CourseRating input, CourseRating r) {
        r.setCourseName(input.getCourseName().trim());
        r.setTeeName(input.getTeeName().trim());
        r.setCourseRatingValue(input.getCourseRatingValue());
        r.setSlopeRating(input.getSlopeRating());
        r.setParTotal(input.getParTotal());
        r.setNote(input.getNote());
    }

    private void validateRating(CourseRating r) {
        if (r.getCourseName() == null || r.getCourseName().isBlank()) {
            throw new BizException("场地名称不能为空");
        }
        if (r.getTeeName() == null || r.getTeeName().isBlank()) {
            throw new BizException("梯台名称不能为空");
        }
        if (r.getCourseRatingValue() == null
                || r.getCourseRatingValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("球场难度值必须大于 0");
        }
        if (r.getSlopeRating() == null
                || r.getSlopeRating() < Dict.SLOPE_MIN || r.getSlopeRating() > Dict.SLOPE_MAX) {
            throw new BizException("坡度难度值必须在 " + Dict.SLOPE_MIN + " ~ " + Dict.SLOPE_MAX + " 之间");
        }
    }

    // ---------------- 规则 ----------------

    public List<HandicapRule> listRules() {
        return ruleRepository.findAllByOrderByEffectiveDateDescIdDesc();
    }

    public HandicapRule getRule(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("差点规则不存在：" + id));
    }

    @Transactional
    public HandicapRule createRule(HandicapRule input) {
        validateRule(input);
        if (input.getCode() != null) {
            ruleRepository.findByCode(input.getCode().trim()).ifPresent(r -> {
                throw new BizException("规则编码已存在：" + input.getCode());
            });
        }
        ruleRepository.findByEffectiveDate(input.getEffectiveDate()).ifPresent(r -> {
            throw new BizException("生效日 " + input.getEffectiveDate() + " 已存在规则「"
                    + r.getName() + "」，同一天只能有一条规则生效");
        });
        HandicapRule r = new HandicapRule();
        copyRule(input, r);
        r.setEnabled(true);
        r.setCreatedAt(LocalDateTime.now());
        return ruleRepository.save(r);
    }

    /**
     * 只允许编辑「未生效」规则（生效日在未来）。已生效规则锁定——
     * 历史差点按它算过，改动会让历史口径被悄悄改写；要改请新增未来规则。
     */
    @Transactional
    public HandicapRule updateRule(Long id, HandicapRule input) {
        HandicapRule r = getRule(id);
        if (!r.getEffectiveDate().isAfter(LocalDate.now())) {
            throw new BizException("规则「" + r.getName() + "」已于 " + r.getEffectiveDate()
                    + " 生效，历史差点按其计算且已固化，不能再编辑；如需调整请新建一条更晚生效的规则");
        }
        validateRule(input);
        if (input.getEffectiveDate() != null && !input.getEffectiveDate().equals(r.getEffectiveDate())) {
            ruleRepository.findByEffectiveDate(input.getEffectiveDate()).ifPresent(x -> {
                throw new BizException("生效日 " + input.getEffectiveDate() + " 已存在其他规则");
            });
        }
        copyRule(input, r);
        return ruleRepository.save(r);
    }

    /** 停用（不物理删除）：停用后不再被新计算选中，历史日志引用保留。 */
    @Transactional
    public HandicapRule toggleRule(Long id, boolean enabled) {
        HandicapRule r = getRule(id);
        r.setEnabled(enabled);
        return ruleRepository.save(r);
    }

    private void copyRule(HandicapRule input, HandicapRule r) {
        r.setCode(input.getCode().trim());
        r.setName(input.getName().trim());
        r.setEffectiveDate(input.getEffectiveDate());
        r.setWindowRounds(input.getWindowRounds());
        r.setMinRounds(input.getMinRounds());
        r.setCountTableJson(input.getCountTableJson());
        r.setMaxDiffBelowIndex(input.getMaxDiffBelowIndex());
        r.setMaxDecreasePerRound(input.getMaxDecreasePerRound());
        r.setHoleScoreCap(input.getHoleScoreCap());
        r.setIndexMin(input.getIndexMin());
        r.setIndexMax(input.getIndexMax());
        r.setNote(input.getNote());
    }

    private void validateRule(HandicapRule r) {
        if (r.getCode() == null || r.getCode().isBlank()) {
            throw new BizException("规则编码不能为空");
        }
        if (r.getName() == null || r.getName().isBlank()) {
            throw new BizException("规则名称不能为空");
        }
        if (r.getEffectiveDate() == null) {
            throw new BizException("生效日期不能为空");
        }
        if (r.getWindowRounds() == null || r.getWindowRounds() < 1) {
            throw new BizException("采样窗口轮次必须 ≥ 1");
        }
        if (r.getMinRounds() == null || r.getMinRounds() < 1) {
            throw new BizException("最低样本轮次必须 ≥ 1");
        }
        if (r.getMaxDiffBelowIndex() == null || r.getMaxDiffBelowIndex().signum() <= 0) {
            throw new BizException("异常差值阈值必须大于 0");
        }
        if (r.getMaxDecreasePerRound() == null || r.getMaxDecreasePerRound().signum() <= 0) {
            throw new BizException("单次下调上限必须大于 0");
        }
        if (r.getHoleScoreCap() != null && r.getHoleScoreCap() < Dict.MIN_STROKES) {
            throw new BizException("逐洞封顶杆数非法");
        }
        if (r.getIndexMin() == null || r.getIndexMax() == null
                || r.getIndexMin().compareTo(r.getIndexMax()) >= 0) {
            throw new BizException("指数区间非法：下限必须小于上限");
        }
        if (r.getCountTableJson() == null || r.getCountTableJson().isBlank()) {
            throw new BizException("选轮表不能为空");
        }
        try {
            JsonNode table = objectMapper.readTree(r.getCountTableJson());
            if (!table.isObject() || table.isEmpty()) {
                throw new BizException("选轮表必须是「样本数 -> 采用个数」的非空 JSON 对象");
            }
            table.fields().forEachRemaining(e -> {
                int sample;
                int count;
                try {
                    sample = Integer.parseInt(e.getKey());
                    count = e.getValue().asInt();
                } catch (NumberFormatException ex) {
                    throw new BizException("选轮表的键必须是整数样本数：" + e.getKey());
                }
                if (sample < 1 || count < 1 || count > sample) {
                    throw new BizException("选轮表档位非法（样本 " + sample + " 取 " + count
                            + "）：采用个数必须在 1 ~ 样本数之间");
                }
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("选轮表 JSON 无法解析：" + e.getOriginalMessage());
        }
    }
}
