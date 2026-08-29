package oj.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssignmentAnalyticsRepository extends JpaRepository<AssignmentAnalytics, AssignmentAnalytics.Pk> {

    List<AssignmentAnalytics> findByAssignmentTargetId(Long assignmentTargetId);
}
