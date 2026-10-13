# Engineering V2 集成记录

补丁版本：engineering-v2-20261013；基线：2bbc3e2cd7b95ac97fd0d4df2fe6ba9a5a9b2455。

## 已确认的问题与改动

1. 原 `ai-service/inspection_service/media.py` 标注文本用 class_id（0/1），而网页与人工复核协议使用候选序号（1..N）。新保存图片统一候选序号，同时保留类名/置信度。不修改JSON类别编号；历史图片不重写。
2. 原视觉建议为B/C/D，加工接口为A/B/C/REJECT。新工作区明确区分模型规则建议、人工图像等级、经批准的原料业务输入；禁止静默传D、静默D→REJECT或无候选→A。
3. 原加工表单需重输等级、缺少可核验质检来源。新增服务器侧来源引用接口，使用保存的版本和等级而不是前端参数；保存来源元数据。
4. 连接检查不能证明真实写入/回读。新增真实代理路径验收脚本，默认只读；显式写入模式限定隔离库与新工程测试批次。人工判断及温湿度是标注清楚的测试夹具，不是质量评测。

## 新接口

`GET /workspace/status`（inspection profile）返回 patch_version、database_ready、database_name、review_database、write_flow_verified=false。只供可信本机管理端；不加到公开溯源。

`POST /batches/{batchId}/reviewed-processing-advice` 请求：

```json
{
  "inspection_id": 11,
  "expected_review_revision": 1,
  "operator": "人工批准人",
  "use_as_material_grade_confirmed": true,
  "temperature": 5,
  "humidity": 80,
  "material_temperature": 12
}
```

以上数字仅为协议示例；实际批次/质检ID必须从服务器读取，温湿度必须来自该次业务输入。

后端锁定相同批次的inspection行，校验latest_review和expected_review_revision，接受target_confirmed/no_target_confirmed；只使用已保存的人工A/B/C/REJECT。needs_recheck、D、无等级和过时来源被拒绝。调用原B业务服务并在同一事务中写入grade_source；来源写入失败则回滚本次数据库变更。

来源字段位于 `processing_records.inputs_json.grade_source`，包括inspection_id、review_revision、reviewer、final_grade、approved_by和material_scope_confirmed。原GET历史和综合报告可读取该JSON；不增加迁移表。原独立人工输入接口仍存在，旧记录没有来源不会被倒填。

## 前端

管理端布局、首页、质检、加工重点更新，其他模块保留原有业务实现。新增管理端专用样式，不把公开溯源套入内部布局；不新增CDN、图库、字体或前端依赖。

状态条读取真实服务；阶段条读取实际批次报告中的记录数量。阶段存在标志只说明此批次有记录，不证明每张图已完成相同链路，也不证明公开或批次合格。

质检/复核/加工/采用成功后回读确认，不使用固定演示数据占位。尚无登录身份绑定，手填复核人仍属于可信本地原型。

## 视觉诊断边界

A15权重、manifest、推理引擎和线上阈值均不变。PIL_RGB输入、EXIF方向校正和原始坐标维度检查在原代码中已存在；未凭用户描述断言其出错。

`ai-service/tools/diagnose_visual.py` 对提供的原图做真实同图输入适配对照、配置试验和可选GT匹配，写新的诊断目录，不改数据或模型。没有提供实际问题原图/标签时不能确认“训练图片标注不对”的具体根因。

## 验证边界

交付侧执行结果保存在补丁包 verification 中：Python辅助/媒体/安装器测试、Node业务逻辑和JS语法检查。完整工程依赖未在交付侧安装，Vue构建/Java回归/Windows真实A15和MySQL链路待本机验收。

写入验收必须通过新的workspace状态确认smart_fresh_c_review，创建新E2批次且显式同意模拟夹具。测试不自动删除记录、不修改原批次、不把模型准确率标为通过。正式的质量效果报告需要固定样本与独立测试方案，不能拿本脚本通过来替代。
