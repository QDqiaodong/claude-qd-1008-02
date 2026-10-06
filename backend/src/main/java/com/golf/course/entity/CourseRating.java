package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 球场难度参数（场地 × 梯台）。
 *
 * <p>差点差值公式：{@code diff = 113 / slopeRating ×（总杆 - courseRating）}。
 * 同一 {@code courseName + teeName} 唯一。旧记分卡可能没绑梯台，
 * 差点计算会把该轮标为「难度参数缺失」，参数补齐后可定向重算。</p>
 */
@Entity
@Table(name = "course_rating")
@Getter
@Setter
public class CourseRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 场地名称（如 A场主球场） */
    @Column(name = "course_name", nullable = false, length = 48)
    private String courseName;

    /** 梯台名称（如 白梯） */
    @Column(name = "tee_name", nullable = false, length = 24)
    private String teeName;

    /** 球场难度值 */
    @Column(name = "course_rating", nullable = false, precision = 4, scale = 1)
    private BigDecimal courseRatingValue;

    /** 坡度难度值，通常 55 ~ 155 */
    @Column(name = "slope_rating", nullable = false)
    private Integer slopeRating;

    /** 该梯台标准杆合计（可空，仅展示用） */
    @Column(name = "par_total")
    private Integer parTotal;

    @Column(name = "note", length = 128)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getDisplayName() {
        return courseName + " · " + teeName;
    }
}
