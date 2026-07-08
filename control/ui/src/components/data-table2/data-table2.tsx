/* Data table with multiple selection support */

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table.tsx";
import { type ColumnDef, flexRender, getCoreRowModel, getPaginationRowModel, getSortedRowModel, type Row, type SortingState, useReactTable } from "@tanstack/react-table";
import _ from "lodash";
import { useEffect, useState } from "react";
import { DataTablePagination } from "./data-table-pagination.tsx";

interface DataTableProps<TData, TValue> {
    columns: ColumnDef<TData, TValue>[]
    data: TData[]
    onSelectionChange?: (selectedRows: TData[] | TData) => void
    enableMultiRowSelection?: boolean,
    loading?: boolean,
    pagination?: boolean,
    className?: string,
    showColumnSeperator?: boolean,
    showRowSelection?: boolean,
    selectedRows?: TData[]
    defaultSort?: { id: string; desc: boolean }
    pageSize?: number
}

function DataTable2<TData, TValue>({ columns, data, onSelectionChange, enableMultiRowSelection,
    loading = false, pagination = true, className = "", showColumnSeperator = false,
    selectedRows = [], showRowSelection = false, defaultSort, pageSize = 10
    , ...props }: DataTableProps<TData, TValue>) {
    const [sorting, setSorting] = useState<SortingState>(defaultSort ? [defaultSort] : [])
    const columnSeparatorClass = "relative not-last:[&>div]:pr-4 not-last:after:absolute not-last:after:content-[''] not-last:after:border-border not-last:after:border-r-1 not-last:after:right-0 not-last:after:top-4 not-last:after:bottom-4"
    const [rowSelection, setRowSelection] = useState<any>({})
    const multiSelect = enableMultiRowSelection ?? false;
    const cellClassName = showColumnSeperator ? columnSeparatorClass : ""
    const rowSelectionMark = showRowSelection ? true : (onSelectionChange || selectedRows.length > 0) && !multiSelect;
    useEffect(() => {
        callOnSelectionChange();
    }, [rowSelection]);
    var selectedPageIndex = 0;
    useEffect(() => {
        if (selectedRows.length > 0) {
            const newSelection: Record<string, boolean> = {};
            selectedRows.forEach(selectedRow => {
                const rowIndex = data.indexOf(selectedRow);
                if (rowIndex !== -1) {
                    newSelection[String(rowIndex)] = true;
                }
            });
            if (!_.isEqual(rowSelection, newSelection)) {
                setRowSelection(newSelection);
            }
        }
        const selectedFirstRowInex = selectedRows.length > 0 ? data.indexOf(selectedRows[0]) : -1;
        selectedPageIndex = selectedFirstRowInex !== -1 ? Math.floor(selectedFirstRowInex / 10) : 0;
        if (selectedPageIndex > 0 && table.getState().pagination.pageIndex !== selectedPageIndex) {
            table.setPageIndex(() => selectedPageIndex);
        }
    }, [selectedRows])

    function callOnSelectionChange() {
        if (onSelectionChange) {
            const selectedRowData = Object.keys(rowSelection)
                .filter(key => rowSelection[key])
                .map(key => data[parseInt(key)])
            onSelectionChange(selectedRowData)
        }
    }
    const table = useReactTable({
        data: data,
        columns: columns,
        getCoreRowModel: getCoreRowModel(),
        getPaginationRowModel: pagination ? getPaginationRowModel() : undefined,
        enableMultiRowSelection: multiSelect,
        //  onRowSelectionChange: setRowSelection
        onRowSelectionChange: (updater) => {
            setRowSelection(updater)
            if (onSelectionChange) {
                const newSelection = typeof updater === 'function' ? updater(rowSelection) : updater
                const selectedRowData = Object.keys(newSelection)
                    .filter(key => newSelection[key])
                    .map(key => data[parseInt(key)])
                onSelectionChange(selectedRowData)
            }
        },
        onSortingChange: setSorting,
        getSortedRowModel: getSortedRowModel(),
        state: {
            rowSelection,
            sorting
        },
        initialState: {
            pagination: { pageIndex: selectedPageIndex, pageSize }
        }
    })
    const NoResult = () => {
        return (
            <span className="text-muted-foreground">
                No results found.
            </span>
        )
    }

    const onRowClick = (row: Row<TData>) => {
        if (!multiSelect) {
            const newSelection = { [row.id]: !row.getIsSelected() };
            setRowSelection(newSelection);
            // callOnSelectionChange();
        } else {
            row.toggleSelected();
        }
    }
    const selectedRowClassName = rowSelectionMark == false ? "" : "data-[state=selected]:bg-current";
    const Loading = () => {
        return (
            <div className="flex items-center justify-center">
                <svg className="animate-spin h-5 w-5 text-gray-500" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2.93 6.364A8.001 8.001 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3.93-1.574zM12 20a8.001 8.001 0 01-6.364-3.07l-3.93 1.574A11.95 11.95 0 0012 24v-4zm6.364-2.93A8.001 8.001 0 0120 12h4c0 3.042-1.135 5.824-3 7.938l-3.636-1.568zM20 12a8.001 8.001 0 01-3.07-6.364l3.574-1.93A11.95 11.95 0 0024 12h-4z"></path>
                </svg>
            </div>
        )
    }
    return (<div>
        <Table className={className}>
            <TableHeader>
                {table.getHeaderGroups().map((headerGroup) => (<TableRow key={headerGroup.id}>
                    {headerGroup.headers.map((header) => {
                        return (<TableHead
                            key={header.id}
                            className={cellClassName}
                        >
                            {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                        </TableHead>)
                    })}
                </TableRow>))}
            </TableHeader>
            <TableBody>
                {table.getRowModel().rows?.length ? (table.getRowModel().rows.map((row) => (
                    <TableRow key={row.id} data-state={row.getIsSelected() && "selected"}
                        onClick={() => onRowClick(row)}
                        className={selectedRowClassName}
                    >
                        {row.getVisibleCells().map((cell) => (
                            <TableCell
                                key={cell.id}
                                className={cellClassName}
                            >
                                {flexRender(cell.column.columnDef.cell, cell.getContext())}
                            </TableCell>))}
                    </TableRow>))) : (<TableRow>
                        <TableCell colSpan={columns.length} className="h-24 text-center">
                            {loading ? <Loading /> : <NoResult />}
                        </TableCell>
                    </TableRow>)}
            </TableBody>
        </Table>
        {pagination && <div className="flex items-center">
            <div className="text-muted-foreground flex-1 text-sm">
                {`${table.getFilteredSelectedRowModel().rows.length} of ${table.getFilteredRowModel().rows.length} selected`}
            </div>
            <DataTablePagination table={table} />
        </div>
        }
    </div>)
}

export default DataTable2;