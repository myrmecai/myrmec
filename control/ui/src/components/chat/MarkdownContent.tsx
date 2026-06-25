// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { memo, useEffect, useId, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import mermaid from 'mermaid'
import { Check, Copy } from 'lucide-react'
import 'katex/dist/katex.min.css'

/**
 * Rich, sanitised markdown renderer for chat content (backlog #104c).
 *
 * Security: react-markdown does NOT render raw HTML unless `rehype-raw` is
 * added — we deliberately omit it, so embedded `<script>`/HTML in model
 * output is rendered as inert text (XSS gate). Only GFM, math (KaTeX) and
 * mermaid (rendered from text, never from HTML) are enabled.
 */
function MarkdownContentBase({ content }: { content: string }) {
  return (
    <div className="markdown-body text-sm">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex]}
        components={{
          a: ({ children, href }) => (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow">
              {children}
            </a>
          ),
          code({ className, children }) {
            const text = String(children ?? '')
            const match = /language-(\w+)/.exec(className ?? '')
            const isBlock = Boolean(match) || text.includes('\n')
            if (!isBlock) {
              return <code className="md-inline-code">{children}</code>
            }
            const language = match?.[1]
            const value = text.replace(/\n$/, '')
            if (language === 'mermaid') {
              return <MermaidDiagram code={value} />
            }
            return <CodeBlock language={language} value={value} />
          },
          // The default `pre` wraps our CodeBlock (which brings its own
          // chrome); unwrap it so we don't double-box block code.
          pre: ({ children }) => <>{children}</>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}

export const MarkdownContent = memo(MarkdownContentBase)

function CodeBlock({ language, value }: { language?: string; value: string }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // clipboard blocked (insecure context / permissions) — ignore
    }
  }
  return (
    <div className="md-codeblock" data-testid="code-block">
      <div className="md-codeblock-header">
        <span className="md-codeblock-lang">{language ?? 'text'}</span>
        <button
          type="button"
          onClick={handleCopy}
          className="md-codeblock-copy"
          title="Copy code"
          data-testid="code-copy"
        >
          {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="md-codeblock-pre">
        <code>{value}</code>
      </pre>
    </div>
  )
}

function MermaidDiagram({ code }: { code: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const rawId = useId()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const renderId = `mermaid-${rawId.replace(/[^a-zA-Z0-9]/g, '')}`
    mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' })
    mermaid
      .render(renderId, code)
      .then(({ svg }) => {
        if (!cancelled && ref.current) {
          ref.current.innerHTML = svg
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to render diagram')
        }
      })
    return () => {
      cancelled = true
    }
  }, [code, rawId])

  if (error) {
    // Fall back to the raw source so the content is never lost.
    return (
      <div className="md-codeblock" data-testid="mermaid-error">
        <div className="md-codeblock-header">
          <span className="md-codeblock-lang">mermaid (render failed)</span>
        </div>
        <pre className="md-codeblock-pre">
          <code>{code}</code>
        </pre>
      </div>
    )
  }
  return <div ref={ref} className="md-mermaid" data-testid="mermaid-diagram" />
}
