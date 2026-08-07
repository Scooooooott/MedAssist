export function stabilizePartialMarkdown(markdown: string): string {
  const fenceCount = (markdown.match(/```/g) ?? []).length;
  if (fenceCount % 2 === 1) {
    return `${markdown}\n\`\`\``;
  }
  return markdown;
}
