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
    observation: Literal['target_defect_detected', 'no_target_defect_detected']
    suggested_grade: None = None
    grade_status: Literal['grading_rule_not_configured'] = 'grading_rule_not_configured'
    requires_human_review: Literal[True] = True
    artifacts: ArtifactLinks
    warnings: list[str]
