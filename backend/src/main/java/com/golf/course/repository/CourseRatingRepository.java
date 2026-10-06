package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.CourseRating;

public interface CourseRatingRepository extends JpaRepository<CourseRating, Long> {

    Optional<CourseRating> findByCourseNameAndTeeName(String courseName, String teeName);

    List<CourseRating> findAllByOrderByCourseNameAscTeeNameAsc();
}
