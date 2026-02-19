#!/usr/bin/env python3
"""
Extract 2-bar segments from a tab PDF as images for Rokid AR display.

Strategy: detect horizontal staff lines (rows with many dark pixels spanning
the page width), group into 4-line systems, add padding for chord names above,
split each system at horizontal center to get 2-bar halves, then invert +
threshold for waveguide display.

Usage:
    pip install pymupdf pillow numpy
    python extract_tab_images.py <input.pdf> <output_dir>
"""

import sys
import os
import json
import fitz  # PyMuPDF
import numpy as np
from PIL import Image


RENDER_DPI = 300
# Minimum dark pixels per row to count as a staff line
# (Staff lines span ~3400px at 300 DPI; chord diagram fret lines only ~1100)
MIN_STAFF_DARK_PIXELS = 1500
# How many px above top staff line to include (chord names, measure numbers)
CHORD_PADDING_ABOVE = 190
# How many px below bottom staff line to include (note stems, etc.)
PADDING_BELOW = 120
# Waveguide inversion threshold
INVERT_THRESHOLD = 60
# Target display width (Rokid AR1 = 480px portrait)
TARGET_WIDTH = 480


def render_pages(pdf_path):
    doc = fitz.open(pdf_path)
    pages = []
    for i, page in enumerate(doc):
        mat = fitz.Matrix(RENDER_DPI / 72, RENDER_DPI / 72)
        pix = page.get_pixmap(matrix=mat, alpha=False)
        img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        pages.append(img)
        print(f"  Page {i+1}: {pix.width}x{pix.height}px")
    doc.close()
    return pages


def find_staff_lines(gray_arr):
    """Find rows containing staff lines by total dark pixel count."""
    row_sums = np.sum(gray_arr < 128, axis=1)
    return np.where(row_sums > MIN_STAFF_DARK_PIXELS)[0]


def cluster_lines(staff_rows, max_thickness=8):
    """Cluster consecutive staff rows into individual line positions."""
    if len(staff_rows) == 0:
        return []
    lines = []
    start = staff_rows[0]
    prev = staff_rows[0]
    for y in staff_rows[1:]:
        if y - prev > 1:
            if prev - start <= max_thickness:
                lines.append((start + prev) // 2)
            start = y
        prev = y
    if prev - start <= max_thickness:
        lines.append((start + prev) // 2)
    return lines


def group_into_systems(lines, max_intra_gap=100, expected_count=4):
    """Group staff lines into systems of 4 (ukulele = 4 strings)."""
    if not lines:
        return []
    groups = []
    current = [lines[0]]
    for i in range(1, len(lines)):
        if lines[i] - lines[i-1] < max_intra_gap:
            current.append(lines[i])
        else:
            if len(current) >= expected_count:
                groups.append(current[:expected_count])
            current = [lines[i]]
    if len(current) >= expected_count:
        groups.append(current[:expected_count])
    return groups


def find_content_bounds(gray_arr, top, bottom, margin=10):
    """Find horizontal content extent within a region."""
    region = gray_arr[top:bottom, :]
    col_ink = np.mean(region < 200, axis=0)
    cols = np.where(col_ink > 0.005)[0]
    if len(cols) == 0:
        return margin, gray_arr.shape[1] - margin
    return max(0, cols[0] - margin), min(gray_arr.shape[1], cols[-1] + margin)


def process_for_waveguide(img, threshold=INVERT_THRESHOLD):
    """Invert + threshold + dilate + resize for waveguide display.

    1. Invert (dark ink -> white, paper -> black)
    2. Threshold to pure black/white
    3. Dilate white pixels by 1px (thicken lines so they survive downscale)
    4. Resize to target display width
    5. Re-threshold to keep pure black/white
    """
    arr = np.array(img.convert("L"))
    # Invert + threshold
    inverted = 255 - arr
    inverted[inverted < threshold] = 0
    inverted[inverted >= threshold] = 255

    # Dilate: expand white pixels by 1px in all directions
    # This thickens 1px lines to 3px so they survive 3.5x downscale
    dilated = inverted.copy()
    dilated[1:, :] = np.maximum(dilated[1:, :], inverted[:-1, :])   # shift down
    dilated[:-1, :] = np.maximum(dilated[:-1, :], inverted[1:, :])  # shift up
    dilated[:, 1:] = np.maximum(dilated[:, 1:], inverted[:, :-1])   # shift right
    dilated[:, :-1] = np.maximum(dilated[:, :-1], inverted[:, 1:])  # shift left

    # Resize to target width
    pil_img = Image.fromarray(dilated, mode="L")
    scale = TARGET_WIDTH / pil_img.width
    new_h = int(pil_img.height * scale)
    resized = pil_img.resize((TARGET_WIDTH, new_h), Image.LANCZOS)

    # Re-threshold after resize (LANCZOS creates anti-aliased gray values)
    final = np.array(resized)
    final[final < 80] = 0
    final[final >= 80] = 255
    return Image.fromarray(final, mode="L")


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <input.pdf> <output_dir>")
        sys.exit(1)

    pdf_path = sys.argv[1]
    output_dir = sys.argv[2]
    os.makedirs(output_dir, exist_ok=True)

    print(f"Rendering PDF at {RENDER_DPI} DPI...")
    pages = render_pages(pdf_path)

    segment_num = 0

    for page_idx, page_img in enumerate(pages):
        gray = np.array(page_img.convert("L"))
        h, w = gray.shape

        staff_rows = find_staff_lines(gray)
        lines = cluster_lines(staff_rows)
        systems = group_into_systems(lines)
        print(f"\n  Page {page_idx+1}: {len(staff_rows)} staff rows -> "
              f"{len(lines)} lines -> {len(systems)} systems")

        for sys_idx, sys_lines in enumerate(systems):
            top_line = sys_lines[0]
            bot_line = sys_lines[-1]
            region_top = max(0, top_line - CHORD_PADDING_ABOVE)
            region_bot = min(h, bot_line + PADDING_BELOW)

            left, right = find_content_bounds(gray, region_top, region_bot)
            mid_x = (left + right) // 2

            print(f"    System {sys_idx+1}: staff y={sys_lines[0]}-{sys_lines[-1]}, "
                  f"region y={region_top}-{region_bot}, x={left}-{right}")

            pad_top = max(0, region_top - 5)
            pad_bot = min(h, region_bot + 5)

            # Left half (first 2 bars)
            segment_num += 1
            crop = page_img.crop((left, pad_top, mid_x, pad_bot))
            proc = process_for_waveguide(crop)
            fname = f"tab_{segment_num:03d}.png"
            proc.save(os.path.join(output_dir, fname))
            print(f"      {fname}: {proc.width}x{proc.height}")

            # Right half (next 2 bars)
            segment_num += 1
            crop = page_img.crop((mid_x, pad_top, right, pad_bot))
            proc = process_for_waveguide(crop)
            fname = f"tab_{segment_num:03d}.png"
            proc.save(os.path.join(output_dir, fname))
            print(f"      {fname}: {proc.width}x{proc.height}")

    meta = {"total_segments": segment_num, "dpi": RENDER_DPI}
    with open(os.path.join(output_dir, "meta.json"), "w") as f:
        json.dump(meta, f, indent=2)

    print(f"\nDone! {segment_num} segments saved to {output_dir}/")
    print(f"Display as pairs (top/bottom): {segment_num // 2} screens")


if __name__ == "__main__":
    main()
