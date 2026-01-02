package org.example.transactiontest.entity

import jakarta.persistence.*

@Entity
@Table(name = "logs")
class Logs(
    @Column(nullable = false)
    var message: String
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
