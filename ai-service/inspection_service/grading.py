from __future__ import annotations

from .schemas import DefectBurden

RULE_VERSION = 'a12-dev-grade-v1'
GRADE_BASIS = 'defect_burden_level'
INITIAL_RULE_VERSION = 'visual-initial-v5.1'

_GRADE_BY_BURDEN = {
    'low': 'B',
    'moderate': 'C',
    'high': 'D',
}

def initial_grade(burden: DefectBurden) -> str:
    """A provisional appearance grade derived from the detector, not a learned grade classifier."""
    return {'none_observed': 'A', 'low': 'B', 'moderate': 'C', 'high': 'REJECT'}[burden.burden_level]


def suggest_grade(burden: DefectBurden) -> tuple[str | None, str]:
    """Map A11 development burden to an explainable Demo grade suggestion.

    An empty target-detection result never implies grade A. The current detector
    only covers the configured target defect classes, so no-target observations
    remain subject to human review.
    """
    if burden.burden_level == 'none_observed':
        return None, 'withheld_no_target_observed'
    return _GRADE_BY_BURDEN[burden.burden_level], 'development_rule_applied'
