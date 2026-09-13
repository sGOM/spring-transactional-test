package org.example.transactiontest.repository

import org.example.transactiontest.entity.Member
import org.springframework.data.jpa.repository.JpaRepository

interface MemberRepository : JpaRepository<Member, Long>
