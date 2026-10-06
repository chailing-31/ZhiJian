# A12 开发版建议等级规则（Development Rule v1）

## 1. 目的

A12 将 A11 的可解释缺陷负担证据映射为 Demo 内部的建议等级。它不是国家标准、行业标准、采购标准或食品安全结论，也不会自动写入人工最终等级。

## 2. 规则

仅当当前模型检出目标候选时给出建议：

- A11 `low` -> 建议 `B`
- A11 `moderate` -> 建议 `C`
- A11 `high` -> 建议 `D`

当 A11 为 `none_observed` 时：

- `suggested_grade = null`
- `grade_status = withheld_no_target_observed`
- **不自动建议 A**

原因是当前检测器只覆盖已配置的目标缺陷类别（表面擦伤、虫害损伤）。没有检测框只表示当前模型、当前阈值下未观察到这些目标候选，不能据此证明正常、A级、整批合格或食品安全。

## 3. 输出字段

- `grade_rule_version = a12-dev-grade-v1`
- `grade_basis = defect_burden_level`
- `grade_status`
  - `development_rule_applied`
  - `withheld_no_target_observed`
- `suggested_grade`
  - `B / C / D`
  - 或 `null`

## 4. 人工复核边界

- `suggested_grade` 不会自动复制到 `final_grade`。
- `final_grade` 仍由人工复核填写并单独保存。
- A12 不改变批次 `status`，不会自动将批次标记为合格。
- A 级暂不由当前模型自动建议。若未来加入经独立验证的 normal / quality 模型或更完整的质量属性，再单独设计 A 级自动建议条件。

## 5. 最终验证

A12 仍属于 development rule。A13 需要使用从未参与训练、阈值选择或开发决策的真实手机拍摄样本进行独立验证，再决定最终冻结状态。
