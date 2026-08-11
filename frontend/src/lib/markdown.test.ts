import { describe, expect, it } from "vitest";

import { stabilizePartialMarkdown } from "./markdown";

describe("stabilizePartialMarkdown", () => {
  it("closes an in-flight fenced code block", () => {
    expect(stabilizePartialMarkdown('```json\n{"ok": true}')).toContain("\n```");
  });

  it("leaves complete markdown unchanged", () => {
    expect(stabilizePartialMarkdown("**Done**")).toBe("**Done**");
  });

  it("balances partial emphasis without disturbing list syntax", () => {
    const markdown = stabilizePartialMarkdown("- **Aspir");
    expect(markdown).toBe("- **Aspir**");
  });

  it("keeps a partial ordered list stable", () => {
    expect(stabilizePartialMarkdown("1. Review the evidence\n2.")).toContain(
      "1. Review the evidence\n2."
    );
  });
});
