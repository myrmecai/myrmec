// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Shared layout wrapper for content rendered inside a PlatformGroupLayout
 * (or any parent that provides an <Outlet />). Applies consistent padding
 * so that all platform pages share the same look.
 *
 * Usage:
 * ```tsx
 * function ModelsPage() {
 *   return (
 *     <ContentAreaLayout>
 *       <div>...</div>
 *     </ContentAreaLayout>
 *   )
 * }
 * ```
 */
export function ContentAreaLayout({
  children,
  maxWidth,
}: {
  children: React.ReactNode
  maxWidth?: string
}) {
  const style = maxWidth ? { maxWidth } : undefined
  return (
    <div className="p-8 h-full overflow-auto" style={style}>
      {children}
    </div>
  )
}