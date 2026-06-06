import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import { TableHead } from '@/components/ui/table'
import { cn } from '@/lib/utils'

export type SortDirection = 'asc' | 'desc'

interface SortableTableHeaderProps<F extends string> {
  field: F
  label: string
  activeField: F
  direction: SortDirection
  onSortChange: (field: F, direction: SortDirection) => void
  className?: string
}

export function SortableTableHeader<F extends string>({
  field,
  label,
  activeField,
  direction,
  onSortChange,
  className,
}: SortableTableHeaderProps<F>) {
  const isActive = field === activeField
  const nextDirection: SortDirection =
    isActive ? (direction === 'asc' ? 'desc' : 'asc') : 'asc'

  const Icon = !isActive ? ArrowUpDown : direction === 'asc' ? ArrowUp : ArrowDown

  return (
    <TableHead className={className}>
      <button
        type="button"
        className={cn(
          'inline-flex items-center gap-1 select-none hover:text-foreground',
          isActive ? 'text-foreground font-medium' : 'text-muted-foreground'
        )}
        onClick={() => onSortChange(field, nextDirection)}
      >
        <span>{label}</span>
        <Icon className="h-3.5 w-3.5" />
      </button>
    </TableHead>
  )
}
