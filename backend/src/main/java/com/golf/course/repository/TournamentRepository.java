package com.golf.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Tournament;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByTournamentNo(String tournamentNo);
}
