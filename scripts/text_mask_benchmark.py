#!/usr/bin/env python3
"""Benchmark text-only enhancement masks on manga CBZ pages.

The script intentionally keeps every source archive read-only. Generated masks,
reports, and contact sheets are written below ``--output``.

Dependencies:
    numpy, pillow, opencv-python, onnxruntime, psutil
"""

from __future__ import annotations

import argparse
import csv
import heapq
import itertools
import json
import math
import statistics
import time
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Iterable
from zipfile import ZipFile

import cv2
import numpy as np
import onnxruntime as ort
import psutil
from PIL import Image, ImageDraw, ImageFont


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


@dataclass(frozen=True)
class Page:
    chapter: str
    index: int
    name: str
    image: np.ndarray

    @property
    def key(self) -> str:
        return f"{self.chapter}__{self.index:03d}"


@dataclass(frozen=True)
class MaskResult:
    probability: np.ndarray
    mask: np.ndarray
    elapsed_ms: float
    peak_rss_mb: float


class OnnxProbabilityDetector:
    def __init__(self, model_path: Path, family: str, max_side: int = 1024) -> None:
        self.family = family
        self.max_side = max_side
        options = ort.SessionOptions()
        options.intra_op_num_threads = max(1, min(4, psutil.cpu_count(logical=False) or 1))
        options.inter_op_num_threads = 1
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(
            str(model_path),
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        self.input_name = self.session.get_inputs()[0].name

    def __call__(self, image: np.ndarray) -> MaskResult:
        before_rss = psutil.Process().memory_info().rss
        if self.family == "ctd":
            tensor, crop_size = self._prepare_ctd(image)
        elif self.family == "paddle":
            tensor, crop_size = self._prepare_paddle(image)
        else:
            raise ValueError(f"Unknown model family: {self.family}")

        started = time.perf_counter()
        outputs = self.session.run(None, {self.input_name: tensor})
        elapsed_ms = (time.perf_counter() - started) * 1000
        if self.family == "ctd":
            names = [item.name for item in self.session.get_outputs()]
            output_map = dict(zip(names, outputs, strict=True))
            raw = output_map.get("seg", outputs[1])[0, 0]
            crop_width, crop_height = crop_size
            raw = raw[:crop_height, :crop_width]
        else:
            raw = outputs[0][0, 0]

        height, width = image.shape[:2]
        probability = cv2.resize(raw, (width, height), interpolation=cv2.INTER_LINEAR)
        probability = np.clip(probability.astype(np.float32), 0.0, 1.0)
        mask = effective_ink_mask(image, probability, self.family)
        after_rss = psutil.Process().memory_info().rss
        return MaskResult(
            probability=probability,
            mask=mask,
            elapsed_ms=elapsed_ms,
            peak_rss_mb=max(before_rss, after_rss) / 1024 / 1024,
        )

    def _prepare_ctd(self, image: np.ndarray) -> tuple[np.ndarray, tuple[int, int]]:
        height, width = image.shape[:2]
        ratio = min(self.max_side / height, self.max_side / width)
        resized_width = max(1, int(round(width * ratio)))
        resized_height = max(1, int(round(height * ratio)))
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR)
        padded = np.zeros((self.max_side, self.max_side, 3), dtype=np.uint8)
        padded[:resized_height, :resized_width] = resized
        tensor = padded.transpose(2, 0, 1)[None].astype(np.float32) / 255.0
        return tensor, (resized_width, resized_height)

    def _prepare_paddle(self, image: np.ndarray) -> tuple[np.ndarray, tuple[int, int]]:
        height, width = image.shape[:2]
        ratio = min(1.0, self.max_side / max(height, width))
        resized_height = max(32, int(round(height * ratio / 32.0)) * 32)
        resized_width = max(32, int(round(width * ratio / 32.0)) * 32)
        resized = cv2.resize(image, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR)
        normalized = resized.astype(np.float32) / 255.0
        normalized -= np.array([0.485, 0.456, 0.406], dtype=np.float32)
        normalized /= np.array([0.229, 0.224, 0.225], dtype=np.float32)
        tensor = normalized.transpose(2, 0, 1)[None].astype(np.float32)
        return tensor, (resized_width, resized_height)


class GroupedInkDetector:
    """Model-free baseline using connected ink components and text-like grouping."""

    family = "classical"

    def __call__(self, image: np.ndarray) -> MaskResult:
        before_rss = psutil.Process().memory_info().rss
        started = time.perf_counter()
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        height, width = gray.shape
        binary = cv2.adaptiveThreshold(
            gray,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY_INV,
            31,
            12,
        )
        count, labels, stats, centroids = cv2.connectedComponentsWithStats(binary, 8, cv2.CV_32S)
        candidates: list[int] = []
        page_area = height * width
        for label in range(1, count):
            x, y, component_width, component_height, area = stats[label]
            box_area = component_width * component_height
            aspect = max(component_width / max(component_height, 1), component_height / max(component_width, 1))
            if not (2 <= area <= page_area * 0.006):
                continue
            if not (2 <= component_width <= width * 0.14 and 2 <= component_height <= height * 0.12):
                continue
            if box_area <= 0 or area / box_area < 0.025 or aspect > 9.0:
                continue
            candidates.append(label)

        cell_size = max(8, width // 40)
        grid: dict[tuple[int, int], list[int]] = {}
        for label in candidates:
            center_x, center_y = centroids[label]
            key = (int(center_x // cell_size), int(center_y // cell_size))
            grid.setdefault(key, []).append(label)

        accepted: set[int] = set()
        for label in candidates:
            x, y, component_width, component_height, _ = stats[label]
            center_x, center_y = centroids[label]
            size = max(component_width, component_height)
            neighbors = 0
            cell_x, cell_y = int(center_x // cell_size), int(center_y // cell_size)
            nearby: set[int] = set()
            for offset in range(-6, 7):
                for cross in (-1, 0, 1):
                    nearby.update(grid.get((cell_x + offset, cell_y + cross), ()))
                    nearby.update(grid.get((cell_x + cross, cell_y + offset), ()))
            for other in nearby:
                if other == label:
                    continue
                ox, oy, other_width, other_height, _ = stats[other]
                other_center_x, other_center_y = centroids[other]
                other_size = max(other_width, other_height)
                ratio = size / max(other_size, 1)
                if not 0.38 <= ratio <= 2.65:
                    continue
                horizontal = (
                    abs(center_y - other_center_y) <= 1.25 * max(component_height, other_height)
                    and abs(center_x - other_center_x) <= 6.0 * max(size, other_size)
                )
                vertical = (
                    abs(center_x - other_center_x) <= 1.25 * max(component_width, other_width)
                    and abs(center_y - other_center_y) <= 6.0 * max(size, other_size)
                )
                if horizontal or vertical:
                    accepted.add(label)
                    accepted.add(other)
                    neighbors += 1
            if neighbors >= 2:
                accepted.add(label)

        probability = np.zeros_like(gray, dtype=np.float32)
        for label in accepted:
            probability[labels == label] = 1.0
        probability = cv2.GaussianBlur(probability, (3, 3), 0.55)
        mask = effective_ink_mask(image, probability, self.family)
        elapsed_ms = (time.perf_counter() - started) * 1000
        after_rss = psutil.Process().memory_info().rss
        return MaskResult(
            probability=probability,
            mask=mask,
            elapsed_ms=elapsed_ms,
            peak_rss_mb=max(before_rss, after_rss) / 1024 / 1024,
        )


class WhiteBackgroundInkDetector:
    """Enhance any thin dark stroke whose immediate background is bright.

    This deliberately does not try to decide whether a stroke is text. A
    morphological black-hat response finds small ink-like structures while
    rejecting broad continuous shadows. That matches the actual reader goal:
    faint dialogue should deepen, and similar fine lines on white are harmless.
    """

    family = "white_ink"

    def __init__(self, multiscale: bool) -> None:
        self.multiscale = multiscale

    def __call__(self, image: np.ndarray) -> MaskResult:
        before_rss = psutil.Process().memory_info().rss
        started = time.perf_counter()
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        base = max(3, int(round(image.shape[1] / 160.0)))
        if base % 2 == 0:
            base += 1
        kernel_sizes = [max(3, base - 2), base, base + 4] if self.multiscale else [base]
        responses: list[np.ndarray] = []
        backgrounds: list[np.ndarray] = []
        for size in kernel_sizes:
            if size % 2 == 0:
                size += 1
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size))
            background = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel)
            response = cv2.subtract(background, gray).astype(np.float32) / 255.0
            responses.append(response)
            backgrounds.append(background.astype(np.float32) / 255.0)
        line_response = np.maximum.reduce(responses)
        local_background = np.maximum.reduce(backgrounds)
        background_gate = smoothstep(0.80, 0.965, local_background)
        line_gate = smoothstep(0.012, 0.20, line_response)
        source_darkness = 1.0 - gray.astype(np.float32) / 255.0
        ink_gate = smoothstep(0.018, 0.34, source_darkness)
        probability = np.clip(background_gate * line_gate, 0.0, 1.0).astype(np.float32)
        mask = np.clip(probability * ink_gate, 0.0, 1.0).astype(np.float32)
        elapsed_ms = (time.perf_counter() - started) * 1000
        after_rss = psutil.Process().memory_info().rss
        return MaskResult(
            probability=probability,
            mask=mask,
            elapsed_ms=elapsed_ms,
            peak_rss_mb=max(before_rss, after_rss) / 1024 / 1024,
        )


def smoothstep(low: float, high: float, values: np.ndarray) -> np.ndarray:
    scaled = np.clip((values - low) / max(high - low, 1e-6), 0.0, 1.0)
    return scaled * scaled * (3.0 - 2.0 * scaled)


def effective_ink_mask(image: np.ndarray, probability: np.ndarray, family: str) -> np.ndarray:
    """Turn detector confidence into a soft, glyph-only darkening mask.

    Multiplying by source darkness and local contrast makes untouched space and
    uniform gray backgrounds inside a detected text polygon remain unchanged.
    It also keeps antialiased glyph edges soft and avoids dilation artifacts
    around speech-balloon outlines.
    """

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
    darkness = 1.0 - gray
    ink = smoothstep(0.025, 0.30, darkness)
    local_background = cv2.boxFilter(
        gray,
        -1,
        (13, 13),
        normalize=True,
        borderType=cv2.BORDER_REPLICATE,
    )
    detail = smoothstep(0.025, 0.16, np.maximum(0.0, local_background - gray))
    if family == "ctd":
        confidence = smoothstep(0.10, 0.52, probability)
    elif family == "paddle":
        confidence = smoothstep(0.08, 0.40, probability)
    else:
        confidence = smoothstep(0.08, 0.60, probability)
    return np.clip(confidence * ink * detail, 0.0, 1.0).astype(np.float32)


def iter_cbz_pages(corpus: Path) -> Iterable[Page]:
    for archive_path in sorted(corpus.glob("*.cbz")):
        with ZipFile(archive_path) as archive:
            names = [
                name
                for name in archive.namelist()
                if Path(name).suffix.lower() in IMAGE_EXTENSIONS and not name.endswith("/")
            ]
            names.sort(key=natural_key)
            for index, name in enumerate(names, start=1):
                data = np.frombuffer(archive.read(name), dtype=np.uint8)
                image = cv2.imdecode(data, cv2.IMREAD_COLOR)
                if image is None:
                    raise RuntimeError(f"Unable to decode {archive_path.name}:{name}")
                yield Page(
                    chapter=archive_path.stem,
                    index=index,
                    name=name,
                    image=image,
                )


def count_cbz_pages(corpus: Path) -> int:
    count = 0
    for archive_path in sorted(corpus.glob("*.cbz")):
        with ZipFile(archive_path) as archive:
            count += sum(
                1
                for name in archive.namelist()
                if Path(name).suffix.lower() in IMAGE_EXTENSIONS and not name.endswith("/")
            )
    return count


def natural_key(value: str) -> list[object]:
    import re

    return [int(part) if part.isdigit() else part.casefold() for part in re.split(r"(\d+)", value)]


def create_synthetic_cases(font_path: Path) -> list[tuple[str, np.ndarray, np.ndarray]]:
    scale = 2
    size = (800 * scale, 1129 * scale)
    cases: list[tuple[str, np.ndarray, np.ndarray]] = []
    for variant in range(3):
        canvas = Image.new("L", size, 248)
        truth = Image.new("L", size, 0)
        draw = ImageDraw.Draw(canvas)
        truth_draw = ImageDraw.Draw(truth)
        rng = np.random.default_rng(1200 + variant)

        # Panel borders and manga-like textures are deliberate hard negatives.
        draw.rectangle((35 * scale, 35 * scale, 765 * scale, 1094 * scale), outline=25, width=3 * scale)
        draw.line((35 * scale, 385 * scale, 765 * scale, 385 * scale), fill=35, width=3 * scale)
        for _ in range(150 + variant * 80):
            x = int(rng.integers(45, 750)) * scale
            y = int(rng.integers(55, 1080)) * scale
            length = int(rng.integers(8, 55)) * scale
            shade = int(rng.integers(80, 205))
            draw.line((x, y, min(size[0] - 1, x + length), max(0, y - length // 3)), fill=shade, width=scale)
        for y in range(420 * scale, 690 * scale, 10 * scale):
            for x in range(50 * scale, 340 * scale, 10 * scale):
                if ((x + y) // (10 * scale)) % 2 == 0:
                    draw.ellipse((x, y, x + 2 * scale, y + 2 * scale), fill=80 + variant * 20)

        draw.ellipse((430 * scale, 105 * scale, 735 * scale, 365 * scale), fill=252, outline=25, width=3 * scale)
        draw.rounded_rectangle(
            (80 * scale, 725 * scale, 375 * scale, 1030 * scale),
            radius=55 * scale,
            fill=250,
            outline=30,
            width=3 * scale,
        )

        regular = ImageFont.truetype(str(font_path), (22 - variant * 2) * scale)
        small = ImageFont.truetype(str(font_path), (15 - variant) * scale)
        bold = ImageFont.truetype(str(font_path), 34 * scale)
        draw_vertical_text(draw, truth_draw, "前阵子不是有野猪冲进小镇了吗", (650, 145), regular, 155 + 18 * variant, scale)
        draw_vertical_text(draw, truth_draw, "糟了我怎么就这样毫无防备", (315, 770), regular, 175, scale)
        draw_text(draw, truth_draw, "再多落一些……", (450, 335), small, 185, scale)
        draw_text(draw, truth_draw, "ガサッ", (95, 470), bold, 25, scale)
        draw_text(draw, truth_draw, "很小的浅灰说明文字", (420, 705 + variant * 40), small, 205, scale)

        downsampled = canvas.resize((800, 1129), Image.Resampling.LANCZOS)
        downsampled_truth = truth.resize((800, 1129), Image.Resampling.LANCZOS)
        bgr = cv2.cvtColor(np.asarray(downsampled), cv2.COLOR_GRAY2BGR)
        truth_mask = np.asarray(downsampled_truth).astype(np.float32) / 255.0
        cases.append((f"synthetic_{variant + 1}", bgr, truth_mask))
    return cases


def draw_text(
    draw: ImageDraw.ImageDraw,
    truth_draw: ImageDraw.ImageDraw,
    text: str,
    position: tuple[int, int],
    font: ImageFont.FreeTypeFont,
    shade: int,
    scale: int,
) -> None:
    xy = (position[0] * scale, position[1] * scale)
    draw.text(xy, text, font=font, fill=shade)
    truth_draw.text(xy, text, font=font, fill=255)


def draw_vertical_text(
    draw: ImageDraw.ImageDraw,
    truth_draw: ImageDraw.ImageDraw,
    text: str,
    position: tuple[int, int],
    font: ImageFont.FreeTypeFont,
    shade: int,
    scale: int,
) -> None:
    x = position[0] * scale
    y = position[1] * scale
    step = int(font.size * 1.08)
    for character in text:
        draw.text((x, y), character, font=font, fill=shade)
        truth_draw.text((x, y), character, font=font, fill=255)
        y += step


def tolerant_metrics(prediction: np.ndarray, truth: np.ndarray) -> dict[str, float]:
    predicted = (prediction >= 0.16).astype(np.uint8)
    expected = (truth >= 0.10).astype(np.uint8)
    kernel = np.ones((3, 3), dtype=np.uint8)
    expanded_expected = cv2.dilate(expected, kernel, iterations=1)
    expanded_predicted = cv2.dilate(predicted, kernel, iterations=1)
    predicted_count = int(predicted.sum())
    expected_count = int(expected.sum())
    precision = float((predicted * expanded_expected).sum()) / max(predicted_count, 1)
    recall = float((expected * expanded_predicted).sum()) / max(expected_count, 1)
    f1 = 2 * precision * recall / max(precision + recall, 1e-9)
    false_positive_fraction = float((predicted * (1 - expanded_expected)).sum()) / predicted.size
    return {
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "false_positive_fraction": false_positive_fraction,
    }


def agreement_metrics(candidate: np.ndarray, reference: np.ndarray, gray: np.ndarray) -> dict[str, float]:
    candidate_binary = (candidate >= 0.16).astype(np.uint8)
    reference_binary = ((reference >= 0.16) & (gray >= 65) & (gray <= 235)).astype(np.uint8)
    kernel = np.ones((3, 3), dtype=np.uint8)
    expanded_reference = cv2.dilate(reference_binary, kernel, iterations=1)
    expanded_candidate = cv2.dilate(candidate_binary, kernel, iterations=1)
    precision = float((candidate_binary * expanded_reference).sum()) / max(int(candidate_binary.sum()), 1)
    recall = float((reference_binary * expanded_candidate).sum()) / max(int(reference_binary.sum()), 1)
    return {"reference_precision": precision, "reference_recall": recall}


def preservation_metrics(mask: np.ndarray, gray: np.ndarray) -> dict[str, float]:
    base = max(3, int(round(gray.shape[1] / 160.0)))
    if base % 2 == 0:
        base += 1
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (base + 4, base + 4))
    local_background = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel)
    selected = mask >= 0.16
    nonbright = selected & (local_background < 210)
    bright = selected & (local_background >= 225)
    selected_count = max(int(selected.sum()), 1)
    return {
        "nonbright_page_fraction": float(nonbright.mean()),
        "bright_share_of_mask": float(bright.sum()) / selected_count,
    }


def enhance_preview(image: np.ndarray, mask: np.ndarray, strength: float = 0.72) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY).astype(np.float32)
    enhanced = gray * (1.0 - strength * mask)
    return np.clip(enhanced, 0, 255).astype(np.uint8)


def imwrite(path: Path, image: np.ndarray, params: list[int] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    success, encoded = cv2.imencode(path.suffix, image, params or [])
    if not success:
        raise RuntimeError(f"Unable to encode image: {path}")
    encoded.tofile(path)


def thumbnail(image: np.ndarray, width: int = 180) -> np.ndarray:
    height, source_width = image.shape[:2]
    target_height = max(1, int(round(height * width / source_width)))
    return cv2.resize(image, (width, target_height), interpolation=cv2.INTER_AREA)


def disagreement_overlay(image: np.ndarray, reference: np.ndarray, candidate: np.ndarray) -> np.ndarray:
    base = cv2.cvtColor(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), cv2.COLOR_GRAY2BGR).astype(np.float32)
    ref_only = np.clip(reference - candidate, 0.0, 1.0)[..., None]
    candidate_only = np.clip(candidate - reference, 0.0, 1.0)[..., None]
    agreement = np.minimum(reference, candidate)[..., None]
    base = base * (1.0 - 0.52 * np.maximum.reduce([ref_only, candidate_only, agreement]))
    base += ref_only * np.array([230, 180, 20], dtype=np.float32)
    base += candidate_only * np.array([200, 20, 220], dtype=np.float32)
    base += agreement * np.array([20, 210, 210], dtype=np.float32)
    return np.clip(base, 0, 255).astype(np.uint8)


def write_contact_sheets(
    output: Path,
    overlays: dict[str, list[tuple[int, np.ndarray]]],
    thumb_width: int = 180,
) -> None:
    sheets = output / "contact-sheets"
    sheets.mkdir(parents=True, exist_ok=True)
    for chapter, items in overlays.items():
        columns = 6
        thumbs: list[np.ndarray] = []
        for index, image in sorted(items):
            thumb = image if image.shape[1] == thumb_width else thumbnail(image, thumb_width)
            cv2.putText(thumb, str(index), (5, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 2, cv2.LINE_AA)
            cv2.putText(thumb, str(index), (5, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
            thumbs.append(thumb)
        cell_height = max(item.shape[0] for item in thumbs)
        rows = math.ceil(len(thumbs) / columns)
        sheet = np.full((rows * cell_height, columns * thumb_width, 3), 245, dtype=np.uint8)
        for position, thumb in enumerate(thumbs):
            row, column = divmod(position, columns)
            sheet[row * cell_height : row * cell_height + thumb.shape[0], column * thumb_width : (column + 1) * thumb_width] = thumb
        imwrite(sheets / f"{chapter}.jpg", sheet, [cv2.IMWRITE_JPEG_QUALITY, 90])


def summarize(rows: list[dict[str, object]]) -> dict[str, object]:
    by_algorithm: dict[str, list[dict[str, object]]] = {}
    for row in rows:
        by_algorithm.setdefault(str(row["algorithm"]), []).append(row)
    result: dict[str, object] = {}
    for algorithm, algorithm_rows in by_algorithm.items():
        elapsed = [float(row["elapsed_ms"]) for row in algorithm_rows]
        coverages = [float(row["mask_coverage"]) for row in algorithm_rows]
        precisions = [float(row["reference_precision"]) for row in algorithm_rows if row["reference_precision"] != ""]
        recalls = [float(row["reference_recall"]) for row in algorithm_rows if row["reference_recall"] != ""]
        result[algorithm] = {
            "pages": len(algorithm_rows),
            "mean_ms": statistics.fmean(elapsed),
            "median_ms": statistics.median(elapsed),
            "p95_ms": sorted(elapsed)[max(0, math.ceil(len(elapsed) * 0.95) - 1)],
            "mean_mask_coverage": statistics.fmean(coverages),
            "mean_reference_precision": statistics.fmean(precisions) if precisions else None,
            "mean_reference_recall": statistics.fmean(recalls) if recalls else None,
            "mean_nonbright_page_fraction": statistics.fmean(
                float(row["nonbright_page_fraction"]) for row in algorithm_rows
            ),
            "mean_bright_share_of_mask": statistics.fmean(
                float(row["bright_share_of_mask"]) for row in algorithm_rows
            ),
            "peak_rss_mb": max(float(row["peak_rss_mb"]) for row in algorithm_rows),
        }
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ctd-model", type=Path, required=True)
    parser.add_argument("--ppv5-model", type=Path, required=True)
    parser.add_argument("--ppv6-model", type=Path, required=True)
    parser.add_argument("--font", type=Path, default=Path(r"C:\Windows\Fonts\msyh.ttc"))
    parser.add_argument("--limit-pages", type=int)
    parser.add_argument("--extra-image", action="append", type=Path, default=[])
    parser.add_argument(
        "--include-grouped",
        action="store_true",
        help="Include the intentionally slow connected-component grouping baseline.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    detectors: dict[str, object] = {
        "ctd": OnnxProbabilityDetector(args.ctd_model, "ctd"),
        "ppocr_v5_mobile": OnnxProbabilityDetector(args.ppv5_model, "paddle"),
        "ppocr_v6_tiny": OnnxProbabilityDetector(args.ppv6_model, "paddle"),
        "white_ink_fast": WhiteBackgroundInkDetector(multiscale=False),
        "white_ink_multiscale": WhiteBackgroundInkDetector(multiscale=True),
    }
    if args.include_grouped:
        detectors["classical_grouped_ink"] = GroupedInkDetector()

    synthetic_rows: list[dict[str, object]] = []
    synthetic_dir = args.output / "synthetic"
    synthetic_dir.mkdir(parents=True, exist_ok=True)
    for case_name, image, truth in create_synthetic_cases(args.font):
        imwrite(synthetic_dir / f"{case_name}-source.png", image)
        imwrite(synthetic_dir / f"{case_name}-truth.png", np.clip(truth * 255, 0, 255).astype(np.uint8))
        for algorithm, detector in detectors.items():
            result = detector(image)
            metrics = tolerant_metrics(result.mask, truth)
            preservation = preservation_metrics(result.mask, cv2.cvtColor(image, cv2.COLOR_BGR2GRAY))
            synthetic_rows.append(
                {
                    "case": case_name,
                    "algorithm": algorithm,
                    **metrics,
                    **preservation,
                    "elapsed_ms": result.elapsed_ms,
                }
            )
            imwrite(
                synthetic_dir / f"{case_name}-{algorithm}-mask.png",
                np.clip(result.mask * 255, 0, 255).astype(np.uint8),
            )
            imwrite(
                synthetic_dir / f"{case_name}-{algorithm}-enhanced.png",
                enhance_preview(image, result.mask),
            )

    extra_pages: list[Page] = []
    for extra_path in args.extra_image:
        image = cv2.imread(str(extra_path), cv2.IMREAD_COLOR)
        if image is None:
            raise RuntimeError(f"Unable to decode extra image: {extra_path}")
        extra_pages.append(Page("extra", len(extra_pages) + 1, extra_path.name, image))
    corpus_page_count = count_cbz_pages(args.corpus)
    total_pages = corpus_page_count + len(extra_pages)
    pages: Iterable[Page] = itertools.chain(iter_cbz_pages(args.corpus), extra_pages)
    if args.limit_pages is not None:
        total_pages = min(total_pages, args.limit_pages)
        pages = itertools.islice(pages, args.limit_pages)

    rows: list[dict[str, object]] = []
    overlays: dict[str, list[tuple[int, np.ndarray]]] = {}
    disagreements: list[tuple[float, int, Page, dict[str, np.ndarray]]] = []
    for position, page in enumerate(pages, start=1):
        results = {name: detector(page.image) for name, detector in detectors.items()}
        reference = results["ctd"].mask
        gray = cv2.cvtColor(page.image, cv2.COLOR_BGR2GRAY)
        for algorithm, result in results.items():
            agreement = (
                {"reference_precision": 1.0, "reference_recall": 1.0}
                if algorithm == "ctd"
                else agreement_metrics(result.mask, reference, gray)
            )
            preservation = preservation_metrics(result.mask, gray)
            rows.append(
                {
                    "page": page.key,
                    "source_name": page.name,
                    "algorithm": algorithm,
                    "elapsed_ms": result.elapsed_ms,
                    "peak_rss_mb": result.peak_rss_mb,
                    "mask_coverage": float((result.mask >= 0.16).mean()),
                    "mean_mask": float(result.mask.mean()),
                    **agreement,
                    **preservation,
                }
            )
        candidate = results["ppocr_v6_tiny"].mask
        agreement = agreement_metrics(candidate, reference, gray)
        score = 1.0 - agreement["reference_recall"]
        overlay = disagreement_overlay(page.image, reference, candidate)
        overlays.setdefault(f"{page.chapter}--ppocr-v6", []).append((page.index, thumbnail(overlay)))
        white_overlay = disagreement_overlay(page.image, reference, results["white_ink_multiscale"].mask)
        overlays.setdefault(f"{page.chapter}--white-ink", []).append((page.index, thumbnail(white_overlay)))
        snapshot = {"source": page.image.copy()}
        snapshot.update(
            {
                algorithm: np.clip(result.mask * 255, 0, 255).astype(np.uint8)
                for algorithm, result in results.items()
            }
        )
        heapq.heappush(disagreements, (score, position, page, snapshot))
        if len(disagreements) > 20:
            heapq.heappop(disagreements)
        print(f"[{position:03d}/{total_pages:03d}] {page.key}", flush=True)

    with (args.output / "real-pages.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    with (args.output / "synthetic.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(synthetic_rows[0].keys()))
        writer.writeheader()
        writer.writerows(synthetic_rows)

    write_contact_sheets(args.output, overlays)
    disagreement_dir = args.output / "highest-disagreement"
    disagreement_dir.mkdir(parents=True, exist_ok=True)
    for rank, (_, _, page, snapshot) in enumerate(sorted(disagreements, reverse=True, key=lambda item: item[0]), start=1):
        source = snapshot["source"]
        overlay = disagreement_overlay(source, snapshot["ctd"] / 255.0, snapshot["ppocr_v6_tiny"] / 255.0)
        imwrite(disagreement_dir / f"{rank:02d}-{page.key}-overlay.jpg", overlay, [cv2.IMWRITE_JPEG_QUALITY, 94])
        for algorithm in detectors:
            imwrite(
                disagreement_dir / f"{rank:02d}-{page.key}-{algorithm}.jpg",
                enhance_preview(source, snapshot[algorithm] / 255.0),
                [cv2.IMWRITE_JPEG_QUALITY, 94],
            )

    report = {
        "corpus_pages": min(corpus_page_count, total_pages),
        "extra_pages": max(0, total_pages - corpus_page_count),
        "models": {
            "ctd_bytes": args.ctd_model.stat().st_size,
            "ppocr_v5_bytes": args.ppv5_model.stat().st_size,
            "ppocr_v6_bytes": args.ppv6_model.stat().st_size,
        },
        "real_pages": summarize(rows),
        "synthetic": synthetic_rows,
        "legend": "Contact sheets: cyan=CTD only, magenta=PP-OCRv6 only, yellow=agreement.",
    }
    (args.output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
