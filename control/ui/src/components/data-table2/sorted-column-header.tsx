import type { Column, Table } from "@tanstack/react-table";

export function SortedColumnHeader({
    table,
    column,
    title,
    defaultSort
}: {
    table: Table<any>;
    column: Column<any, any>;
    title: string;
    defaultSort?: "asc" | "desc";
}) {
    // Only use defaultSort when no column in the table is sorted
    const isAnySorted = table.getState().sorting.length > 0;
    const sortDirection = column.getIsSorted() ? column.getIsSorted() : !isAnySorted ? defaultSort : undefined;
    const onClick = () => {
        column.toggleSorting(column.getIsSorted() === "asc");
    };

    const SortIcon = () => {
        switch (sortDirection) {
            case "asc":
                return (
                    <svg className="h-4 w-4 cursor-pointer" viewBox="0 0 24 24" fill="none" >
                        <path d="M8 16l4-8 4 8" stroke="#3b82f6" strokeWidth="2" fill="none" /> {/* Up arrow colored */}
                    </svg>
                );
            case "desc":
                return (
                    <svg className="h-4 w-4 cursor-pointer" viewBox="0 0 24 24" fill="none">
                        <path d="M8 8l4 8 4-8" stroke="#3b82f6" strokeWidth="2" fill="none" /> {/* Down arrow colored */}
                    </svg>
                );
            default:
                return (
                    <svg className="h-4 w-4 cursor-pointer" viewBox="0 0 24 24" fill="none">
                        <path d="M8 16l4-8 4 8" stroke="#a3a3a3" strokeWidth="2" fill="none" /> {/* Up arrow muted */}
                    </svg>
                );
        }
    };
    return (
        <div
            className="flex items-center select-none"
        >
            {title}
            <div onClick={onClick}><SortIcon /></div>
        </div>
    );
}
