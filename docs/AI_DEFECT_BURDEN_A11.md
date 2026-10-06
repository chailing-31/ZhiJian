# A11 缺陷负担证据（Development Rule v1）

## 1. 目的

A11 在单张苹果图像的模型候选框基础上提供可解释的**缺陷负担证据**，不输出正式质量等级，不替代人工复核。

`defect_burden` 的分母是整张图像面积，不是苹果实例面积、真实果面面积或食品安全指标。

## 2. 数据依据

A11.1 在 `ssda-group30-neg-v1` 上完成几何分布审计：

- train: 771 张，其中 568 张正样本、203 张 normal hard negatives；1209 个缺陷框。
- val: 142 张正样本；303 个缺陷框。
- positive-image union bbox ratio：
  - train p50 0.663%，p90 2.953%，p95 4.151%
  - val p50 0.616%，p90 3.254%，p95 4.039%
- class 0（Scratch）单框 p90：train 2.274%，val 2.512%
- class 1（Pest damage）单框 p90：train 0.449%，val 0.410%
- 每张正样本候选框数量 p90：train / val 均为 4。
- 训练标签中发现 1 个轻微越界框，最大归一化越界 0.00208623；统计时仅内存 clip，未修改源标签。

因此 v1 使用接近共同中位数、p90 和类别 p90 的开发阈值。

## 3. 规则

基础等级按所有候选框几何并集 / 整图面积：

- 无候选：`none_observed`
- `< 0.006`：`low`
- `[0.006, 0.03)`：`moderate`
- `>= 0.03`：`high`

升级条件：

- `detection_count >= 4`
- 任一 `ssda_class_0` bbox / 整图面积 `>= 0.025`
- 任一 `ssda_class_1` bbox / 整图面积 `>= 0.0045`

任一升级条件触发时只上调一级：`low -> moderate -> high`，不因多个 flag 连续上调。

## 4. 边界

- `none_observed` 仅表示当前模型、当前阈值下没有观察到两类目标候选，**不等于正常、合格或食品安全**。
- `low/moderate/high` 是 data-calibrated development burden tier，不是国家、行业或采购标准。
- A11 不改变 `suggested_grade=null` 和 `grade_status=grading_rule_not_configured`。
- A12 才负责把可解释证据映射为 Demo 建议等级；`final_grade` 仍由人工复核产生。
- 最终 A13 仍需从未参与调参/决策的真实手机图片进行独立验证。
