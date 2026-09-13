package org.example.transactiontest.serialization

import com.fasterxml.jackson.annotation.JsonIgnore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.persistence.EntityManagerFactory
import org.example.transactiontest.entity.Member
import org.example.transactiontest.entity.Team
import org.example.transactiontest.repository.TeamRepository
import org.example.transactiontest.service.BidirectionalEntityService
import org.example.transactiontest.support.DatabaseCleaner
import org.example.transactiontest.support.TxExecutor
import org.hibernate.Hibernate
import org.hibernate.LazyInitializationException
import org.hibernate.SessionFactory
import org.hibernate.proxy.HibernateProxy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.databind.DatabindException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * ## 양방향 매핑 엔티티를 그대로 반환하면 생기는 일
 *
 * `Team` 과 `Member` 는 서로를 참조한다. 이 엔티티를 컨트롤러 응답에 그대로 실으면
 * 세 가지가 순서대로 문제가 된다.
 *
 * 1. **순환 참조** — Jackson 이 Team -> members -> Member -> team -> Team ... 을 무한히 따라간다.
 * 2. **지연 로딩 프록시** — `open-in-view: false` 라 반환 시점에 영속성 컨텍스트는 이미 닫혀 있다.
 * 3. **N+1** — 컬렉션을 건드리면 팀 하나당 SELECT 가 한 번씩 더 나간다.
 *    응답 경로에서는 2번이 먼저 터지므로, 이것만 트랜잭션 안에서 따로 잰다.
 *
 * 환경: Spring Boot 4.0.1 / Hibernate 7 / **Jackson 3.0.3** / H2 인메모리.
 * Jackson 3 은 순환 참조에서 `StackOverflowError` 대신 중첩 깊이 제한에 먼저 걸린다.
 */
@Suppress("UNUSED")
@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class BidirectionalEntityReturnTest(
    @Autowired private val service: BidirectionalEntityService,
    @Autowired private val teamRepository: TeamRepository,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
    @Autowired private val txExecutor: TxExecutor,
    @Autowired private val databaseCleaner: DatabaseCleaner,
) : BehaviorSpec() {

    /** `@JsonIgnore` 를 엔티티에 직접 붙인 것과 같은 효과를 내는 MixIn. 붙이는 위치만 다르다. */
    private abstract class IgnoreMembers {
        @get:JsonIgnore
        abstract val members: MutableList<Member>
    }

    private abstract class IgnoreTeam {
        @get:JsonIgnore
        abstract val team: Team?
    }

    private fun mapperIgnoring(target: Class<*>, mixIn: Class<*>): ObjectMapper =
        (objectMapper as JsonMapper).rebuild().addMixIn(target, mixIn).build()

    private fun seedTeam(name: String, vararg memberNames: String): Long {
        val team = Team(name)
        memberNames.forEach { team.addMember(Member(it)) }
        return teamRepository.save(team).id!!
    }

    private val statistics get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    init {
        beforeTest { databaseCleaner.clean() }

        Given("Team 과 Member 가 서로를 참조하는 양방향 매핑에서") {

            When("컬렉션까지 초기화된 Team 을 그대로 직렬화하면") {
                Then("Jackson 이 순환을 따라가다 중첩 깊이 제한에 걸린다") {
                    val teamId = seedTeam("백엔드", "헌", "진")

                    val exception = shouldThrow<StreamConstraintsException> {
                        objectMapper.writeValueAsString(service.findTeamWithMembers(teamId))
                    }

                    // Jackson 2 였다면 StackOverflowError 를 잡아 감싼 JsonMappingException 이 나온다.
                    // Jackson 3 은 그 전에 StreamWriteConstraints 의 기본 상한(500)에서 멈춘다.
                    exception.message!! shouldContain "Document nesting depth (501) exceeds the maximum allowed (500"
                    exception.message!! shouldContain "Team[\"members\"]"
                    // 참조 경로가 끝까지 붙으므로 메시지 자체가 25KB 를 넘는다.
                    exception.message!!.length shouldBe 25_139
                }
            }

            When("트랜잭션 밖으로 나온 Member 의 team 을 건드리면") {
                Then("초기화되지 않은 프록시라 LazyInitializationException 이 난다") {
                    val teamId = seedTeam("백엔드", "헌")
                    val memberId = service.findTeamWithMembers(teamId).members.first().id!!

                    val member = service.findMember(memberId)

                    // 반환된 것은 Team 이 아니라 Team 을 상속한 프록시다.
                    (member.team is HibernateProxy).shouldBeTrue()
                    Hibernate.isInitialized(member.team).shouldBeFalse()

                    // Hibernate 7 의 프록시 이름에는 임의 접미사가 붙지 않는다.
                    member.team!!::class.java.name shouldBe "org.example.transactiontest.entity.Team\$HibernateProxy"

                    val exception = shouldThrow<LazyInitializationException> { member.team!!.name }
                    exception.message!! shouldContain "Could not initialize proxy"
                    exception.message!! shouldContain "no session"
                }
            }

            When("그 Member 를 직렬화하면") {
                Then("직렬화기가 프록시를 열려다 같은 예외를 만난다") {
                    val teamId = seedTeam("백엔드", "헌")
                    val memberId = service.findTeamWithMembers(teamId).members.first().id!!

                    val exception = shouldThrow<DatabindException> {
                        objectMapper.writeValueAsString(service.findMember(memberId))
                    }

                    // 예외는 서비스가 아니라 직렬화기 안에서 난다. 원인만 Hibernate 것이다.
                    (exception.cause is LazyInitializationException).shouldBeTrue()
                    exception.message!! shouldContain "Could not initialize proxy"
                }
            }

            When("팀 목록을 조회하고 각 팀의 members 를 건드리면") {
                Then("팀 수만큼 SELECT 가 추가로 나간다 (N+1)") {
                    seedTeam("백엔드", "헌", "진")
                    seedTeam("프론트엔드", "민")
                    seedTeam("인프라", "수", "현")

                    statistics.clear()
                    val touched = txExecutor.newTransaction(readOnly = true) {
                        teamRepository.findAll().sumOf { it.members.size }
                    }

                    touched shouldBe 5
                    // 팀 목록 1번 + 팀마다 members 1번씩 3번 = 4번.
                    statistics.prepareStatementCount shouldBe 4
                }
            }
        }

        Given("대응책을 하나씩 적용할 때") {

            When("Team.members 에 @JsonIgnore 를 붙이면") {
                Then("순환은 끊기지만 Member 쪽 프록시 문제는 그대로다") {
                    val teamId = seedTeam("백엔드", "헌")
                    val memberId = service.findTeamWithMembers(teamId).members.first().id!!
                    val mapper = mapperIgnoring(Team::class.java, IgnoreMembers::class.java)

                    val json = mapper.writeValueAsString(service.findTeamWithMembers(teamId))
                    json shouldContain "\"name\":\"백엔드\""
                    json shouldNotContain "members"

                    shouldThrow<DatabindException> { mapper.writeValueAsString(service.findMember(memberId)) }
                }
            }

            When("Member.team 에 @JsonIgnore 를 붙이면") {
                Then("둘 다 막히지만 어떤 응답에서도 팀 정보를 실을 수 없게 된다") {
                    val teamId = seedTeam("백엔드", "헌")
                    val memberId = service.findTeamWithMembers(teamId).members.first().id!!
                    val mapper = mapperIgnoring(Member::class.java, IgnoreTeam::class.java)

                    mapper.writeValueAsString(service.findTeamWithMembers(teamId)) shouldContain "\"name\":\"헌\""

                    val memberJson = mapper.writeValueAsString(service.findMember(memberId))
                    memberJson shouldNotContain "team"
                }
            }

            When("트랜잭션 안에서 DTO 로 옮겨 반환하면") {
                Then("순환도 프록시도 남지 않는다") {
                    val teamId = seedTeam("백엔드", "헌")
                    val memberId = service.findTeamWithMembers(teamId).members.first().id!!

                    val json = objectMapper.writeValueAsString(service.findMemberAsDto(memberId))

                    json shouldBe """{"id":$memberId,"name":"헌","teamName":"백엔드"}"""
                }
            }
        }

        Given("Kotlin 엔티티가 final 일 때") {
            When("Hibernate 가 프록시를 만들려 하면") {
                Then("allopen 이 적용돼 있어야 상속이 가능하고, 그래야 LAZY 가 실제로 걸린다") {
                    // final 이면 프록시를 만들지 못해 @ManyToOne(fetch = LAZY) 가 조용히 즉시 로딩이 된다.
                    // build.gradle.kts 의 allOpen 설정이 그 조건을 만든다.
                    java.lang.reflect.Modifier.isFinal(Team::class.java.modifiers).shouldBeFalse()
                }
            }
        }
    }
}
