// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { formatAmount, formatResourceType, formatPeriod, calculatePercentage, statusFromPercentage } from './format'

describe('budget format helpers', () => {
  describe('formatAmount', () => {
    it('returns em dash for null/undefined', () => {
      expect(formatAmount(null, 'COST_USD_CENTS')).toBe('—')
      expect(formatAmount(undefined, 'COST_USD_CENTS')).toBe('—')
    })

    it('formats cost in USD dollars', () => {
      expect(formatAmount(10000, 'COST_USD_CENTS')).toBe('$100.00')
      expect(formatAmount(12345, 'COST_USD_CENTS')).toBe('$123.45')
      expect(formatAmount(5, 'COST_USD_CENTS')).toBe('$0.05')
    })

    it('formats tokens with locale', () => {
      expect(formatAmount(1234567, 'TOKENS')).toBe('1,234,567')
    })
  })

  describe('formatResourceType', () => {
    it('maps resource types', () => {
      expect(formatResourceType('COST_USD_CENTS')).toBe('Cost USD')
      expect(formatResourceType('TOKENS')).toBe('Tokens')
      expect(formatResourceType('REQUESTS')).toBe('Requests')
    })
  })

  describe('formatPeriod', () => {
    it('maps periods', () => {
      expect(formatPeriod('DAILY')).toBe('Today')
      expect(formatPeriod('MONTHLY_CALENDAR')).toBe('This month')
      expect(formatPeriod('LIFETIME')).toBe('Lifetime')
      expect(formatPeriod('UNKNOWN')).toBe('UNKNOWN')
    })
  })

  describe('calculatePercentage', () => {
    it('returns 0 when missing limit or consumed', () => {
      expect(calculatePercentage(null, 100)).toBe(0)
      expect(calculatePercentage(50, null)).toBe(0)
      expect(calculatePercentage(50, 0)).toBe(0)
    })

    it('rounds and caps at 100', () => {
      expect(calculatePercentage(50, 100)).toBe(50)
      expect(calculatePercentage(80, 100)).toBe(80)
      expect(calculatePercentage(150, 100)).toBe(100)
      expect(calculatePercentage(33, 100)).toBe(33)
      expect(calculatePercentage(3333, 10000)).toBe(33)
    })
  })

  describe('statusFromPercentage', () => {
    it('returns paused first', () => {
      expect(statusFromPercentage(120, true)).toBe('paused')
      expect(statusFromPercentage(50, true)).toBe('paused')
    })

    it('flags exceeded over 100', () => {
      expect(statusFromPercentage(101, false)).toBe('exceeded')
      expect(statusFromPercentage(100, false)).toBe('at-risk')
    })

    it('flags at-risk between 80 and 100', () => {
      expect(statusFromPercentage(80, false)).toBe('at-risk')
      expect(statusFromPercentage(95, false)).toBe('at-risk')
    })

    it('returns ok under 80', () => {
      expect(statusFromPercentage(0, false)).toBe('ok')
      expect(statusFromPercentage(79, false)).toBe('ok')
    })
  })
})
