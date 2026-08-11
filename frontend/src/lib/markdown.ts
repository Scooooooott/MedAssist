export function stabilizePartialMarkdown(markdown: string): string {
  const fenceCount = (markdown.match(/```/g) ?? []).length;
  let stabilized = markdown;
  const inlineMarkers = countInlineMarkers(markdown);
  if (inlineMarkers.bold % 2 === 1) stabilized += "**";
  if (inlineMarkers.underscore % 2 === 1) stabilized += "__";
  if (fenceCount % 2 === 1) {
    return `${stabilized}\n\`\`\``;
  }
  return stabilized;
}

function countInlineMarkers(markdown: string): { bold: number; underscore: number } {
  let inFence = false;
  let bold = 0;
  let underscore = 0;
  for (const line of markdown.split(/\r?\n/)) {
    if (line.trimStart().startsWith("```")) {
      inFence = !inFence;
      continue;
    }
    if (!inFence) {
      bold += (line.match(/\*\*/g) ?? []).length;
      underscore += (line.match(/__/g) ?? []).length;
    }
  }
  return { bold, underscore };
}
