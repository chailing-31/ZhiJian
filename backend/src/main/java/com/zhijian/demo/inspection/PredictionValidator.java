private static void validateGrade(JsonNode p, JsonNode burden) {
    if ("quality-routing-v3".equals(p.path("grade_rule_version").asText())) {
        if (!"target_defect_burden_screening".equals(p.path("grade_basis").asText())
            || !p.has("suggested_grade")) {
            fail();
        }

        String level = burden.path("burden_level").asText();

        String expected = switch (level) {
            case "low" -> "B";
            case "moderate" -> "C";
            case "high", "none_observed" -> null;
            default -> throw new IllegalArgumentException("Invalid burden");
        };

        String status = "none_observed".equals(level)
            ? "withheld_no_target_observed"
            : "high".equals(level)
                ? "withheld_requires_review"
                : "development_rule_applied";

        if (!status.equals(p.path("grade_status").asText())
            || (expected == null
                ? !p.get("suggested_grade").isNull()
                : !expected.equals(p.get("suggested_grade").asText()))) {
            fail();
        }

        return;
    }

    // Preserve validation of genuine historic A12 responses; never rewrite historic grades.
    if (!"a12-dev-grade-v1".equals(p.path("grade_rule_version").asText())
        || !"defect_burden_level".equals(p.path("grade_basis").asText())
        || !p.has("suggested_grade")) {
        fail();
    }

    String level = burden.path("burden_level").asText();

    if ("none_observed".equals(level)) {
        if (!p.get("suggested_grade").isNull()
            || !"withheld_no_target_observed".equals(p.path("grade_status").asText())) {
            fail();
        }
        return;
    }

    String expectedGrade = switch (level) {
        case "low" -> "B";
        case "moderate" -> "C";
        case "high" -> "D";
        default -> throw new IllegalArgumentException("Invalid burden level");
    };

    if (!p.get("suggested_grade").isTextual()
        || !expectedGrade.equals(p.get("suggested_grade").asText())
        || !"development_rule_applied".equals(p.path("grade_status").asText())) {
        fail();
    }
}