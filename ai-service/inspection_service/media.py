import hashlib
from io import BytesIO
import json
from pathlib import Path
import shutil
import warnings
from PIL import Image, ImageDraw, ImageFont, ImageOps, UnidentifiedImageError

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
                if normalized.mode == 'RGBA' or 'transparency' in normalized.info:
                    rgba = normalized.convert('RGBA')
                    white = Image.new('RGBA', rgba.size, (255, 255, 255, 255))
                    image = Image.alpha_composite(white, rgba).convert('RGB')
                else:
                    image = normalized.convert('RGB')
                image.info.clear()
        return image, fmt
    except InvalidImage:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError,
            Image.DecompressionBombWarning, Image.DecompressionBombError):
        raise InvalidImage('INVALID_IMAGE') from None

def detection_label(detection, candidate_index: int) -> str:
    """Use the same one-based candidate ordinal as the web UI, NOT class_id."""
    names = {'ssda_class_0': 'Scratch', 'ssda_class_1': 'Pest damage'}
    name = names.get(detection.class_name, f'class {detection.class_id}')
    return f'#{candidate_index} {name} {detection.confidence:.1%}'

def annotate(image, detections):
    result = image.copy()
    draw = ImageDraw.Draw(result)
    size = max(11, min(36, round(max(image.size) / 65)))
    try:
        font = ImageFont.load_default(size=size)
    except TypeError:  # Compatible with older installed Pillow; no bundled font dependency.
        font = ImageFont.load_default()
    colors = {'ssda_class_0': (255, 212, 91), 'ssda_class_1': (101, 219, 223)}
    for index, detection in enumerate(detections, start=1):
        color = colors.get(detection.class_name, (255, 150, 70))
        x1, y1, x2, y2 = detection.bbox_xyxy
        draw.rectangle((x1, y1, x2, y2), outline=color, width=max(2, image.width // 400))
        text = detection_label(detection, index)
        left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
        width, height = right - left + 8, bottom - top + 6
        # Keep the label inside the original image; never resize the evidence image.
        x = max(0, min(x1, image.width - width))
        y = max(0, min(y1 - height, image.height - height))
        draw.rectangle((x, y, min(image.width - 1, x + width), min(image.height - 1, y + height)), fill=(19, 45, 35))
        draw.text((x + 4 - left, y + 3 - top), text, font=font, fill=color)
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
