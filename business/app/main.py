import math
import os
from functools import lru_cache
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .anomaly_models import CombinedResult
from .models import ColdchainRequest, ColdchainResponse, ProcessRequest, ProcessResponse
from .rules import check_coldchain, process_advice


app = FastAPI(
    title="智检鲜达 B 模块", version="0.4.0",
    description="规则与冷链异常模型分别输出；Spring Boot 负责历史、落库和处置。当前模型仅经模拟验证。",
)


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, error: RequestValidationError):
    # Invalid inputs can contain non-finite floats, even in nested error inputs.
    # Preserve the standard detail list without emitting invalid JSON numbers.
    detail = jsonable_encoder(
        error.errors(),
        custom_encoder={float: lambda value: value if math.isfinite(value) else str(value)},
    )
    return JSONResponse(status_code=422, content={"detail": detail})


@app.get("/health")
def health():
    return {"status": "ok", "service": "business-rules", "version": "0.4.0"}


@app.post("/coldchain/check", response_model=ColdchainResponse)
def coldchain_check(request: ColdchainRequest):
    return check_coldchain(request)


@app.post("/business/process-advice", response_model=ProcessResponse)
def advice(request: ProcessRequest):
    return process_advice(request)


@lru_cache(maxsize=1)
def get_anomaly_detector():
    directory = os.environ.get("BUSINESS_ANOMALY_MODEL_DIR")
    if not directory:
        raise HTTPException(status_code=503, detail="未配置已训练的异常检测模型；规则基线仍可通过 /coldchain/check 调用")
    try:
        from .anomaly import AnomalyDetector
        return AnomalyDetector.load(Path(directory))
    except (ImportError, OSError, ValueError, KeyError) as error:
        raise HTTPException(status_code=503, detail="异常检测模型未就绪，请检查依赖、模型清单和版本") from error


@app.post("/coldchain/analyze", response_model=CombinedResult)
def analyze(request: ColdchainRequest):
    detector = get_anomaly_detector()
    try:
        model_result = detector.predict(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return CombinedResult(rule_result=check_coldchain(request), model_result=model_result)
