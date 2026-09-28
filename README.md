# 本地租户计量计费服务（tenant-metering-billing-java）

一个基于 Spring Boot 3 / Java 17 的**本地单机**租户接口计量与计费服务：接收调用记录、去重、
按迟到窗口与可配置策略归属账期、按阶梯价格出账，并通过预写日志（WAL）+ 状态快照实现崩溃恢复。

## 1. 快速开始

```bash
mvn spring-boot:run          # 启动，默认端口 8080
mvn test                     # 运行全部测试（25 个）
```

### 1.1 配置项（`application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `billing.storage.data-dir` | `./data/billing` | WAL（`wal.log`）与快照（`snapshot.json`）所在目录 |
| `billing.storage.snapshot-every-events` | `1000` | 每提交多少个事件落一次状态快照 |

### 1.2 REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tenants` | 配置租户：周期类型、迟到窗口（分钟）、超期策略 |
| PUT  | `/api/tenants/{t}/metrics/{m}/rules` | 写入指标阶梯价格（产生新版本） |
| POST | `/api/usage` | 提交调用记录 `tenantId/metricName/occurTimeEpochMs/dedupKey` |
| GET  | `/api/tenants/{t}/periods/{p}/usage` | 查询账期累计用量快照 |
| POST | `/api/tenants/{t}/periods/{p}/settle` | 结算出账（幂等，结算后自动关闭账期） |
| GET  | `/api/tenants/{t}/periods/{p}/bill` | 查询已生成账单 |
| POST | `/api/tenants/{t}/periods/{p}/close` | 关闭账期（幂等） |
| POST | `/api/tenants/{t}/periods/{p}/reopen` | 重开账期（幂等），允许补记后重新结算 |
| GET  | `/api/tenants/{t}/periods/{p}/audit` | 查询审计流水 |

`PUT rules` 请求体：`{"tierUpperBounds":[1000,10000,null],"unitPrices":[0.10,0.05,0.02]}`。
上限为**含边界**的每档累计用量上限；最后一档传 `null` 表示无上限。

## 2. 计费语义

### 2.1 账期与时间边界

- 周期一律按 **UTC** 计算，当前仅支持 `MONTHLY`，账期 id 为 `yyyy-MM`（如 `2026-09`）。
- 账期为**半开区间** `[月初 00:00:00, 次月 00:00:00)`：边界时刻 `end` 属于下一账期。
- 记录归属在**写入时确定并固化**到记录上，之后不会因时间推移而漂移。
- 记录内同时保存发生时间 `occurTime` 与接收时间 `receivedTime`。

### 2.2 去重与迟到处理

- 去重键为 `(tenantId, dedupKey)`，全局只计一次。重复提交返回 `DUPLICATE` 并带回首次记录 id，
  结果确定、可复现。
- 迟到判定以接收时刻为基准：
  `occurTime < receivedTime - lateWindowMinutes` 即为超期记录，按租户策略处理：
  - `REJECT`：拒绝并写 `USAGE_REJECTED` 审计事件，不计任何用量；
  - `CURRENT_PERIOD`：强制归入接收时刻所在的当前账期；
  - `NEAREST_WRITABLE`：从自然账期开始逐月向当前账期推进，归入第一个**未关闭**账期；
    全部关闭时归入当前账期。
- 未超期的记录（允许轻微未来时钟漂移）归入发生时间的自然账期。
- 已关闭账期不允许写入；当策略计算出的目标账期已关闭时，会继续向最近的可写账期前推。

### 2.3 阶梯计价

- 采用**按档计价**：数量落在 `(lower, upper]` 区间的部分乘以该档单价，各档求和。
  例：三档价 `0.10 / 0.05 / 0.02`，用量 15000 →
  `1000*0.10 + 9000*0.05 + 5000*0.02 = 650.00`。
- 金额统一为 2 位小数，`HALF_UP` 四舍五入。

### 2.4 规则版本

- 每次 `PUT rules` 产生租户内单调递增的新版本号。
- 结算时把**当时的规则版本、用量、金额快照**写入账单行；此后规则再变更**不影响已生成账单**。
- 因此规则更新天然只对尚未结算的周期生效。

### 2.5 账期关闭 / 重开

- **关闭幂等**：重复关闭不产生新事件，返回首次关闭的审计记录。
- **重开幂等**：对已打开账期重复重开不产生新事件；对从未关闭的账期重开返回 409。
- 重开时：当前 `SETTLED` 账单被标记为 `VOIDED` 并永久保留（审计可查），账期恢复可写，
  允许修正或补记；再次结算生成新账单。
- 每次关闭、重开、补记、结算、规则变更、拒绝均写审计流水（事件即审计源）。

### 2.6 一致性（并发语义）

- 内存状态受**单把公平读写锁**保护：所有写入串行提交，读取在共享读锁内完成。
- 提交顺序：业务校验 → 构造事件 → **WAL append + fsync** → 在锁内应用到内存。
- 账单与累计用量在**同一个锁临界区、同一状态版本**内读取，任何时点都不会出现“部分计入”。
- 结算为单条原子事件，与并发写入严格串行；重复结算幂等返回原账单。
- 结算成功后自动关闭账期（若尚未关闭），冻结用量。

## 3. 崩溃恢复

- 所有变更都是**事件溯源**：先写 WAL（每行一条 JSON，append 后 `fsync`），再更新内存。
  - fsync 前崩溃：事件不存在，重启后状态回到之前，调用方重试即可（去重保证幂等）。
  - fsync 后崩溃：事件完整保留，重启重放后恢复，不丢不重。
- 启动恢复流程：打开 WAL（自动识别并**截断尾部半截/损坏行**，对应未提交写入）
  → 加载最近快照（缺失/损坏则全量重放）→ 确定性重放快照之后的事件。
- 快照采用「写临时文件 + fsync + 原子 rename」，永远不会读到半个快照。
- 结算是单条 `BILL_SETTLED` 原子事件，不存在“半个账单”：未完成的结算重启后直接回到
  未结算状态，可继续重新结算，无需人工回滚。

## 4. 代码结构

```
model/    领域模型（枚举与不可变 record）
event/    WAL 事件类型与载荷
store/    BillingState 内存状态、WalLog、SnapshotStore
service/  Engine（锁/提交）、PeriodService、ConfigService、IngestService、
          BillingService、PeriodLifecycleService、RecoveryService、DefaultStateApplier
api/      BillingController（REST）
```

## 5. 测试覆盖

| 测试类 | 覆盖点 |
| --- | --- |
| `PeriodServiceTest` | UTC 账期、半开区间边界、跨月跨年偏移 |
| `PricingTest` | 阶梯计价边界（0、恰好在边界、跨档） |
| `IngestTest` | 去重、三种超期策略、窗口内迟到归属、关闭账期阻挡 |
| `BillingTest` | 阶梯出账、结算幂等、规则变更不影响已出账单、新周期用新价 |
| `PeriodLifecycleTest` | 关闭幂等、重开补记重算、重开幂等、审计完整性 |
| `ConcurrencyTest` | 16 线程并发去重只计一次；并发写入与结算下账单/快照始终自洽 |
| `RecoveryTest` | 重启不丢不重、去重状态恢复、重开恢复、快照+WAL 重放、WAL 半行截断 |
| `ApiIntegrationTest` | REST 端到端：配置→规则→写入→去重→查询→结算→关闭→拒绝 |

## 6. 局限与说明

- 单机本地服务，不支持多实例部署；并发扩展靠单进程读写锁。
- 当前周期类型仅月度；`PeriodType` 已预留枚举，可扩展。
- 审计日志随状态常驻内存并持久化于快照/WAL，超大租户量场景下可增加分段归档。
