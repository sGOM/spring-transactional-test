package org.example.transactiontest.service

import org.example.transactiontest.entity.Member
import org.example.transactiontest.entity.Team
import org.example.transactiontest.repository.MemberRepository
import org.example.transactiontest.repository.TeamRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 컨트롤러가 서비스에서 받은 것을 그대로 응답에 싣는 상황을 재현한다.
 *
 * 이 프로젝트는 `open-in-view: false` 라서 트랜잭션 경계가 곧 영속성 컨텍스트 경계다.
 * 즉 아래 메서드들이 **반환하는 순간 영속성 컨텍스트는 이미 닫혀 있다.**
 */
@Service
class BidirectionalEntityService(
    private val teamRepository: TeamRepository,
    private val memberRepository: MemberRepository,
) {
    /** 컬렉션까지 초기화된 엔티티를 반환한다. 순환 참조는 그대로 남는다. */
    @Transactional(readOnly = true)
    fun findTeamWithMembers(id: Long): Team = teamRepository.findWithMembers(id)!!

    /** [Member.team] 을 건드리지 않고 반환한다. team 자리에는 초기화되지 않은 프록시가 남는다. */
    @Transactional(readOnly = true)
    fun findMember(id: Long): Member = memberRepository.findById(id).orElseThrow()

    /** 트랜잭션 안에서 필요한 값만 뽑아 DTO 로 옮긴다. */
    @Transactional(readOnly = true)
    fun findMemberAsDto(id: Long): MemberDto =
        memberRepository.findById(id).orElseThrow().let { MemberDto(it.id!!, it.name, it.team!!.name) }
}

data class MemberDto(val id: Long, val name: String, val teamName: String)
