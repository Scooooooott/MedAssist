import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Search } from "lucide-react";
import { describe, expect, it, vi } from "vitest";

import { Alert } from "./Alert";
import { Button } from "./Button";
import { CitationItem } from "./CitationItem";
import { Panel } from "./Panel";
import { TextInput } from "./TextInput";
import { makeResult } from "../features/qa/testData";

describe("base components", () => {
  it("renders panel regions with headings", () => {
    render(<Panel title="Evidence">Content</Panel>);
    expect(screen.getByRole("heading", { name: "Evidence" })).toBeInTheDocument();
  });

  it("renders alert text accessibly", () => {
    render(
      <Alert tone="danger" title="Unable to answer">
        Network error
      </Alert>
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Network error");
  });

  it("submits a button click", async () => {
    const onClick = vi.fn();
    render(
      <Button icon={<Search aria-hidden="true" />} onClick={onClick}>
        Ask
      </Button>
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("associates text input labels", async () => {
    render(<TextInput id="publisher" label="Publisher" />);
    await userEvent.type(screen.getByLabelText("Publisher"), "CDC");
    expect(screen.getByLabelText("Publisher")).toHaveValue("CDC");
  });

  it("expands citations and highlights the quoted span", async () => {
    render(
      <CitationItem
        result={makeResult(0)}
        citation={{
          chunkId: "chunk-0",
          documentVersionId: "version-0",
          quotedSpan: "guideline recommends aspirin",
          relevance: "evidence",
          valid: true,
          validationMessage: ""
        }}
      />
    );

    await userEvent.click(screen.getByRole("button", { name: /Guideline 0/i }));

    expect(screen.getByText(/guideline recommends aspirin/i).tagName.toLowerCase()).toBe("mark");
  });
});
