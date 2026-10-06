package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Scorecard;

public interface ScorecardRepository extends JpaRepository<Scorecard, Long> {

    List<Scorecard> findByTournamentIdOrderByRoundNoAscIdAsc(Long tournamentId);

    List<Scorecard> findByPlayerIdOrderByUpdatedAtDesc(Long playerId);

    /** 历史链重放时按需解析固化快照（只在封顶口径不同时调用）。 */
    @org.springframework.data.jpa.repository.Query("select c.snapshotJson from Scorecard c where c.id = :id")
    String findSnapshotJsonById(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Scorecard> findByTournamentIdAndPlayerIdAndRoundNo(Long tournamentId, Long playerId, Integer roundNo);

    List<Scorecard> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    long countByTournamentIdAndStatus(Long tournamentId, String status);
}
