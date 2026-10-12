from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator

class ClassDefinition(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: int = Field(ge=0)
    name: str = Field(min_length=1, max_length=100)
    label: str = Field(min_length=1, max_length=100)

class ModelManifest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    task: Literal['apple_surface_defect_detection']
    model_version: str = Field(min_length=1, max_length=100)
    weights: str = Field(min_length=1)
    weights_sha256: str = Field(pattern=r'^[a-f0-9]{64}$')
    classes: list[ClassDefinition] = Field(min_length=1, max_length=100)
    confidence_threshold: float = Field(default=0.25, gt=0, lt=1, allow_inf_nan=False)
    iou_threshold: float = Field(default=0.7, gt=0, lt=1, allow_inf_nan=False)
    image_size: int = Field(default=640, ge=32, le=2048, multiple_of=32)
    training_data_source: str = Field(min_length=1)
    evaluation_status: Literal['not_evaluated', 'evaluated'] = 'not_evaluated'
    evaluation_report: str | None = None

    @model_validator(mode='after')
    def validate_classes(self):
        if sorted(c.id for c in self.classes) != list(range(len(self.classes))):
            raise ValueError('Class IDs must be unique and contiguous from zero.')
        if len({c.name for c in self.classes}) != len(self.classes):
            raise ValueError('Class names must be unique.')
        if self.evaluation_status == 'evaluated' and not self.evaluation_report:
            raise ValueError('An evaluation report reference is required.')
        return self

class Detection(BaseModel):
    model_config = ConfigDict(extra='forbid')
    class_id: int = Field(ge=0)
    class_name: str
    class_label: str
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    bbox_xyxy: tuple[float, float, float, float]

BurdenLevel = Literal['none_observed', 'low', 'moderate', 'high']
BurdenEscalationFlag = Literal[
    'detection_count_ge_4',
    'scratch_large_box_ge_0_025',
    'pest_damage_large_box_ge_0_0045',
]

class DefectClassSummary(BaseModel):
    model_config = ConfigDict(extra='forbid')
    class_id: int = Field(ge=0)
    class_name: str = Field(min_length=1, max_length=100)
    class_label: str = Field(min_length=1, max_length=100)
    count: int = Field(ge=1, le=300)
    max_bbox_area_ratio_image: float = Field(ge=0, le=1, allow_inf_nan=False)

class DefectBurden(BaseModel):
    model_config = ConfigDict(extra='forbid')
    rule_version: Literal['a11-dev-burden-v1'] = 'a11-dev-burden-v1'
    burden_level: BurdenLevel
    detection_count: int = Field(ge=0, le=300)
    union_bbox_area_ratio_image: float = Field(ge=0, le=1, allow_inf_nan=False)
    max_bbox_area_ratio_image: float = Field(ge=0, le=1, allow_inf_nan=False)
    escalation_flags: list[BurdenEscalationFlag] = Field(default_factory=list, max_length=3)
    denominator: Literal['full_image_area'] = 'full_image_area'
    class_summary: list[DefectClassSummary] = Field(default_factory=list, max_length=100)

    @model_validator(mode='after')
    def validate_burden(self):
        if len(set(self.escalation_flags)) != len(self.escalation_flags):
            raise ValueError('Escalation flags must be unique.')
        if len({c.class_id for c in self.class_summary}) != len(self.class_summary):
            raise ValueError('Burden class IDs must be unique.')
        if sum(c.count for c in self.class_summary) != self.detection_count:
            raise ValueError('Burden class counts must equal detection_count.')
        if self.max_bbox_area_ratio_image > self.union_bbox_area_ratio_image + 1e-9:
            raise ValueError('Max bbox ratio cannot exceed union bbox ratio.')
        if self.detection_count == 0:
            if (self.burden_level != 'none_observed'
                or self.union_bbox_area_ratio_image != 0
                or self.max_bbox_area_ratio_image != 0
                or self.escalation_flags or self.class_summary):
                raise ValueError('Empty detections require an empty none_observed burden.')
        elif self.burden_level == 'none_observed':
            raise ValueError('Positive detections cannot use none_observed burden.')
        return self

class ImageMetadata(BaseModel):
    width: int
    height: int
    source_sha256: str
    normalized_pixels_sha256: str
    coordinate_system: Literal['exif_transposed_pixels_xyxy'] = 'exif_transposed_pixels_xyxy'

class ArtifactLinks(BaseModel):
    source: str
    input: str
    result: str
    record: str

class PredictionResponse(BaseModel):
    schema_version: Literal['zhijian.ai.inspection.v0.1'] = 'zhijian.ai.inspection.v0.1'
    prediction_id: str
    batch_id: int
    batch_code: str | None = None
    model_version: str
    weights_sha256: str
    evaluation_status: Literal['not_evaluated', 'evaluated']
    executed_at: str
    inference_ms: float
    confidence_threshold: float
    iou_threshold: float
    image: ImageMetadata
    detections: list[Detection]
    defect_burden: DefectBurden
    observation: Literal['target_defect_detected', 'no_target_defect_detected']
    suggested_grade: Literal['B', 'C', 'D'] | None = None
    grade_status: Literal['development_rule_applied', 'withheld_no_target_observed']
    grade_rule_version: Literal['a12-dev-grade-v1'] = 'a12-dev-grade-v1'
    grade_basis: Literal['defect_burden_level'] = 'defect_burden_level'
    requires_human_review: Literal[True] = True

    @model_validator(mode='after')
    def validate_grade_suggestion(self):
        expected = {'low': 'B', 'moderate': 'C', 'high': 'D'}
        if self.defect_burden.burden_level == 'none_observed':
            if self.suggested_grade is not None or self.grade_status != 'withheld_no_target_observed':
                raise ValueError('No-target observations must withhold automatic grade suggestion.')
        else:
            if (self.suggested_grade != expected[self.defect_burden.burden_level]
                or self.grade_status != 'development_rule_applied'):
                raise ValueError('Suggested grade must match the A12 development rule.')
        return self
    artifacts: ArtifactLinks
    warnings: list[str]
