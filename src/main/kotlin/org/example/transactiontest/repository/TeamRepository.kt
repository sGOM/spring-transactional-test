package org.example.transactiontest.repository

import org.example.transactiontest.entity.Team
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TeamRepository : JpaRepository<Team, Long> {

    /** 컬렉션까지 한 번에 초기화해서 가져온다. 순환 참조 재현에 쓴다. */
    @Query("select t from Team t join fetch t.members where t.id = :id")
    fun findWithMembers(id: Long): Team?
}
