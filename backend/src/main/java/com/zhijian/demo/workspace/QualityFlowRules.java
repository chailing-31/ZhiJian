package com.zhijian.demo.workspace;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure workflow policy. A material business grade is not a detector class or safety certificate. */
public final class QualityFlowRules {
    public static final String VERSION = "quality-flow-v3-20261013";
    public static final Set<String> GRADES = Set.of("A", "B", "C", "REJECT");
    private QualityFlowRules() {}
    public static List<Map<String,String>> grades() {
        return List.of(
            Map.of("code","A","label","优选外观","definition","人工核对所检图像，未确认可见目标缺陷；仍须核对完整业务标准。","route","可进入分选、包装及加工条件评估"),
            Map.of("code","B","label","一般外观","definition","轻微外观缺陷，经人工核定可进入后续分选。","route","分选后核对 B 规则与工艺条件"),
            Map.of("code","C","label","用途待评估","definition","缺陷或用途适用性需要负责人进一步确认。","route","专项分选、用途评估；不自动提供工艺参数"),
            Map.of("code","REJECT","label","人工拒收","definition","负责人依据业务检查明确判退；不是由大框或无框自动产生。","route","暂停流转，隔离登记并人工处置")
        );
    }
    public static String nextState(boolean reviewed, String grade, boolean linked, String adviceStatus,
                                   boolean adopted, long readings, long unresolved, long activeEpisodes) {
        if (!reviewed || !GRADES.contains(grade == null ? "" : grade)) return "REVIEW_REQUIRED";
        if (unresolved > 0 || activeEpisodes > 0) return "COLDCHAIN_ATTENTION";
        if ("REJECT".equals(grade)) return "REJECTED_HOLD";
        if (!linked) return "PROCESSING_SOURCE_REQUIRED";
        if ("C".equals(grade) || !"suggested".equals(adviceStatus)) return "PROCESSING_REVIEW_REQUIRED";
        if (!adopted) return "ADOPTION_REQUIRED";
        if (readings < 1) return "COLDCHAIN_RECORDS_REQUIRED";
        return "RECORDS_LINKED";
    }
}
