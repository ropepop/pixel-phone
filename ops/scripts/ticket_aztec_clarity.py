#!/usr/bin/env python3
"""Measure simple black/white clarity metrics for ticket Aztec evidence PNGs."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def parse_crop(value: str) -> tuple[int, int, int, int]:
  parts = [int(part) for part in value.split(",")]
  if len(parts) != 4:
    raise argparse.ArgumentTypeError("crop must be x,y,width,height")
  x, y, width, height = parts
  if width <= 0 or height <= 0:
    raise argparse.ArgumentTypeError("crop width and height must be positive")
  return x, y, width, height


def load_image(path: Path):
  try:
    from PIL import Image
  except ImportError as error:
    raise SystemExit(
      "Pillow is required for PNG analysis. Install pillow or run inside the bundled workspace runtime."
    ) from error
  return Image.open(path).convert("RGB")


def percentile(values: list[int], fraction: float) -> int:
  if not values:
    return 0
  index = round((len(values) - 1) * fraction)
  return values[max(0, min(index, len(values) - 1))]


def analyze(path: Path, crop: tuple[int, int, int, int] | None, module_grid: int) -> dict[str, object]:
  image = load_image(path)
  width, height = image.size
  if crop is None:
    box = (0, 0, width, height)
  else:
    x, y, crop_width, crop_height = crop
    box = (x, y, min(width, x + crop_width), min(height, y + crop_height))
  cropped = image.crop(box)
  luminance: list[int] = []
  pixel_iterable = cropped.get_flattened_data() if hasattr(cropped, "get_flattened_data") else cropped.getdata()
  for red, green, blue in pixel_iterable:
    luminance.append(round(0.2126 * red + 0.7152 * green + 0.0722 * blue))
  luminance.sort()
  total = len(luminance)
  black = sum(1 for value in luminance if value <= 48)
  white = sum(1 for value in luminance if value >= 224)
  mid_gray = sum(1 for value in luminance if 64 <= value <= 192)
  p05 = percentile(luminance, 0.05)
  p50 = percentile(luminance, 0.50)
  p95 = percentile(luminance, 0.95)
  return {
    "image": str(path),
    "imageWidth": width,
    "imageHeight": height,
    "crop": {
      "x": box[0],
      "y": box[1],
      "width": box[2] - box[0],
      "height": box[3] - box[1],
    },
    "pixels": total,
    "blackPixelPercent": round((black / total) * 100, 3) if total else 0,
    "whitePixelPercent": round((white / total) * 100, 3) if total else 0,
    "midGrayContaminationPercent": round((mid_gray / total) * 100, 3) if total else 0,
    "lumaP05": p05,
    "lumaP50": p50,
    "lumaP95": p95,
    "contrastP95MinusP05": p95 - p05,
    **module_quality(cropped, module_grid),
  }


def module_quality(image, grid: int = 41) -> dict[str, object]:
  """Sample module-center blocks so gray carryover is visible in the report.

  This does not try to decode the Aztec payload. It measures whether the places
  that should look like clean black or white modules are instead stuck in a
  gray in-between state.
  """
  width, height = image.size
  if width <= 0 or height <= 0:
    return {
      "moduleGrid": grid,
      "moduleSamples": 0,
      "ambiguousModuleCenterPercent": 0,
      "edgeSoftnessPercent": 0,
    }
  pixels = image.load()
  samples: list[int] = []
  soft_edges = 0
  comparisons = 0
  for row in range(grid):
    y0 = int(row * height / grid)
    y1 = int((row + 1) * height / grid)
    if y1 <= y0:
      continue
    for column in range(grid):
      x0 = int(column * width / grid)
      x1 = int((column + 1) * width / grid)
      if x1 <= x0:
        continue
      center_x0 = x0 + max(0, (x1 - x0) // 4)
      center_x1 = x1 - max(0, (x1 - x0) // 4)
      center_y0 = y0 + max(0, (y1 - y0) // 4)
      center_y1 = y1 - max(0, (y1 - y0) // 4)
      values: list[int] = []
      for y in range(center_y0, max(center_y0 + 1, center_y1)):
        for x in range(center_x0, max(center_x0 + 1, center_x1)):
          red, green, blue = pixels[min(width - 1, x), min(height - 1, y)]
          values.append(round(0.2126 * red + 0.7152 * green + 0.0722 * blue))
      if not values:
        continue
      values.sort()
      samples.append(percentile(values, 0.50))
      if column + 1 < grid:
        next_x = min(width - 1, int((column + 1) * width / grid))
        edge_values: list[int] = []
        for y in range(y0, y1):
          red, green, blue = pixels[next_x, min(height - 1, y)]
          edge_values.append(round(0.2126 * red + 0.7152 * green + 0.0722 * blue))
        if edge_values:
          comparisons += 1
          edge_values.sort()
          if 64 <= percentile(edge_values, 0.50) <= 192:
            soft_edges += 1
  ambiguous = sum(1 for value in samples if 64 <= value <= 192)
  return {
    "moduleGrid": grid,
    "moduleSamples": len(samples),
    "ambiguousModuleCenterPercent": round((ambiguous / len(samples)) * 100, 3) if samples else 0,
    "edgeSoftnessPercent": round((soft_edges / comparisons) * 100, 3) if comparisons else 0,
  }


def main() -> int:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("image", type=Path)
  parser.add_argument("--crop", type=parse_crop, help="Optional x,y,width,height crop around the Aztec code.")
  parser.add_argument("--module-grid", type=int, default=41, help="Approximate Aztec module grid used for center sampling.")
  parser.add_argument("--pretty", action="store_true")
  args = parser.parse_args()
  result = analyze(args.image, args.crop, max(1, args.module_grid))
  json.dump(result, sys.stdout, indent=2 if args.pretty else None, sort_keys=True)
  sys.stdout.write("\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
