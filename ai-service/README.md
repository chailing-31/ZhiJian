# AI Service

<!-- A15-HANDOFF-CURRENT:BEGIN -->
## 当前入口：A15 真实模型交付

不再停留在下面的首周计划。当前A15已有真实权重与本机真实推理记录；本次将固定 `models/a15/best.pt` 和 `models/a15/model-manifest.json` 纳入Git交接范围。

仓库根执行 `python ai-service/tools/verify_a15.py --require-tracked` 验证文件；本目录执行 `run-a15.cmd` 启动当前仓库的A15。环境准备、版本和真实上传验收见 [AI_HANDOFF_A15](../docs/AI_HANDOFF_A15.md)。

A15仅检测Scratch/Pest候选；A11输出缺陷负担，A12输出B/C/D/null开发建议，人工复核独立保存。未检出不能自动判正常或A级，`evaluation_status=not_evaluated`保持不变。详见 [模型卡](../docs/AI_MODEL_A15.md) 和 [交付进度](../docs/AI_DELIVERY_STATUS.md)。

下方旧“首周”描述为历史计划，不是当前实现状态。
<!-- A15-HANDOFF-CURRENT:END -->

A 负责 FastAPI 推理服务。首周先确定输入输出契约和样例 JSON，再建立健康检查与无需权重的占位响应；真实模型推理在下一阶段接入。不得把占位输出称为模型结果。模型来源、版本、下载方式和测试样本在交付时补齐。
