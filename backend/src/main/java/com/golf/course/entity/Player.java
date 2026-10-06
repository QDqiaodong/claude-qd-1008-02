package com.golf.course.entity;

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
 * 球员：下场打球与参加赛事的人员。
 *
 * <p>playerNo 唯一；handicap 差点取值范围 0 ~ 54。</p>
 */
@Entity
@Table(name = "player")
@Getter
@Setter
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 球员编号，唯一 */
    @Column(name = "player_no", nullable = false, unique = true, length = 16)
    private String playerNo;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    /** 差点：0 ~ 54 */
    @Column(name = "handicap", nullable = false)
    private Integer handicap;

    @Column(name = "phone", length = 24)
    private String phone;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
