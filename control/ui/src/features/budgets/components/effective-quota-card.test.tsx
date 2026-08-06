// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import '@testing-library/jest-dom'
import { render, screen, fireEvent } from '@testing-library/react'
import { EffectiveQuotaCard } from './effective-quota-card'
import type { EffectiveQuota } from '@/lib/api'

function makeQuota(overrides: Partial<EffectiveQuota> = {}): EffectiveQuota {
  return {
    id: 'q-1',
    scopeType: 'PROJECT',
    scopeId: 'p-1',
    name: 'Project budget',
    resourceType: 'COST_USD_CENTS',
    period: 'MONTHLY_CALENDAR',
    serviceType: null,
    ownLimit: 10000,
    inheritedLimit: null,
    effectiveLimit: 10000,
    quotaType: 'CEILING',
    enforcementMode: 'BLOCK',
    consumed: 2500,
    remaining: 7500,
    atRisk: false,
    exceeded: false,
    paused: false,
    ...overrides,
  }
}

describe('EffectiveQuotaCard', () => {
  it('renders quota details and progress', () => {
    render(
      <EffectiveQuotaCard
        quota={makeQuota()}
        resourceType="COST_USD_CENTS"
        period="MONTHLY_CALENDAR"
        canMutate={true}
        onPause={vi.fn()}
        onResume={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    const heading = screen.getByRole('heading', { level: 3 })
    expect(heading.textContent?.toLowerCase()).toContain('this month')
    expect(heading.textContent?.toLowerCase()).toContain('cost usd')
    expect(heading.textContent?.toLowerCase()).toContain('budget')
    expect(screen.getByText('$100.00')).toBeInTheDocument()
    expect(screen.getByText(/type:\s*ceiling/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /edit/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /pause/i })).toBeInTheDocument()
  })

  it('shows Resume when paused', () => {
    render(
      <EffectiveQuotaCard
        quota={makeQuota({ paused: true })}
        resourceType="COST_USD_CENTS"
        period="MONTHLY_CALENDAR"
        canMutate={true}
        onPause={vi.fn()}
        onResume={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: /resume/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /pause/i })).not.toBeInTheDocument()
  })

  it('hides actions when cannot mutate', () => {
    render(
      <EffectiveQuotaCard
        quota={makeQuota()}
        resourceType="COST_USD_CENTS"
        period="MONTHLY_CALENDAR"
        canMutate={false}
        onPause={vi.fn()}
        onResume={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.queryByRole('button', { name: /edit/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /pause/i })).not.toBeInTheDocument()
  })

  it('calls action handlers', () => {
    const onPause = vi.fn()
    const onDelete = vi.fn()
    render(
      <EffectiveQuotaCard
        quota={makeQuota()}
        resourceType="COST_USD_CENTS"
        period="MONTHLY_CALENDAR"
        canMutate={true}
        onPause={onPause}
        onResume={vi.fn()}
        onDelete={onDelete}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /pause/i }))
    expect(onPause).toHaveBeenCalledWith('q-1')

    fireEvent.click(screen.getByRole('button', { name: /delete/i }))
    expect(onDelete).toHaveBeenCalledWith('q-1')
  })

  it('highlights exceeded state', () => {
    render(
      <EffectiveQuotaCard
        quota={makeQuota({ exceeded: true, consumed: 15000, remaining: -5000 })}
        resourceType="COST_USD_CENTS"
        period="MONTHLY_CALENDAR"
        canMutate={false}
        onPause={vi.fn()}
        onResume={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.getByText('Exceeded')).toBeInTheDocument()
  })
})
