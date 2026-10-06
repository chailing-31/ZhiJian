"""Internal HTTP API. Bind to loopback; no database writes, no grading, no fake outputs."""
from contextlib import asynccontextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
import time
from typing import Annotated, Literal
from uuid import UUID, uuid4
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from .config import Settings
from .engine import YoloEngine, validate_detections
from .burden import calculate_defect_burden
from .media import FORMATS, InvalidImage, annotate, decode_image, image_metadata, store_prediction
from .schemas import ArtifactLinks, PredictionResponse

class RequestLimit:
    """Bound request bytes before multipart parsing/spooling. Single-image local prototype."""
    def __init__(self, app, limit):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope.get('method') != 'POST':
            return await self.app(scope, receive, send)
        messages, size = [], 0
        for key, value in scope.get('headers', []):
            if key.lower() == b'content-length':
                try:
                    if int(value) > self.limit:
                        await self.reject(scope, receive, send)
                        return
                except ValueError:
                    pass
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            size += len(message.get('body', b''))
            if size > self.limit:
                await self.reject(scope, receive, send)
                return
            messages.append(message)
            if not message.get('more_body', False):
                break
        index = 0
        async def replay():
            nonlocal index
            if index < len(messages):
                message = messages[index]
                index += 1
                return message
            return await receive()
        await self.app(scope, replay, send)

    async def reject(self, scope, receive, send):
        await JSONResponse(status_code=413, content={'detail': {
            'code': 'REQUEST_TOO_LARGE', 'message': 'Request exceeds service size limit.'}})(scope, receive, send)

def create_app(settings=None, engine=None):
    settings = settings or Settings.from_env()
    engine = engine or YoloEngine(settings)

    @asynccontextmanager
    async def lifespan(app):
        # One worker for the prototype. Each worker would otherwise load its own model.
        engine.initialize()
        yield

    app = FastAPI(title='ZhiJian Internal Inspection API', version='0.1.0', lifespan=lifespan)
    app.state.engine = engine
    app.add_middleware(RequestLimit, limit=settings.max_request_bytes)

    @app.get('/health')
    def health():
        return {'status': 'ok', 'service': 'zhijian-ai', 'model_ready': engine.ready,
                'model_status': 'ready' if engine.ready else engine.error_code,
                'model_version': engine.manifest.model_version if engine.ready else None}

    @app.get('/ready')
    def ready():
        state = health()
        return JSONResponse(state, status_code=200 if engine.ready else 503)

    @app.post('/ai/inspection/predict', response_model=PredictionResponse)
    def predict(
        image: Annotated[UploadFile, File(description='JPEG, PNG or WebP; one non-animated image, <=10 MiB')],
        batch_id: Annotated[int, Form(gt=0, description='Actual numeric ID assigned by Spring Boot/MySQL')],
        batch_code: Annotated[str | None, Form(pattern=r'^[A-Za-z0-9-]{1,64}$')] = None,
    ):
        try:
            data = image.file.read(settings.max_upload_bytes + 1)
        finally:
            image.file.close()
        if len(data) > settings.max_upload_bytes:
            raise HTTPException(413, detail={'code': 'IMAGE_TOO_LARGE'})
        try:
            normalized, fmt = decode_image(data, settings.max_pixels)
        except InvalidImage as error:
            raise HTTPException(422, detail={'code': str(error)}) from None
        if not engine.ready:
            raise HTTPException(503, detail={'code': 'MODEL_NOT_READY',
                'reason': engine.error_code, 'message': 'No usable apple model is loaded. No prediction was generated.'})
        start = time.perf_counter()
        try:
            detections = engine.predict(normalized)
            validate_detections(detections, normalized.width, normalized.height)
        except Exception:
            raise HTTPException(503, detail={'code': 'INFERENCE_FAILED',
                'message': 'Inference failed; no result was saved.'}) from None
        elapsed_ms = (time.perf_counter() - start) * 1000
        prediction_id = str(uuid4())
        prefix = '/internal/artifacts/' + prediction_id
        record = PredictionResponse(
            prediction_id=prediction_id, batch_id=batch_id, batch_code=batch_code,
            model_version=engine.manifest.model_version,
            weights_sha256=engine.manifest.weights_sha256,
            evaluation_status=engine.manifest.evaluation_status,
            executed_at=datetime.now(timezone(timedelta(hours=8))).isoformat(), inference_ms=round(elapsed_ms, 3),
            confidence_threshold=engine.manifest.confidence_threshold,
            iou_threshold=engine.manifest.iou_threshold,
            image=image_metadata(data, normalized), detections=detections,
            defect_burden=calculate_defect_burden(detections, normalized.width, normalized.height),
            observation='target_defect_detected' if detections else 'no_target_defect_detected',
            artifacts=ArtifactLinks(**{k: prefix + '/' + k for k in ('source', 'input', 'result', 'record')}),
            warnings=['仅为当前图像、当前模型类别与阈值下的观察，不代表食品安全或整批合格结论。',
                      'A11 缺陷负担以候选框相对整张图像面积计算，不是真实苹果表面损伤率或质量等级。',
                      '未检出目标缺陷不等于正常；等级规则未配置，需人工复核。']
        )
        if engine.manifest.evaluation_status == 'not_evaluated':
            record.warnings.append('该模型清单标记为尚未独立评测。')
        try:
            store_prediction(settings.storage_root, prediction_id, data, fmt, normalized,
                             annotate(normalized, detections), record.model_dump(mode='json'))
        except OSError:
            raise HTTPException(507, detail={'code': 'ARTIFACT_STORAGE_FAILED',
                'message': 'Could not persist evidence. Check private data disk permissions and free space.'}) from None
        return record

    @app.get('/internal/artifacts/{prediction_id}/{variant}')
    def artifact(prediction_id: UUID, variant: Literal['source', 'input', 'result', 'record']):
        directory = settings.storage_root / str(prediction_id)
        if not (directory / 'record.json').is_file():
            raise HTTPException(404, detail={'code': 'ARTIFACT_NOT_FOUND'})
        if variant == 'source':
            found = [(directory / ('source.' + ext), mime) for ext, mime in FORMATS.values()
                     if (directory / ('source.' + ext)).is_file()]
            if not found:
                raise HTTPException(404, detail={'code': 'ARTIFACT_NOT_FOUND'})
            path, mime = found[0]
        else:
            path = directory / ('record.json' if variant == 'record' else variant + '.png')
            mime = 'application/json' if variant == 'record' else 'image/png'
        if not path.is_file():
            raise HTTPException(404, detail={'code': 'ARTIFACT_NOT_FOUND'})
        return FileResponse(path, media_type=mime,
                            headers={'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})
    return app
