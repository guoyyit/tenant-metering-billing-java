# 本地租户计量计费服务 (Local Tenant Metering & Billing)

一个基于 Spring Boot 的**本地**租户计量与计费服务：接收带租户/指标/发生时间/去重键的调用
记录，按可配置的阶梯单价与结算周期生成账单，支持重复与迟到处理、账期关闭/重开、规则版本
管理、并发安全快照以及崩溃恢复。无外部数据库依赖，状态保存在本地只追加（write-ahead）日志中。

## 1. 运行与构建

```bash
mvn spring-boot:run          # 启动服务（默认端口 8080）
mvn test                     # 运行全部自动化测试（15 个）
```

数据默认写入工作目录下的 `data/`（日志文件 `data/events.log`，JVM 锁文件
`data/events.log.lock`）。

## 2. 数据模型与核心概念

| 概念 | 说明 |
| --- | --- |
| 租户 Tenant | 以 `tenantId` 标识。首次写入时按全局默认配置**懒自动开通**，无需单独注册。 |
| 指标 Metric | 计费项名称，如 `api_calls`。每个指标有独立的阶梯价格版本历史。 |
| 调用记录 UsageRecord | `(tenantId, metric, occurredAt, dedupKey, quantity)`，另记录接收时间、归属账期、状态与拒绝原因。 |
| 账期 Period | 由结算周期（日/周/月）与周期键标识。 |
| 账单 Bill | 某租户某账期一次结算的不可变结果，含每个指标的用量、所用规则版本、金额、修订号。 |
| 规则版本 RuleVersion | 某指标的一版阶梯单价，含 `version` 与 `effectiveAt`。 |

## 3. REST API

所有接口前缀 `/api`。请求/响应均为 JSON。

### 3.1 上报调用记录

```
POST /api/usage
{
  "tenantId": "tenant-a",
  "metric": "api_calls",
  "occurredAt": 1790579552614,   // 可选，缺省为服务端当前时间（epoch 毫秒）
  "dedupKey": "client-uuid-1",   // 必填，(tenantId, dedupKey) 全局唯一
  "quantity": 15                 // 可选，缺省 1，必须为正
}
```

响应：

```
{ "accepted": true, "duplicate": false, "periodKey": "2026-09", "reason": null }
```

### 3.2 租户配置

```
POST /api/tenants/{tenantId}/config
{ "cycle": "MONTHLY", "latePolicy": "NEAREST_WRITABLE_PERIOD", "lateWindowMillis": 604800000 }

GET  /api/tenants/{tenantId}/config
```

`lateWindowMillis` 省略则保持原值。

### 3.3 配置阶梯价格

```
POST /api/tenants/{tenantId}/rules
{
  "metric": "api_calls",
  "effectiveAt": 1767225600000,        // 可选，缺省为当前时间；不得为未来
  "tiers": [
    {"lowerBoundInclusive": 0,    "upperBoundExclusive": 100, "unitPrice": "1.00"},
    {"lowerBoundInclusive": 100,  "upperBoundExclusive": null, "unitPrice": "0.50"}
  ]
}
```

- 阶梯必须从 `0` 开始、**连续且不重叠**；最后一个阶梯必须开放（`upperBoundExclusive=null`）。
- 每次成功调用返回分配的单调递增版本号：`{ "version": 1 }`。版本号在租户内全局递增。

### 3.4 账期生命周期与账单

```
POST /api/tenants/{tenantId}/periods/{periodKey}/close    # 关闭（幂等）
POST /api/tenants/{tenantId}/periods/{periodKey}/reopen   # 重开（允许补记/修正）
POST /api/tenants/{tenantId}/periods/{periodKey}/settle   # 生成账单（要求账期已关闭）
GET  /api/tenants/{tenantId}/periods/{periodKey}          # 单个账期快照
GET  /api/tenants/{tenantId}/periods                      # 全部账期快照
GET  /api/tenants/{tenantId}/bills/{periodKey}            # 最新账单
GET  /api/tenants/{tenantId}/audit                        # 审计记录
```

账期键格式见第 5 节。错误返回统一 JSON：`{"timestamp","status","error","message"}`，
未知资源为 404，非法请求为 400，业务冲突（如对未关闭账期结算）为 422。

## 4. 计费语义

### 4.1 去重（幂等接收）

- 去重键作用域为 `(tenantId, dedupKey)`，**不区分指标**——调用方应为每条业务事件提供
  全局唯一键。
- 首次出现的键产生一个终态决定（接受或拒绝），并作为单条事件持久化。
- 之后相同键的重复上报直接返回**首次决定**：重复计数 `duplicate=true`，用量**只计一次**。
  - 若首次被接受，重复上报仍返回 `accepted=true`（语义为“该事件已被计入”），但不增加用量。
  - 若首次被拒绝（如未来时间），重复上报仍被拒绝。
- 重启后从日志重建去重索引，跨进程/跨重启同样保证幂等。

### 4.2 阶梯定价

- 采用**分段累进**计价：落在某阶梯内的每个单位按该阶梯单价计费。
  上例 150 单位 = 100×1.00 + 50×0.50 = **150.00**。
- 金额使用 `BigDecimal`，单价以十进制字符串传输，避免浮点误差。

### 4.3 规则变更的生效范围（账单不可变）

- 结算时，选择在**账期最后一毫秒（`periodEndExclusive - 1`）已生效**的最高版本规则。
  之所以用账期结束前一毫秒，是因为账期结束时刻是**排他**边界（exclusive），恰在
  下一账期起点生效的规则不会“倒灌”进上一账期。
- 规则更新**只影响尚未结算的账期**。账单一旦生成，其单价快照（`ruleVersion`、用量、金额）
  即固化，后续再改价不会改变历史账单（`GET .../bills/{period}` 永远返回已生成的内容）。
- 重开历史账期并补记后重新结算，仍然使用**该账期在历史上生效的规则版本**，只是生成更高
  修订号（revision）的新账单；旧账单作为历史保留。

### 4.4 账期关闭与重开

- **关闭幂等**：对同一账期多次 `close` 只有第一次产生事件与审计；对从未写入的账期关闭会
  创建一个零用量的关闭账期（因此可直接结算出零元账单）。
- **只有已关闭账期允许结算**；对开放账期结算返回 422。
- **重开**后允许修正/补记；对本就开放的账期重开返回 400。每次有效的关闭、重开、结算、
  规则变更、记录接受/拒绝都写入审计（带全局单调序号）。重复关闭这种无状态变化的操作不产生
  审计，保证审计只记录真实变更。
- 重开 → 补记 → 再次关闭 → 再次结算，会生成 `revision+1` 的新账单。

### 4.5 迟到记录与时间窗

归属判定是 `(配置, 账期状态, 当前时间)` 的**纯函数**，相同输入结果可复现：

1. `occurredAt` 晚于服务端当前时间（未来记录）→ 一律**拒绝**。
2. 目标账期**未显式关闭**且 `now < periodEndExclusive + lateWindowMillis` → 直接计入目标账期。
   - “迟到窗口”只针对**已自然结束但尚未显式关闭**的账期；账期一旦被显式 `close`，即使仍在
     窗口内也不再接受直接写入（否则关闭无意义）。
3. 目标账期已关闭或已超出迟到窗口时，按租户策略处理：

| 策略 | 行为 |
| --- | --- |
| `REJECT` | 拒绝该记录（事件与审计中标记 `RECORD_REJECTED`，原因可读）。 |
| `CURRENT_PERIOD` | 计入当前账期；若当前账期也被关闭则拒绝。 |
| `NEAREST_WRITABLE_PERIOD` | 从目标账期向后找到最近一个“可写”（从未创建或未关闭）的账期计入；全部已关闭则拒绝。默认策略。 |

被拒绝的记录同样持久化（终态为 `REJECTED`），因此其去重键之后重复提交不会“变成接受”。

## 5. 时间边界（全部使用 UTC）

周期键与边界与时区无关、结果可复现：

| 周期 | 键格式 | 示例 | 区间（UTC，左闭右开） |
| --- | --- | --- | --- |
| `DAILY` | `yyyy-MM-dd` | `2026-09-28` | 当日 00:00 起，至次日 00:00 前 |
| `WEEKLY` | ISO-8601 `yyyy-Www` | `2026-W40` | 周一 00:00 起，至下周一 00:00 前 |
| `MONTHLY` | `yyyy-MM` | `2026-09` | 当月 1 日 00:00 起，至下月 1 日 00:00 前 |

关键边界约定：

- 账期 `[start, endExclusive)`；发生时刻 `occurredAt` 满足 `start <= occurredAt < endExclusive`
  即归属该账期。
- 结算选价时点为 `endExclusive - 1ms`（账期内最后一毫秒），避免新规则在边界点倒灌。
- 迟到窗口判断 `now < endExclusive + lateWindowMillis`。

## 6. 并发与一致性快照

- 所有写操作经同一把引擎写锁串行化；读操作持读锁。任一时点读到的账期用量、关闭状态、
  已结算修订号都来自**同一个已提交事件前缀**，不会出现“用量已加但账单半计入”的中间态。
- 每次提交：**先把一条事件 fsync 持久化，再推进内存状态**。账单是单个原子事件，因此读视角中
  账单要么完整存在、要么不存在。
- 结算要求账期关闭，天然形成“封口快照”：账单金额恒等于快照用量按所记录规则版本计算的结果；
- 关闭后到达的写入按迟到策略重定向或拒绝，绝不会部分计入正在结算的账期。

## 7. 崩溃恢复与未完成结算

- 存储为只追加日志 `data/events.log`，每行一个 JSON 信封：
  `{"seq":1,"crc":<payload 的 crc32>,"type":"...","payload":{...}}\n`。
- 每次 append 都 `force(true)`（fsync 数据）并 fsync 目录；序号为严格连续的 1..N。
- 启动时**顺序重放**全部事件重建内存状态（配置、去重索引、用量、账期、账单、审计）。
  已提交的记录与账单**不丢失、不重复**。
- 每行携带 CRC32（针对内嵌 payload 的原始字节）。重放遇到部分写入/校验失败/未知类型/序号
  断裂的行，即判定为崩溃残留尾部，并将文件**截断到最后一条有效记录之后**，随后从一致前缀恢复。
- `data/events.log.lock` 使用 JVM 排他文件锁，防止两个服务实例同时打开同一数据目录。
- **未完成结算的语义**：账单是“一次性原子落盘”的事件。若崩溃发生在结算事件落盘之前，重启后
  账期回到“已关闭、未结算”，可**继续重新结算**；若已落盘，则账单完整恢复。需要放弃一次已落盘
  结算时，可 `reopen`（回滚到可修正状态）→ 修改 → 重新关闭 → 重新结算，产生新修订号。

## 8. 配置项

在 `application.properties` 或通过系统属性 / 环境变量配置（Spring Boot 松散绑定）：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `metering.data-dir` | `data` | WAL 日志目录。 |
| `metering.log-file` | `events.log` | 目录内日志文件名。 |
| `metering.default-cycle` | `MONTHLY` | 自动开通租户的结算周期：`DAILY`/`WEEKLY`/`MONTHLY`。 |
| `metering.default-late-policy` | `NEAREST_WRITABLE_PERIOD` | 自动开通租户的迟到策略。 |
| `metering.default-late-window-millis` | `604800000`（7 天） | 自动开通租户的迟到窗口（毫秒）。 |

租户级配置可随时通过 `POST /api/tenants/{id}/config` 覆盖；新配置对该租户后续判定立即生效，
但不改变已生成账单。

## 9. 代码结构

```
domain/   领域模型（枚举、不可变 record、可变账期状态）
event/    WAL 事件（sealed LogEvent 及其各实现）
storage/  EventLog 接口与 FileEventLog（fsync + CRC + 尾部截断 + 文件锁）
billing/  BillingService 引擎（锁、去重、归属、定价、结算、重放）、PeriodCalculator、配置属性
web/      REST 控制器与统一异常处理
```

## 10. 测试覆盖

`src/test/.../tests/` 共 15 个用例：

- `BillingServiceTest`：端到端去重 + 阶梯计价冒烟。
- `LateArrivalTest`：三种迟到策略、迟到窗口、未来记录、拒绝键幂等。
- `PeriodLifecycleTest`：关闭幂等、重开补记、账单修订、审计完整性。
- `RuleChangeTest`：规则版本化、历史账单不变、重开后仍用历史价、非法阶梯拒绝。
- `ConcurrencyTest`：多线程并发写入无丢失、重复键跨线程只计一次、并发读快照自洽。
- `SettlementConcurrencyTest`：结算与写入并发时账单恒对应单一已提交前缀。
- `RecoveryTest`：重启后状态/账单/去重完整恢复，损坏尾部截断。
- `WebApiTest`：HTTP 全链路与错误码映射。
