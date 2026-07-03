// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, Outlet, useLocation } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'

interface SubNavItem {
  label: string
  to: string
}

/**
 * Shared layout for a Platform tab group. Renders a sub-navigation bar
 * (when the group has more than one item) and an <Outlet /> for the
 * nested route content.
 */
export function PlatformGroupLayout({
  title,
  items,
}: {
  title: string
  items: SubNavItem[]
}) {
  const location = useLocation()
  const currentPath = location.pathname

  return (
    <div className="flex flex-col h-full">
      {/* Header + sub-navigation */}
      <div className="border-b bg-card px-6 py-3 shrink-0">
        <h1 className="text-lg font-semibold mb-2">{title}</h1>
        {items.length > 1 && (
          <div className="flex items-center gap-1">
            {items.map((item) => {
              const isActive = currentPath === item.to || currentPath.startsWith(item.to + '/')
              return (
                <Link key={item.to} to={item.to}>
                  <Button
                    variant={isActive ? 'secondary' : 'ghost'}
                    size="sm"
                    className={isActive ? 'font-medium' : ''}
                  >
                    {item.label}
                  </Button>
                </Link>
              )
            })}
          </div>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto bg-muted/40">
        <Outlet />
      </div>
    </div>
  )
}