from io import BytesIO
from types import SimpleNamespace
import importlib.util
from pathlib import Path
import pytest
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('e2_media_under_test', ROOT/'inspection_service/media.py')
media = importlib.util.module_from_spec(spec); spec.loader.exec_module(media)

def detection(class_id=0, name='ssda_class_0', box=(10,20,70,80)):
    return SimpleNamespace(class_id=class_id,class_name=name,class_label='擦伤',confidence=.812,bbox_xyxy=box)

def png(image):
    b=BytesIO();image.save(b,format='PNG');return b.getvalue()

def test_candidate_ordinal_independent_of_class():
    assert media.detection_label(detection(),2).startswith('#2 Scratch')
    assert media.detection_label(detection(1,'ssda_class_1'),1).startswith('#1 Pest damage')

def test_annotation_preserves_size_and_source_pixels():
    image=Image.new('RGB',(200,120),'white'); before=image.tobytes()
    result=media.annotate(image,[detection()])
    assert result.size==image.size and image.tobytes()==before and result.tobytes()!=before

def test_empty_result_does_not_fabricate_boxes():
    image=Image.new('RGB',(20,30),'white')
    assert media.annotate(image,[]).tobytes()==image.tobytes()

def test_alpha_matches_white_background():
    data=png(Image.new('RGBA',(2,2),(255,0,0,0)))
    image,_=media.decode_image(data,100)
    assert image.mode=='RGB' and image.getpixel((0,0))==(255,255,255)

def test_exif_is_applied_exactly_once():
    raw=Image.new('RGB',(30,20)); exif=Image.Exif();exif[274]=6
    b=BytesIO();raw.save(b,'JPEG',exif=exif)
    image,_=media.decode_image(b.getvalue(),1000)
    assert image.size==(20,30) and not image.getexif()

def test_source_hash_and_normalized_hash_are_separate():
    image=Image.new('RGB',(4,5),'white');data=png(image)
    meta=media.image_metadata(data,image)
    assert meta['source_sha256']!=meta['normalized_pixels_sha256']
    assert meta['width']==4 and meta['height']==5

@pytest.mark.parametrize('data',[b'',b'not an image'])
def test_invalid_image_rejected(data):
    with pytest.raises(media.InvalidImage):media.decode_image(data,100)

def test_pixel_limit_enforced():
    with pytest.raises(media.InvalidImage):media.decode_image(png(Image.new('RGB',(11,10))),100)

@pytest.mark.parametrize('size',[(1,1),(10,10),(200,100)])
def test_edge_label_does_not_resize_or_crash(size):
    image=Image.new('RGB',size)
    d=detection(box=(0,0,size[0],size[1]))
    assert media.annotate(image,[d]).size==size
