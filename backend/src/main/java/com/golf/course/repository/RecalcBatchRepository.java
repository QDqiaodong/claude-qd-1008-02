package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.RecalcBatch;

public interface RecalcBatchRepository extends JpaRepository<RecalcBatch, Long> {

    List<RecalcBatch> findAllByOrderByIdDesc();
}
