import { describe, expect, it } from "vitest";

import { stabilizePartialMarkdown } from "./markdown";

describe("stabilizePartialMarkdown", () => {
  it("closes an in-flight fenced code block", () => {
    expect(stabilizePartialMarkdown('```json\n{"ok": true}')).toContain("\n```");
  });

  it("leaves complete markdown unchanged", () => {
    expect(stabilizePartialMarkdown("**Done**")).toBe("**Done**");
  });
});
