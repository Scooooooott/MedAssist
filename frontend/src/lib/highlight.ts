export interface HighlightSegment {
  text: string;
  highlighted: boolean;
}

interface NormalizedChar {
  char: string;
  originalIndex: number;
}

export function buildHighlightSegments(text: string, quotedSpan: string): HighlightSegment[] {
  if (!quotedSpan) return [{ text, highlighted: false }];

  const normalizedText = normalizeWithMap(text);
  const normalizedQuote = normalizeWithMap(quotedSpan);
  const index = normalizedText
    .map((item) => item.char)
    .join("")
    .indexOf(normalizedQuote.map((item) => item.char).join(""));

  if (index < 0) return [{ text, highlighted: false }];

  const start = normalizedText[index]?.originalIndex ?? 0;
  const last = normalizedText[index + normalizedQuote.length - 1];
  const end = last ? last.originalIndex + 1 : start;

  return [
    { text: text.slice(0, start), highlighted: false },
    { text: text.slice(start, end), highlighted: true },
    { text: text.slice(end), highlighted: false }
  ].filter((segment) => segment.text.length > 0);
}

function normalizeWithMap(value: string): NormalizedChar[] {
  const normalized: NormalizedChar[] = [];
  let previousWasSpace = false;

  Array.from(value).forEach((char, index) => {
    if (/\s/.test(char)) {
      if (!previousWasSpace) {
        normalized.push({ char: " ", originalIndex: index });
        previousWasSpace = true;
      }
      return;
    }
    previousWasSpace = false;
    normalized.push({ char: char.toLocaleLowerCase("en-US"), originalIndex: index });
  });

  return trimMappedSpaces(normalized);
}

function trimMappedSpaces(chars: NormalizedChar[]): NormalizedChar[] {
  let start = 0;
  let end = chars.length;
  while (chars[start]?.char === " ") start += 1;
  while (chars[end - 1]?.char === " ") end -= 1;
  return chars.slice(start, end);
}
