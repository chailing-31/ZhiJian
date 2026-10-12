# AI 模型冻结与 Demo 交付记录

更新时间：2026-10-02

## 1. 冻结对象

当前本地 Demo 使用的苹果表面缺陷检测配置：

```text
model_version = ssda-yolov8n-dev-20261001-8c45a0602dad-nms050
weights_sha256 = 8c45a0602dadf8b9f48b609e29d6d3b5e5868db5f68682da8b32583e104827a4
confidence_threshold = 0.25
iou_threshold = 0.50
image_size = 640
evaluation_status = not_evaluated
```

类别保持中性命名：

```text
0 -> ssda_class_0 -> SSDA 缺陷 0（含义待确认）
1 -> ssda_class_1 -> SSDA 缺陷 1（含义待确认）
```

没有把中性类别改写为未经确认的病害名、机械损伤名或质量等级。

权重文件和真实 `model-manifest.json` 保存在项目外数据目录，不提交到 Git。仓库中的
`ai-service/model-manifest.example.json` 仅作为结构示例。

## 2. 参数选择证据

### A5：开发集阈值筛选

在原 SSDA val 的 142 张图、296 个标注框上，用当前服务 `conf=0.25 / NMS=0.70`
实际推理一次，再离线比较更严格的阈值组合。A5 用于筛选候选配置，不作为最终直接运行结论。

A5 中 `0.25 / 0.50` 保持 TP/FN 与原配置一致，同时减少了开发集固定 IoU 匹配口径下的 FP，
因此进入 A6 直接验证。

### A6：两个真实 AI 服务直接比较

两个服务加载同一权重 SHA：

| 配置 | TP | FP | FN | Precision | Recall | F1 | 候选数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline `0.25 / 0.70` | 233 | 79 | 63 | 74.68% | 78.72% | 76.64% | 312 |
| candidate `0.25 / 0.50` | 233 | 64 | 63 | 78.45% | 78.72% | 78.58% | 297 |

A6 是真实 `NMS=0.50` 运行结果，不是 A5 的二次离线 NMS。固定匹配口径为同类别一对一
IoU >= 0.50。该结果支持把 Demo NMS 从 0.70 调整到 0.50，同时保持当前开发集口径下的
TP、FN 和 Recall 不变。

### A7 + 最终 A4：冻结后回归

A7 将本机正式 manifest 安全切换到 `0.25 / 0.50`，保留原 manifest 备份和 freeze record，
随后重启正式 8001 服务。

冻结后的 A4 固定 20 张功能回归：

```text
planned = 20
completed = 20
failed = 0
candidate_count = 39
images_with_overlap_flags = 0
images_with_low_score_flags = 4
images_with_zero_candidates = 1
median_inference_ms = 59.632
median_http_total_ms = 386.409
```

A4 是接口、证据取回和多图运行回归，不是准确率评测。耗时只代表该次本机单请求运行记录，
不用于宣称多用户吞吐或实时视频性能。

## 3. 业务链路验收

本机已经跑通：

```text
Vue 5173
  -> Spring Boot 8080
  -> AI service 8001
  -> 当前 best.pt
  -> input/result evidence
  -> MySQL inspection record
  -> 人工复核
  -> 复核历史重新读取
```

页面能够显示实际模型版本、置信度阈值、NMS 阈值、模型候选、坐标和推理耗时。
模型原始结果与人工复核分别保存；人工复核不覆盖模型原始候选。

当前没有核定的自动质量分级规则，因此 `suggested_grade` 保持为空，人工等级也不应为了演示强填。

## 4. 重要限制

该冻结版本是 **Demo / development configuration**，不是生产模型认证。

仍未解决：

- 原数据只有 train/val，没有独立 test；
- 同一果实是否跨 train/val 尚未验证，数据泄漏风险仍是审查项；
- 没有经确认的 normal-only 独立评测集合；
- `ssda_class_0/1` 的正式业务语义尚未确认；
- 当前固定 IoU 匹配统计不是 mAP，也不是食品安全指标；
- 没有登录鉴权、生产级访问控制、正式文件保留策略和公网部署加固；
- “未检出目标”不等于正常苹果，也不等于整批合格。

因此仓库和页面继续保留 `evaluation_status = not_evaluated` 以及人工复核提示。

## 5. 团队交付

代码合入后，其他开发机仍需单独完成：

1. 准备匹配的 `best.pt` 与真实 `model-manifest.json`；
2. 核对权重 SHA-256；
3. 配置 CPU 推理依赖；
4. 执行 `database/migrations/20261001_A3_inspection_integration.sql`；
5. 启动 8001 AI 服务；
6. 后端使用 `run-inspection.cmd` 启动；
7. 检查 `/inspection-service/ready`；
8. 用未画框原图完成一次真实上传、保存和人工复核。

不要提交权重、真实预测图片、数据库备份、密码、虚拟环境、`node_modules`、`target` 或 `dist`。
