# A9：SSDA 类别语义与标签审计

更新时间：2026-10-05

## 1. 审计结论

对本地 SSDA 原始副本和训练准备副本分别进行 YOLO 标签只读统计，两份数据完全一致，无空标签文件和格式异常。

| Split | 图像/标签文件 | class 0 | class 1 | 总框数 |
| --- | ---: | ---: | ---: | ---: |
| train | 568 | 500 | 716 | 1216 |
| val | 142 | 124 | 172 | 296 |

按 SSDA 原始类别统计顺序进行精确计数匹配后，当前训练权重的类别 ID 语义确认为：

```text
class 0 -> scratch     -> 表面擦伤
class 1 -> pest damage -> 虫害损伤
```

两份本地数据的 train/val 文件数、每类框数均一致，因此训练准备过程没有改变类别 ID 顺序。

## 2. 工程处理

当前权重内部的技术类名仍是：

```text
0 -> ssda_class_0
1 -> ssda_class_1
```

AI 引擎会校验权重中的 `model.names` 与 manifest `name` 完全一致，因此 **不修改技术类名**，只把 manifest 的用户可见 `label` 正式化：

```text
ssda_class_0 -> 表面擦伤（Scratch）
ssda_class_1 -> 虫害损伤（Pest damage）
```

这样不需要重新训练，也不会改变模型输出 class ID。

A9 后的本地 manifest 建议把 `model_version` 增加 `-sem-v1` 后缀，用来区分“同一权重、同一阈值，但类别语义元数据已经确认”的新记录。权重 SHA、置信度阈值、NMS 阈值、图像尺寸保持不变。

## 3. A9 不代表完成独立评测

A9 只解决“class 0/1 到底是什么”的类别语义问题，不改变以下限制：

- 原 SSDA 仍只有 train/val，没有独立 test；
- 同一果实是否可能跨 train/val 仍需审计；
- 没有独立 normal-only 集合；
- 当前 `evaluation_status` 继续保持 `not_evaluated`；
- “未检出表面擦伤或虫害损伤”不等于“正常苹果”；
- 还没有自动严重度和建议等级规则。

因此下一阶段应进入 A10 独立测试/正常样本，再进入 A11 严重度与 A12 建议等级。
