package com.golf.course.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.HandicapIndexLog;

public interface HandicapIndexLogRepository extends JpaRepository<HandicapIndexLog, Long> {

    /** 球员当前链（LIVE）按截止日顺序回放。 */
    @Query("""
            select l from HandicapIndexLog l
            where l.playerId = :playerId and l.chain = 'LIVE'
            order by l.asOfDate asc, l.id asc
            """)
    List<HandicapIndexLog> findLiveChain(@Param("playerId") Long playerId);

    /** 含已作废旧链的全量历史（规则追溯页用）。 */
    @Query("""
            select l from HandicapIndexLog l
            where l.playerId = :playerId
            order by l.asOfDate asc, l.id asc
            """)
    List<HandicapIndexLog> findAllForPlayer(@Param("playerId") Long playerId);

    @Query("""
            select l from HandicapIndexLog l
            where l.playerId = :playerId and l.chain = 'LIVE'
              and l.asOfDate < :asOfDate
            order by l.asOfDate desc, l.id desc
            """)
    List<HandicapIndexLog> findPreviousLive(@Param("playerId") Long playerId,
                                            @Param("asOfDate") LocalDate asOfDate);
}
