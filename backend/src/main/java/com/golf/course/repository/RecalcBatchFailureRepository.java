package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.RecalcBatchFailure;

public interface RecalcBatchFailureRepository extends JpaRepository<RecalcBatchFailure, Long> {

    List<RecalcBatchFailure> findByBatchIdOrderByIdAsc(Long batchId);
}
