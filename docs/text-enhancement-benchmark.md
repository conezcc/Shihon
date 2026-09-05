# Text enhancement benchmark

Shihon's text enhancement deliberately detects text-like ink rather than
recognizing text or trying to reconstruct speech balloons. The detector output
is multiplied by source-pixel darkness and local contrast to produce a soft,
strength-independent mask. The local contrast gate excludes uniform gray and
halftone backgrounds inside a detected region. No text content is extracted,
stored, or transmitted.

## Corpus and method

- 168 images from nine downloaded CBZ chapters of *山剧*.
- Two user-supplied failure screenshots, for 170 real pages in total.
- Three synthetic manga pages with known text masks and hard negatives such as
  panel borders, halftone, hatching, foliage, and motion lines.
- Comic Text Detector (CTD) was used only as a high-quality offline reference.
  It is not distributed because of its GPL license and mobile cost.
- `scripts/text_mask_benchmark.py` keeps the CBZ files read-only and writes all
  generated reports and previews below the requested output directory.

Representative command:

```shell
python scripts/text_mask_benchmark.py \
  --corpus <cbz-directory> \
  --output <output-directory> \
  --ctd-model <comic-text-detector.onnx> \
  --ppv5-model <ppocr-v5-mobile-det.onnx> \
  --ppv6-model <ppocr-v6-tiny-det.onnx>
```

## Candidate comparison

Times below are desktop CPU inference times and are useful for relative rather
than absolute Android performance. Agreement precision and recall use CTD's
light-ink mask as the reference.

| Candidate | Model | Mean/page | Precision | Recall | Selected coverage | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| CTD | 94.67 MB | 620.4 ms | reference | reference | 2.16% | Accurate but far too heavy |
| PP-OCRv5 mobile, 1024 | 4.83 MB | 49.9 ms | 77.65% | 72.18% | 1.56% | Best preservation/recall |
| PP-OCRv6 tiny, 1024 | 1.78 MB | 28.7 ms | 77.12% | 67.80% | 1.55% | More misses and foliage false positives |
| White-background ink, fast | none | 21.9 ms | 13.49% | 71.93% | 9.96% | Darkened detailed artwork |
| White-background ink, multiscale | none | 30.3 ms | 13.61% | 90.51% | 14.57% | Darkened artwork heavily |

The classical connected-component grouping baseline took 3.8–7.8 seconds per
page on dense pages and was rejected before the full run.

The low precision of the two morphology candidates is not simply a text-label
problem: visual review showed that they substantially darkened foliage,
hatching, and motion lines. PP-OCRv5 consistently darkened the faint dialogue
while leaving those regions essentially unchanged. Its glyph-only soft mask
also avoided the black jagged outline previously introduced by balloon masks.

## Mobile input size and memory

PP-OCRv5 was swept at a lower confidence window of 0.04–0.25 across all 170
pages:

| Long side | Mean/page | Precision | Recall | Non-bright selected pixels |
| ---: | ---: | ---: | ---: | ---: |
| 640 | 19.4 ms | 75.93% | 70.84% | 0.347% |
| 768 | 28.9 ms | 76.18% | 73.10% | 0.315% |
| 1024 | 53.6 ms | 77.44% | 73.12% | 0.265% |

The 768-pixel input retains effectively all of the 1024-pixel recall while
cutting inference time by about 46%. It also preserved the thinnest text in the
user screenshots better than 640, so 768 is the shipped setting.

ONNX Runtime's CPU arena retained approximately 118 MB more process memory in
the isolated 768-pixel test after repeated inference. Disabling the arena kept
post-inference RSS near the session baseline (about 90 MB total in that test)
and cost roughly 10–15% inference time. Shihon disables the arena and limits
the detector to two CPU threads.

## Shipped behavior

- The original page remains tiled; it is never replaced by a full-page enhanced
  bitmap.
- Detection runs in the background and is serialized to bound peak memory.
- A small ALPHA_8 mask is cached by processed-image hash. Revisiting a page does
  not rerun inference.
- Slider changes only alter overlay opacity and never reload the page.
- At strength zero, Shihon does not copy the source, read the mask cache, load
  ONNX Runtime, run inference, or draw an overlay.
- The distributed PP-OCRv5 model is Apache-2.0 licensed. Its source and checksum
  are recorded next to the asset in `app/src/main/assets/text_enhancement`.

## Local-contrast regression

The shipped mask postprocessor was rerun after adding the local-contrast gate
against all 168 CBZ pages and three user-supplied failure screenshots. At the
same 768-pixel detector input, selected mask coverage fell from 1.741% to
1.131% per page. Selection in areas below the minimum local-contrast threshold
fell from 0.532% to zero, while 99.96% of high-confidence, high-contrast text
strokes remained selected. Visual review of the 12 pages with the largest mask
reduction found that the removed pixels were uniform cover art, halftone, or
gray backgrounds rather than faint dialogue.
