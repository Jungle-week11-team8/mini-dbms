# mini-dbms

[← README](../README.md)

## 인덱스
> 100만 행에서 `_id` 인덱스 조회가 선형 조회보다 **수백 배 이상** 빠름

### 성능 비교
| 항목 | 머신 1 | 머신 2 | 머신 3 |
| --- | --- | --- | --- |
| 조회 1건: 인덱스 | 0.38 ms | 0.035 ms | 2.0 ms |
| 조회 1건: 선형 INT | 456 ms | 155 ms | 732 ms |
| 인덱스 / 선형 INT | 1,196배 | 4,420배 | 361배 |

### B+ 트리 리프 값
| 후보 | 결과 |
| --- | --- |
| **바이트 오프셋**  | 해당 위치로 바로 점프, 한 줄만 읽음 |
| 줄 번호 | 줄 길이가 제각각, 처음부터 줄바꿈을 세야 함 |
| 행 데이터 자체 | 가장 빠르지만 100만 행이 전부 메모리에 올라감 |

- 메모리에만 보관, 실행마다 CSV를 훑어 재생성
- 서버는 `TableState`가 보관, 최초 1회 생성 후 요청마다 재사용
- 재구축 비용은 조회 1~2번이면 회수

<br/>

## API 서버
> `POST /query` 하나로 SQL 1문장 실행, CLI와 같은 엔진 사용

### 구조
![구조](images/architecture.svg)

| 패키지 | 클래스 | 역할 |
| --- | --- | --- |
| `server/` | `ApiServer`, `QueryHandler` | HTTP 입출력 |
| `parser/` | `Lexer`, `Parser` | SQL 해석 |
| `executor/` | `Executor`, `TableState` | 쿼리 실행, 동시성 제어 |
| `storage/` | `Storage` | 파일 읽기/쓰기 |
| `index/` | `BPlusTree` | `_id` 인덱스 |

### 요청 처리 흐름
| 단계 | 하는 일 | 실패 시 |
| --- | --- | --- |
| 받기 | JDK `HttpServer` + 고정 스레드 8개 | - |
| 검사 | 경로 `/query`, 메서드 POST, 본문 1MB 이하 | 404 / 405 / 413 |
| 해석 | Parser, SQL 정확히 1문장 | 400 (문법 오류, 문장 0개 또는 2개 이상) |
| 실행 | `Executor`가 INSERT·SELECT 처리 | 400 (타입 오류, 없는 테이블·컬럼) / 500 (디스크 오류) |

- `HttpServer`: 스레드 풀 직접 설정 가능
- 엔드포인트 1개: 기존 Parser 재사용
- 요청당 1문장: 락을 한 번만 잡으므로 데드락 없음

### 요청·응답 예시
```bash
curl -X POST http://127.0.0.1:8080/query --data "INSERT INTO students VALUES ('김철수', 20);"
# {"inserted":1,"id":1}
curl -X POST http://127.0.0.1:8080/query --data "SELECT * FROM students WHERE _id = 1;"
# {"columns":["_id","name","age"],"rows":[["1","김철수","20"]]}
```

<br/>

## 동시성
> 같은 테이블은 하나씩, 다른 테이블끼리는 병렬

### 문제와 해결
| 문제 | 해결 |
| --- | --- |
| 스레드 8개가 같은 테이블에 동시 INSERT → `_id` 중복, 행 깨짐 | 테이블별 `synchronized` 배타 락 안에서 인덱스 생성, CSV 쓰기, 다음 `_id` 예약 |
| 다른 테이블 락을 잡은 두 스레드가 같은 `HashMap`에 동시 `put` | `ConcurrentHashMap`의 `computeIfAbsent`로 테이블당 `TableState` 하나 |
| 서버 실행 중 CLI가 `data/`에 쓰면 서버가 그 행을 모름 | `data/.lock` 파일 락, 못 잡으면 바로 종료 (프로세스 종료 시 자동 해제) |

- 테이블 락: 상태(인덱스, 다음 `_id`, CSV)가 전부 테이블 단위로 나뉨
- 행 락 불필요: INSERT만 있어 한 번 쓴 행은 불변, 충돌 지점은 B+ 트리 전체
- 검증: `ExecutorTest` 8스레드 동시 INSERT에서 `_id` 중복·행 깨짐 없음

<br/>

## SQLite와 비교
> 데드락 위험과 구현 기간을 고려해 읽기/쓰기 락 대신 배타 락 선택

| 항목 | mini-dbms | SQLite | 이유 |
| --- | --- | --- | --- |
| 스레드 동시성 | 테이블별 배타 락(읽기끼리도 하나씩) | 읽기 여럿·쓰기 하나(WAL이면 읽기와 쓰기 동시) | 처리할 것이 적은 배타 락 선택 |
| 프로세스 동시성 | `data/.lock`, 못 잡으면 바로 종료 | 파일 락, 기다리거나 busy 오류 | 한 프로세스만 허용 |

- 읽기/쓰기 락: SELECT가 많을 때 유리하지만, 첫 SELECT가 인덱스를 만들며 상태 변경 → 읽기 락을 쥔 채 쓰기 락 요청 시 데드락
- 락 잡는 곳이 한 군데라 추후 변경 비용 작음
