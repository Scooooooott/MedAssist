import { describe, expect, it } from "vitest";

import { buildHighlightSegments } from "./highlight";

describe("buildHighlightSegments", () => {
  it("aligns quoted spans despite case and whitespace differences", () => {
    const segments = buildHighlightSegments(
      "The Guideline\nrecommends   Aspirin for eligible adults.",
      "guideline recommends aspirin"
    );

    expect(segments.some((segment) => segment.highlighted)).toBe(true);
    expect(segments.find((segment) => segment.highlighted)?.text).toContain("Guideline");
  });

  it("returns unhighlighted text when alignment fails", () => {
    const segments = buildHighlightSegments("No matching text.", "missing span");

    expect(segments).toEqual([{ text: "No matching text.", highlighted: false }]);
  });
});
