# 模型权重

<!-- A15-HANDOFF-CURRENT:BEGIN -->
## A15 固定模型资产

唯一纳入常规Git的冻结权重：`a15/best.pt`，6,252,266字节。配套 `a15/model-manifest.json`、`a15/SHA256SUMS`。

SHA256：`05c5329e01d4c3bea9a1ca48f336a215dbf985b914af63268c9e34fb01f96ed1`。

这是本次针对单个约6MB资产的精确白名单例外。下方历史“模型不进Git”口径不再适用于这一个文件；其他试验模型、训练数据和运行产物仍不提交。

从仓库根运行 `python ai-service/tools/verify_a15.py --require-tracked`。模型卡见 [AI_MODEL_A15](../../docs/AI_MODEL_A15.md)，不把示例manifest、空权重或通用YOLO替换为苹果缺陷模型。
<!-- A15-HANDOFF-CURRENT:END -->

权重文件不纳入 Git。A 在此说明合法获取位置、版本、文件校验值和本地 `MODEL_PATH` 配置；交付前不得放入未经许可的模型或数据。
