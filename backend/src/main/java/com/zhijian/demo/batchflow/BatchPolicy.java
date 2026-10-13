package com.zhijian.demo.batchflow;

import java.util.*;

/** Pure business vocabulary and gates. No model-score-to-batch-grade conversion. */
public final class BatchPolicy {
    public static final String VERSION = "batch-workbench-v4-20261013";
    public static final String GRADE_POLICY = "manual-batch-assessment-v1";
    private BatchPolicy() {}
    public static final Set<String> GRADES = Set.of("A", "B", "C", "REJECT");
    public static final Set<String> USES = Set.of("premium_sorting", "general_sorting", "processing_evaluation", "isolate_return", "undetermined");
    public static boolean reviewed(String conclusion) {
        return Set.of("target_confirmed", "manual_target_confirmed", "no_target_confirmed").contains(conclusion == null ? "" : conclusion);
    }
    public static String gradeLabel(String grade) {
        return switch (grade == null ? "" : grade) {
            case "A" -> "优选外观"; case "B" -> "一般外观，需分选";
            case "C" -> "用途待评估"; case "REJECT" -> "人工拒收"; default -> "待批次评定";
        };
    }
    public static String useLabel(String use) {
        return switch (use == null ? "" : use) {
            case "premium_sorting" -> "优选商品分选"; case "general_sorting" -> "普通商品分选";
            case "processing_evaluation" -> "加工适用性评估"; case "isolate_return" -> "隔离／退货处置";
            default -> "用途待定";
        };
    }
    public static void validateAssessment(String grade, String use, int pictures, int pending, int confirmedImages) {
        if (!GRADES.contains(grade == null ? "" : grade)) throw new IllegalArgumentException("业务等级只能是 A/B/C/REJECT，历史 D 需要重新核定。");
        if (!USES.contains(use == null ? "" : use)) throw new IllegalArgumentException("请选择有效的拟定用途。");
        if (pictures < 1) throw new IllegalArgumentException("当前抽检轮次尚无图片，不能签发批次评定。");
        if (pending != 0) throw new IllegalArgumentException("本轮还有未完成的图片复核，请先处理后再评定。");
        if ("A".equals(grade) && confirmedImages > 0) throw new IllegalArgumentException("本轮确认了缺陷，与本版 A 的定义冲突；请重新检查或另建分选后抽检轮次。");
        if ("REJECT".equals(grade) != "isolate_return".equals(use)) throw new IllegalArgumentException("人工拒收须对应隔离／退货处置，不能同时选择正常商品用途。");
        if ("premium_sorting".equals(use) && !"A".equals(grade)) throw new IllegalArgumentException("优选商品分选只允许人工 A 评定。");
        if ("C".equals(grade) && !Set.of("processing_evaluation", "undetermined").contains(use)) throw new IllegalArgumentException("C 级只能进入用途评估，不能直接批准商品用途。");
    }
    public record Facts(boolean round, int images, int pending, boolean assessed, boolean current,
                        String grade, String use, boolean sorted, boolean processLinked, String adviceStatus,
                        boolean adopted, boolean executed, long readings, boolean coldHold) {}
    public static String next(Facts f) {
        if (!f.round()) return "CREATE_ROUND";
        if (f.images() == 0) return "ADD_IMAGES";
        if (f.pending() > 0) return "REVIEW_IMAGES";
        if (!f.assessed()) return "ASSESS_BATCH";
        if (!f.current()) return "REASSESS_BATCH";
        if ("REJECT".equals(f.grade())) return "REJECTED";
        if (f.coldHold()) return "COLDCHAIN_REVIEW";
        if ("C".equals(f.grade()) || Set.of("processing_evaluation", "undetermined").contains(Objects.toString(f.use(), ""))) return "USE_REVIEW";
        if (!f.sorted()) return "SORTING_RECORD";
        if (!f.processLinked()) return "PROCESS_INPUT";
        if (!"suggested".equals(f.adviceStatus())) return "PROCESS_REVIEW";
        if (!f.adopted()) return "ADOPT_PROCESS";
        if (!f.executed()) return "EXECUTION_RECORD";
        return f.readings() == 0 ? "COLDCHAIN_NOT_STARTED" : "MONITORING";
    }
    public static String actionLabel(String state) {
        return switch(state) {
            case "CREATE_ROUND" -> "建立本轮抽检"; case "ADD_IMAGES" -> "上传或纳入抽检图片";
            case "REVIEW_IMAGES" -> "完成图片复核"; case "ASSESS_BATCH" -> "确认本轮批次评定";
            case "REASSESS_BATCH" -> "证据已更新，请重新核定"; case "REJECTED" -> "登记拒收处置";
            case "COLDCHAIN_REVIEW" -> "核查冷链异常与数据完整性"; case "USE_REVIEW" -> "负责人确认用途和适用性";
            case "SORTING_RECORD" -> "登记实际分选结果"; case "PROCESS_INPUT" -> "补齐工艺条件，生成 B 建议";
            case "PROCESS_REVIEW" -> "工艺规则不适用，请核查"; case "ADOPT_PROCESS" -> "人工确认工艺采用值";
            case "EXECUTION_RECORD" -> "登记实际加工执行"; case "COLDCHAIN_NOT_STARTED" -> "按业务计划录入冷链观测";
            case "MONITORING" -> "查看监测与报告，按需更新公开摘要"; default -> "刷新状态";
        };
    }
    public static String tab(String state) {
        return switch(state) {
            case "CREATE_ROUND", "ADD_IMAGES", "REVIEW_IMAGES" -> "inspection";
            case "ASSESS_BATCH", "REASSESS_BATCH", "USE_REVIEW" -> "assessment";
            case "REJECTED", "SORTING_RECORD", "PROCESS_INPUT", "PROCESS_REVIEW", "ADOPT_PROCESS", "EXECUTION_RECORD" -> "processing";
            case "COLDCHAIN_REVIEW", "COLDCHAIN_NOT_STARTED", "MONITORING" -> "coldchain"; default -> "overview";
        };
    }
}
