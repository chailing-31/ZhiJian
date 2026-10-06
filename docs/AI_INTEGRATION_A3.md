# A3：真实质检上传、持久化与人工复核（本地集成候选 v0.1）

## 范围与当前证据

需求基于现有六模块骨架、A1 内部服务和用户已返回的单图推理 JSON。用户已验证 8001 /ready 和单图推理/结果图访问；本包不声称 Windows 上新的 Java/SQL/Vue 全链路已验收。

基线：develop@66706cf。B 的加工/冷链仍由 B 开发，未修改其源码。新增代码在 inspection profile 下启用。由用户在自己的 feature-ai 做本地集成，公共变更与 C 对齐。

A3 初始接入时沿用置信度阈值 0.25 和 NMS 阈值 0.70。后续 A5/A6 在同一权重上完成开发集参数筛选和两个真实服务的直接对比后，本地 Demo 冻结为置信度阈值 0.25、NMS 阈值 0.50、图像尺寸 640；权重 SHA 未变。A9 标签审计已确认 `ssda_class_0=表面擦伤（scratch）`、`ssda_class_1=虫害损伤（pest damage）`；技术类名保留以匹配权重，独立评测状态仍为 `not_evaluated`，没有自动等级。完整冻结证据见 `AI_MODEL_FREEZE.md`。

## 数据与事件

原 inspections 表存模型原始候选 JSON、模型版本、原图/结果图内部位置；模型原字段只在首次创建时写入。

新增 inspection_payloads 表与 inspections 一对一：保存 A1 完整响应（包括实际权重 SHA、参数、预测时间和证据相对链接）、request_id、prediction_id和上传图片哈希。数据库主键与模型 prediction UUID 不互换。

新增 inspection_reviews 表与 inspections 一对多：每次人工复核追加版本，保存候选逐条意见、图像结论、复核人、说明、可选人工等级、是否发布通用摘要和时间。inspections 的 reviewer/reviewed_at/final_grade 只保存最新人工复核快照，完整历史在新表保留；不改 detections_json 或 response_json。

上传流程：先校验批次与图片大小 → 调 8001 → 核对响应批次、上传哈希、坐标、字段和模型版本长度 → 从固定内部端点取 input/result PNG → 后端保存到私有目录 → 在一个数据库事务中写 inspections、payload和private事件。

只有图像文件保存成功且数据库提交成功，才向前端返回 persisted=true。网络响应丢失可能发生在事务完成后；同一请求键重试返回同一业务记录。不同浏览器或重新选图会生成新键，不做“同一图片永远不能重检”的限制。

文件系统、AI服务存储和MySQL不构成跨系统事务。AI推理成功但后续下载/数据库失败时可能残留未关联文件，不创建虚假业务成功事件；本版不自动删除这些证据，后续清理须核对数据库和备份。单实例后端用一个Semaphore限制CPU推理并发；不能代替多实例锁或AI服务自身多租户并发管理。

推理事件默认private、说明“候选待复核”。复核默认也为private；勾选公开后，只发布通用“已保存单张图像复核记录，不构成食品安全或整批合格结论”文字，不公开备注、等级、候选或图片。历史public事件不因后续修改自动撤销。模型原图和JSON无公开路由。

## 新增/具体化的业务接口

URL 不含浏览器用的 /api 前缀；由 Vite 去掉 /api 再请求后端。

| 方法 | 路径 | 输入/返回 |
|---|---|---|
| GET | /inspection-service/ready | database_ready、model_ready、ready、message；未开启profile时404 |
| GET | /batches/{batchId}/inspections | 最近100条带payload的记录，内部ID、模型版本、时间、复核版本数 |
| POST | /batches/{batchId}/inspections | multipart字段 image；必需Header Idempotency-Key为UUID |
| GET | /inspections/{id} | 已保存记录、prediction、artifact_urls、reviews、latest_review、review_revision |
| PATCH | /inspections/{id}/review | 追加复核，expected_revision防止并发覆盖或重复提交 |
| GET | /inspections/{id}/artifacts/input | 归一化输入图PNG |
| GET | /inspections/{id}/artifacts/result | A1 保存的原始标注PNG |

创建/详情结构示意（不是硬编码模型结果）：

```json
{
  "inspection_id": 23,
  "batch_id": 1,
  "batch_code": "APPLE-2026-001",
  "persisted": true,
  "prediction": {"schema_version": "zhijian.ai.inspection.v0.1", "detections": []},
  "artifact_urls": {
    "input": "/api/inspections/23/artifacts/input",
    "result": "/api/inspections/23/artifacts/result"
  },
  "review_revision": 0,
  "latest_review": null,
  "reviews": []
}
```

实际 prediction 为 A1 完整原响应删去浏览器不应直接使用的 artifacts 字段；数据库仍保留完整原响应。示意中的23和空数组不得当作固定数据。

复核示例（候选个数和序号必须来自所选记录，不可原样提交到不匹配记录）：

```json
{
  "expected_revision": 0,
  "reviewer": "operator-demo",
  "conclusion": "target_confirmed",
  "remark": "仅针对本次图像。候选2被人工标为候选1的重复定位。",
  "final_grade": null,
  "publish_summary": false,
  "candidate_reviews": [
    {"candidate_index":1,"decision":"confirmed","duplicate_of":null,"note":""},
    {"candidate_index":2,"decision":"duplicate","duplicate_of":1,"note":"重叠候选"}
  ]
}
```

候选序号从1开始，是该记录中原始 detections 数组的顺序，不是 class_id。允许confirmed/false_positive/duplicate/uncertain；每个原候选必须出现一次。duplicate只能指向另一个confirmed候选。存在uncertain时结论只能为needs_recheck。target_confirmed必须至少有一个confirmed；no_target_confirmed不得有confirmed。无框仍可记录needs_recheck或no_target_confirmed，后者不等于正常或整批合格。

复核人目前手填，不是已认证身份。final_grade只是可选的人工文字，不自动推导；没有核定标准时应留空。不能借此宣称监管认证。

## 错误与重试

A3返回明确的{code,message}错误。常见状态：400请求/复核非法，404无记录/未启用profile，409请求键冲突/过期复核版本，413超大图片，422非法图片，502内部响应或取图失败，503模型繁忙/未就绪/数据库迁移未准备，504AI超时，507文件保存失败。其他模块错误格式不改。

上传图片上限10MiB，multipart总上限12MiB；后台取图流式限80MiB/张，PNG签名检查。上传内容由A1再次真实解码，不只看扩展名。JSON响应上限2MiB，最多300个候选。AI HTTP不跟随重定向，不接收前端指定URL；下载路径只由校验后的预测UUID和 input/result 白名单构建，防止将响应里的任意URL当成下载目标。

浏览器300秒超时只停止等待，不保证取消服务器端操作。刷新历史确认结果，保留同一选图的Idempotency-Key重试，不能连续创建新请求掩盖失败。复核409后重新打开最新记录再编辑。

## 操作界面

输入图与候选图并排展示。右侧SVG基于EXIF校正后的输入图和原始像素坐标，叠加1..N候选序号；不改AI服务原始标注图。可打开AI保存的result PNG对照。点击候选序号只高亮，不删除、合并或改阈值。疑似误检、重复、不确定的意见分别保存，原始7框不会因为人工确认6处就消失。

跨批次组件重新挂载，取消浏览器旧请求，清空未提交草稿；后端已开始的上传仍可能完成，但绑定的是请求开始时验证的批次，不会写到新选择批次。

历史只列最近100条A3记录，旧inspections中无payload记录暂不迁移。完整分页、图像编辑、候选新增/改框、数据库历史记录导入不在本包范围。

## 测试与验收

1. 按APPLY执行mvn test、Vue编译检查、Node单测和构建，再进行手动SQL迁移；真实数据库单独验收。
2. /inspection-service/ready三个布尔字段为true；选图后出现真实记录ID/模型版本/参数/时间/图片与候选。
3. 重试同一上传不新增inspection或AI事件；切批次无旧内容残留。
4. 复核提交后刷新仍可见；再次修改生成新版本，原预测JSON和候选数组不变；过期版本返回409。
5. 私有模型事件不出现在公开trace；明确公开后只出现通用复核摘要。
6. 关闭AI服务后上传报错，不产生新的业务成功记录；恢复后能重试；后端历史取图不依赖AI服务仍运行。
7. 原smoke_batch.py保持通过。新增smoke_inspection.py为真实写入式测试，仅用于本地测试库。

制作环境实际执行项及未执行项见包内VALIDATION.md。训练指标和单图CPU速度不属于本包的性能测试。

## 存储与部署限制

新profile默认只监听127.0.0.1；后端脚本将结果图放D盘外部目录。数据库和证据目录需一起备份。A1服务的模型和存储路径不变。

本版没有用户登录鉴权、角色权限、跨用户隔离、审计身份认证、持久化任务队列或生产文件保留策略，不是公网部署包。不可仅改成0.0.0.0就发布。不要开放MySQL/8001到公网。Docker/Nginx部署需另行对齐profile、AI网络地址、持久卷及client_max_body_size/超时；本包不修改现有Compose或Nginx配置，当前只走已跑通的Windows本机链路。

## 技术依据

以下仅为实现参考；上述接口细化和两张表为本次工程新增，不声称旧任务书已经包含这些字段。

- Spring Boot 3.5 MultipartProperties / MultipartConfigFactory：上传大小与临时目录。
- Spring Framework TransactionTemplate：业务写入和事件同事务、复核版本行锁。
- Java 17 HttpURLConnection：连接/读取超时、显式禁重定向。
- Vue 3：组件卸载时取消前端请求与释放预览URL；模板自动转义。

```text
https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html
https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html
https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/HttpURLConnection.html
https://vuejs.org/guide/essentials/watchers.html
```
