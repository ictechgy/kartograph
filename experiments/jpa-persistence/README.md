# JPA·Spring Data persistence 실험 (API 영향 계획 Phase 4b)

`kartograph schema`의 JPA·Spring Data 지원이 기대는 두 전제를 실측한다. 제품 빌드와 기본 CI에는 들어가지 않는다.

1. **S3 — Spring Data 저장소 호출의 JVM 호출 대상 id와 owner 규칙**
2. **명명 벡터 — 엔티티 선언 → 테이블·컬럼 이름이 실제 Hibernate 6·7 스키마 export와 일치하는지**

## S3: 저장소 호출 대상과 owner 규칙

2026-09-28, JDK 21 / Kotlin 2.4.20 / javac 21 / spring-data-jpa 3.5.4 / jakarta.persistence-api 3.1.0에서
합성 앱(Kotlin `interface JobRepo : JpaRepository<Job, Long>`, Java `interface NoteRepo extends CrudRepository<Note, Long>`)을
컴파일해 `javap -c`와 `kartograph snapshot --include-paths`로 확인했다.

| 호출 | bytecode (`javap -c`) | kartograph 스냅샷 |
|---|---|---|
| `repo.save(job)` (상속 CRUD) | `invokeinterface com/example/jobs/JobRepo.save:(Ljava/lang/Object;)Ljava/lang/Object;` | 정점·CALL 간선 없음. `graph.externalCalls`에 `owner=com/example/jobs/JobRepo`, `name=save`, 호출자와 줄 번호가 남는다(`resolution: unresolved`) |
| `repo.findById(id)`, `count()`, Java `deleteAll()` | 모두 사용자 저장소 인터페이스가 owner인 `invokeinterface` (서술자는 소거된 `Object`) | 위와 같다 |
| `repo.findByTitle(t)` (파생 질의, 저장소에 선언) | `invokeinterface …/JobRepo.findByTitle:(Ljava/lang/String;)Ljava/util/List;` | 추상 메서드 정점 `method:com/example/jobs/JobRepo#findByTitle(Ljava/lang/String;)Ljava/util/List;`과 호출자 → 그 정점 CALL 간선이 있다. 추상 메서드라 줄 번호는 없다 |
| `@Query` 메서드 `repo.recent(since)` | 파생 질의와 같다 | 파생 질의와 같다 |

javac와 kotlinc 모두 호출 대상 owner를 `CrudRepository`가 아니라 **정적 수신 타입인 사용자 저장소 인터페이스**로 기록한다.
그러나 상속 CRUD 메서드에는 프로젝트 정점이 없으므로, isthmus `trace`의 핸들러 정방향 도달 집합이 확실히 담는
것은 호출 명령을 가진 메서드뿐이다.

**owner 규칙(결정)**

- 저장소 **호출 지점** 사실(스냅샷이 있을 때)의 `symbol.usr`는 호출 명령을 담은 메서드의 JVM id다(람다 본문이면 그 람다
  메서드 — `reach`가 어휘적 소속 간선으로 따라간다). 위치는 호출자의 소스 경로와 호출 줄(외부 호출) 또는 호출자 선언 줄이다.
- 저장소에 **선언된** 질의 메서드(파생·`@Query`·이름 있는 질의)의 선언 위치 사실은 인터페이스 메서드 id를 싣는다 — CALL
  간선이 있어 정방향 도달에 포함되고, 역방향 `impact`도 그 id에서 호출자로 이어진다.
- 상속 CRUD 표면은 저장소 선언 위치에 symbol 없는 관계 사실로도 남긴다(스냅샷이 없어도 테이블 사용을 알 수 있게).

**isthmus 왕복(같은 날, isthmus `9de927a`, schemagraph `703a21f`)**: Boot 3.5 BOM을 쓰는 합성 앱과 SQLite DDL에서
`isthmus check code.json sql.json --pairs`가 오류 0으로 관계 3·컬럼 8을 잇고, 합성 route-decl(핸들러 usr)과
`kartograph reach`·`schemagraph impact --format language-traversal`로 만든 trace context에서 두 route 모두
`GET /jobs → JobController.list → JobService.byTitle/recent/byId → jobs(title, created_at) → recent_jobs 뷰`처럼
테이블과 DB 의존자까지 이어졌다. 상속 CRUD(`findById`)도 호출자 usr로 도달했다. Spring 서버 route 생산은 이 작업
범위가 아니어서 route-decl 문서는 손으로 만들었다.

## 명명 벡터

`harness/`는 `fixtures/jpa-naming/src`의 합성 엔티티(Java·Kotlin)를 Hibernate로 부트스트랩하고 hbm2ddl 스크립트
(`SchemaManagementToolCoordinator`, `SchemaExport`와 같은 경로)를 H2 방언으로 쓴 뒤 `create table` 문에서 테이블→컬럼을
읽는다. `run.py`가 여섯 실행을 `fixtures/jpa-naming/vectors.json`으로 모으고, `JpaNamingVectorTest`가 같은 소스를
`kartograph schema --jpa-naming <profile>`과 같은 스캐너로 읽어 테이블 집합·테이블별 컬럼 집합의 **완전 일치**를 요구한다.

| 케이스 | Hibernate | 전략 |
|---|---|---|
| `spring-boot-3` | 6.6.53.Final (Boot 3.5.16 BOM) | `CamelCaseToUnderscoresNamingStrategy` + Boot `SpringImplicitNamingStrategy` |
| `spring-boot-4` | 7.2.24.Final (Boot 4.0.8 BOM), 7.4.5.Final (Boot 4.1.1 BOM) | `PhysicalNamingStrategySnakeCaseImpl` + Boot 4 `SpringImplicitNamingStrategy` |
| `hibernate-6`·`hibernate-7` | 6.6.53, 7.2.24, 7.4.5 | Hibernate 기본값(`PhysicalNamingStrategyStandardImpl` + `ImplicitNamingStrategyJpaCompliantImpl`) |

결과: 6개 실행 × 31개 테이블 모두 kartograph와 일치(100%).

소스로 확인한 버전 차이(벡터가 실행으로 재확인):

- Boot 3.5는 `CamelCaseToUnderscoresNamingStrategy`, Boot 4.0·4.1은 `PhysicalNamingStrategySnakeCaseImpl`을 기본 physical
  전략으로 쓴다(Boot 자동 구성 `HibernateProperties`). Hibernate 7에서 `CamelCaseToUnderscoresNamingStrategy`는
  `PhysicalNamingStrategySnakeCaseImpl`을 상속하는 deprecated 별칭이다.
- Hibernate 7 snake-case는 숫자도 소문자처럼 경계로 본다(`address2Line`: 6 → `address2line`, 7 → `address2_line`).
- Hibernate 6은 인용 식별자에도 `_` 삽입과 소문자화를 적용하지만(`"UserAccount"` → `user_account`), 7은 그대로 둔다.
- `SpringImplicitNamingStrategy`는 두 세대 모두 join table 이름만 `{소유 테이블}_{속성}`으로 바꾼다(JPA 기본은
  `{소유 테이블}_{대상 테이블}`). implicit 전략 본문은 Hibernate 6.6과 7.4 사이에 동작 차이가 없다.
- 순수 Hibernate 6과 7의 기본 전략 결과는 벡터에서 같았다.

## 재현

```sh
python3 experiments/jpa-persistence/run.py          # vectors.json을 다시 쓴다
python3 experiments/jpa-persistence/run.py --check  # 현재 vectors.json과 오라클이 같은지 확인한다
```

JDK 21과 Maven Central 접근이 필요하다(Hibernate·Spring Boot 좌표를 내려받는다). 기본 CI는 네트워크 의존 오라클을
돌리지 않고, 커밋된 `vectors.json`에 대한 `JpaNamingVectorTest`만 실행한다.

## 한계

- 벡터는 명명 규칙을 검증한다. 모델링하지 않은 매핑(`@SecondaryTable`·`@Column(table)`, Map 컬렉션, `@MapsId`·공유 PK,
  복합 FK의 암묵 이름, `@AssociationOverride`, Kotlin `@get:` property access, 위임 프로퍼티)은 이름을 추측하지 않고
  dynamic 사실과 `jpa-unmodelled-mappings:`로 남긴다.
- Spring Boot 2(Hibernate 5, `javax.persistence`)는 실행으로 검증하지 않았다. 감지되면 검증된 조합 전체를 후보로 두어
  결과가 갈리는 이름만 dynamic으로 내린다.
- 설정 밖(환경 변수, 외부 설정 서버)의 명명 재정의는 관측하지 못한다 — `jpa-naming-assumed:`가 근거 파일을 밝힌다.
