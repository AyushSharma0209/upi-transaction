package com.upi.transaction.repository;

import com.upi.transaction.entity.PendingCategorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PendingCategorizationRepository extends JpaRepository<PendingCategorization, Long> {

    // For Day 4 timeout — auto-fail stale rows
    List<PendingCategorization> findAllByCreatedAtBefore(Instant cutoff);
}