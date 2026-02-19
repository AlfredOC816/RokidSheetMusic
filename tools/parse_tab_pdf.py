#!/usr/bin/env python3
"""
Parse a ukulele tab PDF into structured JSON for the Rokid Sheet Music app.

Uses PyMuPDF (fitz) to extract precise text positions from the PDF,
then groups characters into rows by y-coordinate, identifies tab systems
(chord row → measure number row → 4 string rows), and assigns fret numbers
to measures by x-position ranges.

Usage:
    pip install pymupdf
    python parse_tab_pdf.py <input.pdf> [output.json]

Output JSON structure:
{
  "title": "Song Name",
  "arranger": "...",
  "tuning": "Low G (GCEA)",
  "tempo": 70,
  "time": "4/4",
  "measures": [
    {
      "num": 1,
      "chord": "F",
      "strings": [
        [1, 1, 1, 0, 0, 0, 0],   // A string frets (top string)
        [1, 1, 1],                 // E string frets
        [0, 0, 0],                 // C string frets
        []                          // G string frets (bottom string)
      ]
    },
    ...
  ]
}
"""

import sys
import json
from collections import defaultdict

try:
    import fitz  # PyMuPDF
except ImportError:
    print("Error: PyMuPDF required. Install with: pip install pymupdf")
    sys.exit(1)


def extract_text_positions(pdf_path):
    """Extract all text characters with their positions from the PDF."""
    doc = fitz.open(pdf_path)
    chars = []
    for page_num in range(len(doc)):
        page = doc[page_num]
        blocks = page.get_text("dict")["blocks"]
        for block in blocks:
            if "lines" not in block:
                continue
            for line in block["lines"]:
                for span in line["spans"]:
                    text = span["text"].strip()
                    if not text:
                        continue
                    x = span["origin"][0]
                    y = span["origin"][1]
                    size = span["size"]
                    chars.append({
                        "text": text,
                        "x": round(x, 1),
                        "y": round(y, 1),
                        "size": round(size, 1),
                        "page": page_num
                    })
    doc.close()
    return chars


def group_by_rows(chars, y_tolerance=3.0):
    """Group characters into rows by y-coordinate proximity."""
    if not chars:
        return []

    sorted_chars = sorted(chars, key=lambda c: (c["page"], c["y"], c["x"]))
    rows = []
    current_row = [sorted_chars[0]]
    current_y = sorted_chars[0]["y"]
    current_page = sorted_chars[0]["page"]

    for c in sorted_chars[1:]:
        if c["page"] == current_page and abs(c["y"] - current_y) <= y_tolerance:
            current_row.append(c)
        else:
            rows.append(sorted(current_row, key=lambda c: c["x"]))
            current_row = [c]
            current_y = c["y"]
            current_page = c["page"]

    if current_row:
        rows.append(sorted(current_row, key=lambda c: c["x"]))

    return rows


def identify_chord_rows(rows):
    """Identify rows that contain chord names (larger font, musical notation)."""
    chord_chars = set("ABCDEFGabcdefgm#b0123456789susdimaugmaj/+°7")
    chord_rows = []
    for i, row in enumerate(rows):
        texts = [c["text"] for c in row]
        joined = " ".join(texts)
        # Chord rows typically have larger font and contain chord-like text
        avg_size = sum(c["size"] for c in row) / len(row)
        has_chord = any(
            len(t) >= 1 and t[0] in "ABCDEFG" and all(ch in chord_chars for ch in t)
            for t in texts
        )
        if has_chord and avg_size > 8:
            chord_rows.append(i)
    return chord_rows


def parse_tab_system(rows, chord_row_idx, all_rows):
    """Parse a tab system starting from a chord row.

    A system consists of:
    - Chord row (chord names above the tab)
    - Possibly a measure number row
    - 4 string rows (A, E, C, G for ukulele)
    """
    chord_row = all_rows[chord_row_idx]
    chords = []
    for c in chord_row:
        if c["text"][0] in "ABCDEFG":
            chords.append({"name": c["text"], "x": c["x"]})

    # Look for string rows below the chord row
    string_rows = []
    for offset in range(1, 8):
        idx = chord_row_idx + offset
        if idx >= len(all_rows):
            break
        row = all_rows[idx]
        # String rows contain mostly single digits (fret numbers)
        digit_count = sum(1 for c in row if c["text"].isdigit() or
                         (len(c["text"]) <= 2 and c["text"].replace("-", "").isdigit()))
        if digit_count > 0 and digit_count >= len(row) * 0.5:
            string_rows.append(row)
        if len(string_rows) >= 4:
            break

    return chords, string_rows


def assign_frets_to_measures(chords, string_rows, page_width=612):
    """Assign fret numbers from string rows to their respective measures
    based on x-position proximity to chord positions."""

    if not chords:
        return []

    # Define x-ranges for each measure based on chord positions
    measures = []
    for i, chord in enumerate(chords):
        x_start = chord["x"] - 5
        if i + 1 < len(chords):
            x_end = chords[i + 1]["x"] - 5
        else:
            x_end = page_width

        strings_data = []
        for row in string_rows:
            frets = []
            for c in row:
                if c["x"] >= x_start and c["x"] < x_end:
                    try:
                        frets.append(int(c["text"]))
                    except ValueError:
                        # Try individual digits for multi-char entries
                        for ch in c["text"]:
                            if ch.isdigit():
                                frets.append(int(ch))
            strings_data.append(frets)

        measures.append({
            "chord": chord["name"],
            "strings": strings_data
        })

    return measures


def parse_pdf(pdf_path):
    """Main parsing pipeline."""
    print(f"Extracting text from {pdf_path}...")
    chars = extract_text_positions(pdf_path)
    print(f"  Found {len(chars)} text elements")

    rows = group_by_rows(chars)
    print(f"  Grouped into {len(rows)} rows")

    # Debug: print all rows
    for i, row in enumerate(rows):
        texts = " ".join(c["text"] for c in row)
        avg_y = sum(c["y"] for c in row) / len(row)
        avg_size = sum(c["size"] for c in row) / len(row)
        print(f"  Row {i}: y={avg_y:.1f} size={avg_size:.1f} | {texts[:80]}")

    chord_rows = identify_chord_rows(rows)
    print(f"  Identified {len(chord_rows)} chord rows at indices: {chord_rows}")

    all_measures = []
    measure_num = 1

    for cr_idx in chord_rows:
        chords, string_rows = parse_tab_system(rows, cr_idx, rows)
        measures = assign_frets_to_measures(chords, string_rows)

        for m in measures:
            m["num"] = measure_num
            all_measures.append(m)
            measure_num += 1

    print(f"\nParsed {len(all_measures)} measures total")
    return all_measures


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <input.pdf> [output.json]")
        sys.exit(1)

    pdf_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else pdf_path.rsplit(".", 1)[0] + "_tab.json"

    measures = parse_pdf(pdf_path)

    # Build output JSON
    # You may want to edit these metadata fields for your specific tab
    result = {
        "title": "Untitled",
        "arranger": "Unknown",
        "tuning": "Low G (GCEA)",
        "tempo": 70,
        "time": "4/4",
        "measures": measures
    }

    with open(output_path, "w") as f:
        json.dump(result, f, indent=2)

    print(f"\nSaved to {output_path}")
    print("NOTE: Review the output and adjust metadata (title, arranger, tempo, etc.)")
    print("      The parser may need manual corrections for complex tabs.")


if __name__ == "__main__":
    main()
