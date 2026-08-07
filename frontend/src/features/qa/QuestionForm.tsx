import { Search } from "lucide-react";
import { useState } from "react";
import type { FormEvent } from "react";

import { Button } from "../../components/Button";
import { TextInput } from "../../components/TextInput";
import type { RetrievalFilters } from "./types";

interface QuestionFormProps {
  disabled: boolean;
  onAsk: (query: string, filters: RetrievalFilters) => void;
}

export function QuestionForm({ disabled, onAsk }: QuestionFormProps) {
  const [query, setQuery] = useState("");
  const [docTypes, setDocTypes] = useState("");
  const [publishers, setPublishers] = useState("");

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;
    onAsk(trimmed, {
      docTypes: splitValues(docTypes),
      publishers: splitValues(publishers)
    });
  }

  return (
    <form className="question-form" onSubmit={handleSubmit}>
      <label className="field" htmlFor="query">
        <span>Question</span>
        <textarea
          id="query"
          value={query}
          placeholder="Ask a corpus-grounded question"
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>
      <div className="filter-grid">
        <TextInput
          id="doc-types"
          label="Document types"
          placeholder="GUIDELINE, POLICY"
          value={docTypes}
          onChange={(event) => setDocTypes(event.target.value)}
        />
        <TextInput
          id="publishers"
          label="Publishers"
          placeholder="CDC, FDA"
          value={publishers}
          onChange={(event) => setPublishers(event.target.value)}
        />
      </div>
      <Button disabled={disabled} icon={<Search aria-hidden="true" size={18} />} type="submit">
        Ask
      </Button>
    </form>
  );
}

function splitValues(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}
