package org.example.transactiontest.repository

import org.example.transactiontest.entity.Logs
import org.springframework.data.jpa.repository.JpaRepository

interface LogsRepository : JpaRepository<Logs, Long> {

    fun countByMessage(message: String): Long

    fun existsByMessage(message: String): Boolean

    fun findAllByMessage(message: String): List<Logs>
}
