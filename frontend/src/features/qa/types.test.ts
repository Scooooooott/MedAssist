import { describe, expect, it } from "vitest";

import { getSearchFilters, type SearchResponse } from "./types";

function search(overrides: Partial<SearchResponse>): SearchResponse {
  return { query: "test", results: [], ...overrides };
}

describe("search response compatibility", () => {
  it("prefers the backend filters field", () => {
    expect(
      getSearchFilters(
        search({
          filters: { docTypes: ["GUIDELINE"], publishers: ["CDC"] },
          appliedFilters: { docTypes: ["OLD"], publishers: [] }
        })
      )
    ).toEqual({ docTypes: ["GUIDELINE"], publishers: ["CDC"] });
  });

  it("accepts the previous appliedFilters field", () => {
    expect(
      getSearchFilters(search({ appliedFilters: { docTypes: ["POLICY"], publishers: [] } }))
    ).toEqual({ docTypes: ["POLICY"], publishers: [] });
  });

  it("keeps the previous retrievedAt timestamp available", () => {
    expect(search({ retrievedAt: "2026-08-07T00:00:00Z" }).retrievedAt).toBe(
      "2026-08-07T00:00:00Z"
    );
  });

  it("uses empty filters when neither response field is present", () => {
    expect(getSearchFilters(search({}))).toEqual({ docTypes: [], publishers: [] });
  });
});
