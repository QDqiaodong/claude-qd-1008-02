package com.golf.course.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.HandicapRound;

public interface HandicapRoundRepository extends JpaRepository<HandicapRound, Long> {

    Optional<HandicapRound> findByScorecardId(Long scorecardId);

    /** 球员最近窗口轮次：完赛日升序（同完赛日按卡 id 排序，保证回放顺序稳定）。 */
    @Query("""
            select hr from HandicapRound hr
            where hr.playerId = :playerId and hr.playDate <= :asOfDate
            order by hr.playDate desc, hr.id desc
            """)
    List<HandicapRound> findWindow(@Param("playerId") Long playerId,
                                   @Param("asOfDate") LocalDate asOfDate);

    /** 日期分批用：区间内涉及的球员。 */
    @Query("""
            select distinct hr.playerId from HandicapRound hr
            where hr.playDate >= :from and hr.playDate <= :to
            order by hr.playerId
            """)
    List<Long> findPlayerIdsInRange(@Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    List<HandicapRound> findByPlayerIdOrderByPlayDateDescIdDesc(Long playerId);

    /** 某球员缺梯台的已签核轮（球员页缺项提示用）。 */
    List<HandicapRound> findByPlayerIdAndRatingMissingTrueOrderByPlayDateDescIdDesc(Long playerId);

    /** 启动回填用：全部派生轮涉及的球员（distinct，不把全部轮次载入内存）。 */
    @Query("select distinct hr.playerId from HandicapRound hr order by hr.playerId")
    List<Long> findDistinctPlayerIds();

    /** 补齐梯台参数后定向重算：圈定绑定该梯台的已签核卡涉及的球员。 */
    @Query("""
            select distinct hr.playerId from HandicapRound hr
            where hr.courseRating.id = :ratingId
            order by hr.playerId
            """)
    List<Long> findPlayerIdsByRatingId(@Param("ratingId") Long ratingId);

    /**
     * 按梯台重建派生层（梯台参数刚补齐/修正时使用）：只取绑定该梯台的已签核卡，
     * 同样是一条聚合 SQL，不触碰其他球员的成绩。
     */
    @Query(value = """
            SELECT c.id              AS scorecardId,
                   c.tournament_id   AS tournamentId,
                   c.player_id       AS playerId,
                   c.round_no        AS roundNo,
                   COALESCE(c.play_date, t.start_date) AS playDate,
                   c.total_strokes   AS totalStrokes,
                   c.snapshot_json   AS snapshotJson,
                   c.course_rating_id AS courseRatingId
            FROM scorecard c
            JOIN tournament t ON t.id = c.tournament_id
            WHERE c.status = '已签核'
              AND c.total_strokes IS NOT NULL
              AND c.course_rating_id = :ratingId
            """, nativeQuery = true)
    List<SignedRoundAggregate> aggregateSignedCardsByRating(@Param("ratingId") Long ratingId);

    /**
     * 从已签核记分卡批量重建派生层（一条 SQL 取数，逐洞在服务层解析固化快照，不逐球员扫描）：
     * 总杆 / 标准杆 / 逐洞杆数以签核 {@code snapshot_json} 为唯一正式口径，
     * 因此这里只取主单字段与快照；封顶后的 adjustedStrokes 在服务层按规则计算。
     */
    @Query(value = """
            SELECT c.id              AS scorecardId,
                   c.tournament_id   AS tournamentId,
                   c.player_id       AS playerId,
                   c.round_no        AS roundNo,
                   COALESCE(c.play_date, t.start_date) AS playDate,
                   c.total_strokes   AS totalStrokes,
                   c.snapshot_json   AS snapshotJson,
                   c.course_rating_id AS courseRatingId
            FROM scorecard c
            JOIN tournament t ON t.id = c.tournament_id
            WHERE c.status = '已签核'
              AND c.total_strokes IS NOT NULL
              AND (:playerId IS NULL OR c.player_id = :playerId)
              AND (:scorecardId IS NULL OR c.id = :scorecardId)
              AND (:from IS NULL OR COALESCE(c.play_date, t.start_date) >= :from)
              AND (:to   IS NULL OR COALESCE(c.play_date, t.start_date) <= :to)
            """, nativeQuery = true)
    List<SignedRoundAggregate> aggregateSignedCards(@Param("playerId") Long playerId,
                                                    @Param("scorecardId") Long scorecardId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    /** 已签核卡聚合投影。 */
    interface SignedRoundAggregate {
        Long getScorecardId();
        Long getTournamentId();
        Long getPlayerId();
        Integer getRoundNo();
        java.time.LocalDate getPlayDate();
        Integer getTotalStrokes();
        String getSnapshotJson();
        Long getCourseRatingId();
    }
}
