import hashlib
from io import BytesIO
import json
from pathlib import Path
import shutil
import warnings
from PIL import Image, ImageDraw, ImageOps, UnidentifiedImageError

class InvalidImage(ValueError):
    pass

FORMATS = {'JPEG': ('jpg', 'image/jpeg'), 'PNG': ('png', 'image/png'), 'WEBP': ('webp', 'image/webp')}

def decode_image(data: bytes, max_pixels: int):
    if not data:
        raise InvalidImage('EMPTY_IMAGE')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as source:
                fmt = source.format
                if fmt not in FORMATS:
                    raise InvalidImage('IMAGE_FORMAT_UNSUPPORTED')
                if getattr(source, 'n_frames', 1) != 1:
                    raise InvalidImage('ANIMATED_IMAGE_UNSUPPORTED')
                if source.width * source.height > max_pixels:
                    raise InvalidImage('IMAGE_DIMENSIONS_TOO_LARGE')
                source.load()
                normalized = ImageOps.exif_transpose(source)
                # Match browser appearance for alpha images, instead of making transparent pixels black.
                if normalized.mode == 'RGBA' or 'transparency' in normalized.info:
                    rgba = normalized.convert('RGBA')
                    white = Image.new('RGBA', rgba.size, (255, 255, 255, 255))
                    image = Image.alpha_composite(white, rgba).convert('RGB')
                else:
                    image = normalized.convert('RGB')
                image.info.clear()  # Public/normalized artifacts must not retain EXIF/GPS metadata.
        return image, fmt
    except InvalidImage:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError,
            Image.DecompressionBombWarning, Image.DecompressionBombError):
        raise InvalidImage('INVALID_IMAGE') from None

def annotate(image, detections):
    result = image.copy()
    draw = ImageDraw.Draw(result)
    # Numeric legend prevents Chinese font dependencies; UI uses class_label from JSON.
    for d in detections:
        draw.rectangle(d.bbox_xyxy, outline=(229, 80, 49), width=max(2, image.width // 400))
        draw.text((d.bbox_xyxy[0], max(0, d.bbox_xyxy[1] - 13)),
                  f'#{d.class_id} {d.confidence:.2f}', fill=(229, 80, 49))
    return result

def image_metadata(data, image):
    pixels = f'RGB:{image.width}x{image.height}:'.encode('ascii') + image.tobytes()
    return dict(width=image.width, height=image.height,
                source_sha256=hashlib.sha256(data).hexdigest(),
                normalized_pixels_sha256=hashlib.sha256(pixels).hexdigest())

def store_prediction(root: Path, prediction_id, data, fmt, image, result, record):
    staging = root / ('.' + prediction_id + '.partial')
    final = root / prediction_id
    root.mkdir(parents=True, exist_ok=True)
    staging.mkdir(exist_ok=False)
    try:
        (staging / ('source.' + FORMATS[fmt][0])).write_bytes(data)
        image.save(staging / 'input.png', format='PNG')
        result.save(staging / 'result.png', format='PNG')
        (staging / 'record.json').write_text(
            json.dumps(record, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
        staging.rename(final)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
