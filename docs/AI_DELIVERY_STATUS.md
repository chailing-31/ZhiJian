# A15 交付进度与证据层级

更新：2026-10-12；范围仅 A15 模型资产及运行交接，不覆盖 B/C 尚未合并的开发。

| 事项 | 已有证据 | 尚需确认 |
| --- | --- | --- |
| A15训练/本机部署 | A于2026-10-06分享的真实运行与接口记录 | 不等于C环境验证 |
| A的工程回归 | 45 AI、14后端、31前端；Full Demo 10/10（历史日志） | 新版本集成后由C复跑 |
| 本次权重资产 | 上传ZIP中真实best.pt，6,252,266字节；SHA已独立核验 | 提交后需核对Git跟踪与远端分支 |
| C业务集成 | C分享的截图报告后端20、B服务183、前端36、24个Vue检查及业务流程通过 | 本次未重跑这些结果；不能代替C真实YOLO验证 |
| C真实YOLO | 此前因缺权重和manifest未完成 | 收到本分支完整资产后，加载及真实上传验收 |
| 生产安全/性能认证 | 未完成 | 本包不作生产级承诺 |

## 已纠正的交付缺口

原提交 `3864023` 只更新启动脚本中的外部D盘路径。`.gitignore` 屏蔽了所有 `.pt`，因此只拉Git代码拿不到模型。**代码合入不等于权重交付。**

本次补齐计划是：仅为 `ai-service/models/a15/best.pt` 增加精确白名单，纳入一个固定二进制；同步相对manifest、模型卡、校验脚本与启动说明。其余模型实验和数据仍保持忽略。私发ZIP可先解除C阻塞，但以包含权重的PR合并为正式仓库交付。

## C验收记录（由实际执行者补填）

```text
Git commit:
Python / torch / ultralytics:
weights_sha256: 05c5329e01d4c3bea9a1ca48f336a215dbf985b914af63268c9e34fb01f96ed1
A15_ASSET_VERIFIED:
A15_SERVICE_READY:
真实上传 inspection_id:
实际 prediction.model_version:
证据重新读取:
人工复核 revision:
公开摘要与隐私:
执行人/日期:
```

留空表示待验证，不代表失败，也不能自动填PASS。模型身份/参数见 [AI_MODEL_A15.md](AI_MODEL_A15.md)，启动见 [AI_HANDOFF_A15.md](AI_HANDOFF_A15.md)。
