import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { makeResult } from "./testData";
import { VirtualEvidenceList } from "./VirtualEvidenceList";

describe("VirtualEvidenceList", () => {
  it("renders only the visible evidence rows for a large result set", () => {
    const results = Array.from({ length: 500 }, (_, index) => makeResult(index));
    render(
      <VirtualEvidenceList citations={[]} results={results} viewportHeight={420} rowHeight={174} />
    );

    expect(screen.getByText("500 chunks")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Guideline/i }).length).toBeLessThan(20);
  });

  it("shows an empty state", () => {
    render(
      <VirtualEvidenceList citations={[]} results={[]} viewportHeight={420} rowHeight={174} />
    );

    expect(screen.getByText("No evidence chunks were returned.")).toBeInTheDocument();
  });
});
