package com.zhijian.demo.inspection;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Pure domain validation; decisions never rewrite the model's detections. */
public final class ReviewRules {
    private ReviewRules() {}
    public record Candidate(Integer candidate_index, String decision, Integer duplicate_of, String note) {}
    public record Input(Integer expected_revision, String reviewer, String conclusion,
                        String remark, String final_grade, Boolean publish_summary,
                        List<Candidate> candidate_reviews) {}
    private static final Set<String> DECISIONS = Set.of("confirmed", "false_positive", "duplicate", "uncertain");
    public static Input validate(Input input, int count) {
        if (input == null || input.expected_revision() == null || input.expected_revision() < 0)
            throw bad("缺少有效的复核版本号，请刷新记录。");
        String reviewer = text(input.reviewer(), 50, true, "复核人");
        String remark = text(input.remark(), 4000, true, "复核说明");
        String grade = text(input.final_grade(), 20, false, "人工等级");
        if (!Set.of("target_confirmed", "no_target_confirmed", "needs_recheck").contains(
                input.conclusion() == null ? "" : input.conclusion())) throw bad("请选择有效的图像复核结论。");
        List<Candidate> candidates = input.candidate_reviews();
        if (candidates == null || candidates.size() != count) throw bad("请逐个标记全部候选框。");
        var byId = new HashMap<Integer, Candidate>();
        for (Candidate c : candidates) {
            if (c == null || c.candidate_index() == null || c.candidate_index() < 1 || c.candidate_index() > count
                    || byId.put(c.candidate_index(), c) != null) throw bad("候选编号缺失、重复或越界。");
            if (!DECISIONS.contains(c.decision() == null ? "" : c.decision())) throw bad("仍有未复核的候选框。");
            text(c.note(), 500, false, "候选备注");
            if (!"duplicate".equals(c.decision()) && c.duplicate_of() != null)
                throw bad("只有重复框才填写关联候选编号。");
        }
        int confirmed = 0, uncertain = 0;
        for (Candidate c : candidates) {
            if ("confirmed".equals(c.decision())) confirmed++;
            if ("uncertain".equals(c.decision())) uncertain++;
            if ("duplicate".equals(c.decision())) {
                Candidate target = byId.get(c.duplicate_of());
                if (target == null || c.candidate_index().equals(c.duplicate_of()) || !"confirmed".equals(target.decision()))
                    throw bad("重复框须指向另一个已确认的候选框。");
            }
        }
        if (uncertain > 0 && !"needs_recheck".equals(input.conclusion())) throw bad("存在不确定候选，结论必须为需再复核。");
        if ("target_confirmed".equals(input.conclusion()) && confirmed == 0) throw bad("没有已确认候选，不能提交已确认目标结论。");
        if ("no_target_confirmed".equals(input.conclusion()) && confirmed > 0) throw bad("仍有已确认候选，不能提交未确认目标结论。");
        return new Input(input.expected_revision(), reviewer, input.conclusion(), remark,
            grade, Boolean.TRUE.equals(input.publish_summary()), List.copyOf(candidates));
    }
    private static String text(String raw, int max, boolean required, String label) {
        String s = raw == null ? "" : raw.trim();
        if ((required && s.isEmpty()) || s.length() > max) throw bad(label + "为空或过长。");
        return s.isEmpty() ? null : s;
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
