package com.upi.transaction.repository;

import com.upi.transaction.entity.CounterpartyMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterpartyMappingRepository extends JpaRepository<CounterpartyMapping, String> {


}