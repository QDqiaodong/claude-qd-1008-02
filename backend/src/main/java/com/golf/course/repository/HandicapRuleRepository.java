package com.golf.course.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.HandicapRule;

public interface HandicapRuleRepository extends JpaRepository<HandicapRule, Long> {

    List<HandicapRule> findAllByOrderByEffectiveDateDescIdDesc();

    Optional<HandicapRule> findByCode(String code);

    Optional<HandicapRule> findByEffectiveDate(LocalDate effectiveDate);

    /**
     * 计算 {@code asOfDate} 时生效的规则：enabled=1 且生效日 ≤ 截止日的最新一条。
     * 「新规则只影响生效日之后的结果」——早于规则生效日的历史计算永远选不到新规则。
     */
    @Query("""
            select r from HandicapRule r
            where r.enabled = true and r.effectiveDate <= :asOfDate
            order by r.effectiveDate desc, r.id desc
            """)
    List<HandicapRule> findEffective(@Param("asOfDate") LocalDate asOfDate);
}
