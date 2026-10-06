package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.HandicapIndexRound;

public interface HandicapIndexRoundRepository extends JpaRepository<HandicapIndexRound, Long> {

    List<HandicapIndexRound> findByLogIdOrderByIncludedDescPlayDateAscIdAsc(Long logId);

    @Modifying(flushAutomatically = true)
    @Query("delete from HandicapIndexRound r where r.logId in :logIds")
    void deleteByLogIds(@Param("logIds") List<Long> logIds);
}
