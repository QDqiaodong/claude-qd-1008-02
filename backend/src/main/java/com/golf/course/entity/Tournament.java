package com.golf.course.entity;

import java.time.LocalDate;
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
 * 赛事：球场组织的比赛，含报名、进行、结束三个阶段。
 *
 * <p>tournamentNo 唯一；状态只能逐级推进：报名中 → 进行中 → 已结束。</p>
 */
@Entity
@Table(name = "tournament")
@Getter
@Setter
public class Tournament {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 赛事编号，唯一 */
    @Column(name = "tournament_no", nullable = false, unique = true, length = 24)
    private String tournamentNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** 报名中 / 进行中 / 已结束 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "location", length = 48)
    private String location;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
