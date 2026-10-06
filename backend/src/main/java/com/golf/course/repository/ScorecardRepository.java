package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Scorecard;

public interface ScorecardRepository extends JpaRepository<Scorecard, Long> {

    List<Scorecard> findByTournamentIdOrderByRoundNoAscIdAsc(Long tournamentId);

    List<Scorecard> findByPlayerIdOrderByUpdatedAtDesc(Long playerId);

    Optional<Scorecard> findByTournamentIdAndPlayerIdAndRoundNo(Long tournamentId, Long playerId, Integer roundNo);

    List<Scorecard> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    long countByTournamentIdAndStatus(Long tournamentId, String status);
}
