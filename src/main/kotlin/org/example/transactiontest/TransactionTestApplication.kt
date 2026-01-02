package org.example.transactiontest

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TransactionTestApplication

fun main(args: Array<String>) {
    runApplication<TransactionTestApplication>(*args)
}
