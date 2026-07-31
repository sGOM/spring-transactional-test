package org.example.transactiontest.repository

import org.example.transactiontest.entity.Counter
import org.springframework.data.jpa.repository.JpaRepository

interface CounterRepository : JpaRepository<Counter, Long> {

    fun findByName(name: String): Counter?
}
