from __future__ import annotations

from collections import defaultdict
from .schemas import DefectBurden, DefectClassSummary, Detection

RULE_VERSION = 'a11-dev-burden-v1'
LOW_TO_MODERATE = 0.006
MODERATE_TO_HIGH = 0.03
SCRATCH_LARGE = 0.025
PEST_LARGE = 0.0045

def _union_area(rects):
    if not rects:
        return 0.0
    xs = sorted({x for r in rects for x in (r[0], r[2])})
    total = 0.0
    for left, right in zip(xs, xs[1:]):
        if right <= left:
            continue
        intervals = sorted((y1, y2) for x1, y1, x2, y2 in rects if x1 < right and x2 > left)
        if not intervals:
            continue
        cur1, cur2 = intervals[0]
        covered = 0.0
        for y1, y2 in intervals[1:]:
            if y1 <= cur2:
                cur2 = max(cur2, y2)
            else:
                covered += cur2 - cur1
                cur1, cur2 = y1, y2
        covered += cur2 - cur1
        total += (right - left) * covered
    return total

def _base_level(union_ratio):
    if union_ratio < LOW_TO_MODERATE:
        return 'low'
    if union_ratio < MODERATE_TO_HIGH:
        return 'moderate'
    return 'high'

def _upgrade(level):
    return {'low': 'moderate', 'moderate': 'high', 'high': 'high'}[level]

def calculate_defect_burden(detections: list[Detection], width: int, height: int) -> DefectBurden:
    if width < 1 or height < 1:
        raise ValueError('Image dimensions must be positive.')
    if not detections:
        return DefectBurden(
            burden_level='none_observed',
            detection_count=0,
            union_bbox_area_ratio_image=0.0,
            max_bbox_area_ratio_image=0.0,
            escalation_flags=[],
            class_summary=[],
        )

    image_area = float(width * height)
    rects = []
    areas = []
    by_class = defaultdict(list)

    for detection in detections:
        x1, y1, x2, y2 = detection.bbox_xyxy
        rect = (float(x1), float(y1), float(x2), float(y2))
        rects.append(rect)
        ratio = ((x2 - x1) * (y2 - y1)) / image_area
        areas.append(ratio)
        by_class[(detection.class_id, detection.class_name, detection.class_label)].append(ratio)

    union_ratio = _union_area(rects) / image_area
    flags = []
    if len(detections) >= 4:
        flags.append('detection_count_ge_4')
    if any(d.class_name == 'ssda_class_0' and area >= SCRATCH_LARGE
           for d, area in zip(detections, areas, strict=True)):
        flags.append('scratch_large_box_ge_0_025')
    if any(d.class_name == 'ssda_class_1' and area >= PEST_LARGE
           for d, area in zip(detections, areas, strict=True)):
        flags.append('pest_damage_large_box_ge_0_0045')

    level = _base_level(union_ratio)
    if flags:
        level = _upgrade(level)

    summaries = [
        DefectClassSummary(
            class_id=class_id,
            class_name=class_name,
            class_label=class_label,
            count=len(values),
            max_bbox_area_ratio_image=max(values),
        )
        for (class_id, class_name, class_label), values in sorted(by_class.items())
    ]

    return DefectBurden(
        burden_level=level,
        detection_count=len(detections),
        union_bbox_area_ratio_image=union_ratio,
        max_bbox_area_ratio_image=max(areas),
        escalation_flags=flags,
        class_summary=summaries,
    )
