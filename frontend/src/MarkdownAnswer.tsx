import { memo } from "react";
import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

const components: Components = {
  a: ({ href, children }) => href
    ? <a href={href} target="_blank" rel="noopener noreferrer nofollow">{children}</a>
    : <span>{children}</span>,
  img: ({ alt }) => <span>{alt}</span>,
  table: ({ children }) => <div className="overflow-x-auto"><table>{children}</table></div>,
};

/** Renders Markdown without executing raw HTML or automatically loading model-supplied images. */
const MarkdownAnswer = memo(function MarkdownAnswer({ text }: { text: string }) {
  return <div className="markdown-answer"><Markdown remarkPlugins={[remarkGfm]} components={components}>{text}</Markdown></div>;
});

export default MarkdownAnswer;
