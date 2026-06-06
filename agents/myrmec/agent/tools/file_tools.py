"""
File management tools for agent task execution.

These tools provide file system operations within a configured workspace directory.
All paths are restricted to stay within the workspace for security.
"""

from __future__ import annotations

import fnmatch
import logging
import re
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# LangChain imports - optional dependency
try:
    from langchain_core.tools import BaseTool, tool
    LANGCHAIN_AVAILABLE = True
except ImportError:
    LANGCHAIN_AVAILABLE = False
    BaseTool = Any
    tool = lambda f: f  # No-op decorator


def _resolve_safe_path(workspace: Path, file_path: str) -> Path | None:
    """
    Resolve a file path safely within workspace bounds.
    
    Args:
        workspace: Workspace root directory.
        file_path: Requested file path (can be relative or absolute).
        
    Returns:
        Resolved Path if safe, None if path escapes workspace.
    """
    try:
        # Handle absolute paths - make them relative to workspace
        requested = Path(file_path)
        if requested.is_absolute():
            # Try to make it relative if it's within workspace
            try:
                requested = requested.relative_to(workspace)
            except ValueError:
                # Path is outside workspace
                return None
        
        # Resolve .. and symlinks
        resolved = (workspace / requested).resolve()
        
        # Ensure resolved path is within workspace
        try:
            resolved.relative_to(workspace.resolve())
            return resolved
        except ValueError:
            return None
    except Exception as e:
        logger.warning("Path resolution error: %s", e)
        return None


def create_read_file_tool(workspace_path: Path | None) -> "BaseTool":
    """Create read_file tool configured for workspace."""
    
    @tool
    def read_file(
        file_path: str,
        encoding: str = "utf-8",
    ) -> str:
        """
        Read the contents of a file.
        
        Args:
            file_path: Path to the file (relative to workspace or absolute).
            encoding: Text encoding (default: utf-8).
            
        Returns:
            File contents as string.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, file_path)
        if not resolved:
            return f"Error: Path '{file_path}' is outside workspace or invalid"
        
        if not resolved.exists():
            return f"Error: File not found: {file_path}"
        
        if not resolved.is_file():
            return f"Error: Not a file: {file_path}"
        
        try:
            content = resolved.read_text(encoding=encoding)
            return content
        except UnicodeDecodeError:
            return f"Error: Cannot decode file with {encoding} encoding"
        except Exception as e:
            return f"Error reading file: {e}"
    
    return read_file


def create_write_file_tool(workspace_path: Path | None) -> "BaseTool":
    """Create write_file tool configured for workspace."""
    
    @tool
    def write_file(
        file_path: str,
        content: str,
        encoding: str = "utf-8",
        create_dirs: bool = True,
    ) -> str:
        """
        Write content to a file. Creates parent directories if they don't exist.
        
        Args:
            file_path: Path to the file (relative to workspace or absolute).
            content: Content to write.
            encoding: Text encoding (default: utf-8).
            create_dirs: Create parent directories if missing (default: True).
            
        Returns:
            Success message or error.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, file_path)
        if not resolved:
            return f"Error: Path '{file_path}' is outside workspace or invalid"
        
        try:
            if create_dirs and not resolved.parent.exists():
                resolved.parent.mkdir(parents=True, exist_ok=True)
            
            resolved.write_text(content, encoding=encoding)
            return f"Successfully wrote {len(content)} characters to {file_path}"
        except Exception as e:
            return f"Error writing file: {e}"
    
    return write_file


def create_list_directory_tool(workspace_path: Path | None) -> "BaseTool":
    """Create list_directory tool configured for workspace."""
    
    @tool
    def list_directory(
        dir_path: str = ".",
        include_hidden: bool = False,
    ) -> str:
        """
        List contents of a directory.
        
        Args:
            dir_path: Directory path (relative to workspace, default is workspace root).
            include_hidden: Include hidden files/directories starting with '.' (default: False).
            
        Returns:
            List of entries with type indicators (/ for directories).
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, dir_path)
        if not resolved:
            return f"Error: Path '{dir_path}' is outside workspace or invalid"
        
        if not resolved.exists():
            return f"Error: Directory not found: {dir_path}"
        
        if not resolved.is_dir():
            return f"Error: Not a directory: {dir_path}"
        
        try:
            entries = []
            for item in sorted(resolved.iterdir()):
                name = item.name
                
                # Skip hidden files unless requested
                if not include_hidden and name.startswith('.'):
                    continue
                
                # Add / suffix for directories
                if item.is_dir():
                    entries.append(f"{name}/")
                else:
                    entries.append(name)
            
            if not entries:
                return "Directory is empty"
            
            return "\n".join(entries)
        except Exception as e:
            return f"Error listing directory: {e}"
    
    return list_directory


def create_search_files_tool(workspace_path: Path | None) -> "BaseTool":
    """Create search_files tool configured for workspace."""
    
    @tool
    def search_files(
        pattern: str,
        dir_path: str = ".",
        max_results: int = 100,
    ) -> str:
        """
        Search for files matching a glob pattern.
        
        Args:
            pattern: Glob pattern (e.g., "*.py", "src/**/*.java").
            dir_path: Directory to search in (default: workspace root).
            max_results: Maximum number of results to return (default: 100).
            
        Returns:
            List of matching file paths, one per line.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, dir_path)
        if not resolved:
            return f"Error: Path '{dir_path}' is outside workspace or invalid"
        
        if not resolved.exists():
            return f"Error: Directory not found: {dir_path}"
        
        try:
            matches = []
            for path in resolved.rglob(pattern):
                if path.is_file():
                    # Return path relative to workspace
                    try:
                        rel_path = path.relative_to(workspace_path)
                        matches.append(str(rel_path))
                    except ValueError:
                        matches.append(str(path))
                    
                    if len(matches) >= max_results:
                        break
            
            if not matches:
                return f"No files found matching pattern: {pattern}"
            
            result = "\n".join(matches)
            if len(matches) >= max_results:
                result += f"\n\n(Showing first {max_results} results)"
            
            return result
        except Exception as e:
            return f"Error searching files: {e}"
    
    return search_files


def create_search_code_tool(workspace_path: Path | None) -> "BaseTool":
    """Create search_code tool configured for workspace."""
    
    @tool
    def search_code(
        query: str,
        file_pattern: str = "*",
        dir_path: str = ".",
        is_regex: bool = False,
        max_results: int = 50,
        context_lines: int = 2,
    ) -> str:
        """
        Search for text or regex pattern in code files.
        
        Args:
            query: Search string or regex pattern.
            file_pattern: Glob pattern for files to search (e.g., "*.py", "*.java").
            dir_path: Directory to search in (default: workspace root).
            is_regex: Treat query as regex pattern (default: False).
            max_results: Maximum number of matches to return (default: 50).
            context_lines: Number of context lines before/after match (default: 2).
            
        Returns:
            Matching lines with file path and line numbers.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, dir_path)
        if not resolved:
            return f"Error: Path '{dir_path}' is outside workspace or invalid"
        
        try:
            # Compile pattern
            if is_regex:
                pattern = re.compile(query, re.IGNORECASE)
            else:
                pattern = re.compile(re.escape(query), re.IGNORECASE)
        except re.error as e:
            return f"Error: Invalid regex pattern: {e}"
        
        # Binary file extensions to skip
        binary_extensions = {
            '.pyc', '.pyo', '.so', '.dll', '.exe', '.bin',
            '.jpg', '.jpeg', '.png', '.gif', '.ico', '.svg',
            '.pdf', '.zip', '.tar', '.gz', '.jar', '.war',
            '.class', '.o', '.a', '.lib', '.whl', '.egg',
        }
        
        try:
            results = []
            files_searched = 0
            
            for file_path in resolved.rglob(file_pattern):
                if not file_path.is_file():
                    continue
                
                # Skip binary files
                if file_path.suffix.lower() in binary_extensions:
                    continue
                
                files_searched += 1
                
                try:
                    content = file_path.read_text(errors='ignore')
                    lines = content.split('\n')
                    
                    for i, line in enumerate(lines):
                        if pattern.search(line):
                            # Get relative path
                            try:
                                rel_path = file_path.relative_to(workspace_path)
                            except ValueError:
                                rel_path = file_path
                            
                            # Build context
                            start = max(0, i - context_lines)
                            end = min(len(lines), i + context_lines + 1)
                            
                            context_text = []
                            for j in range(start, end):
                                prefix = ">" if j == i else " "
                                context_text.append(f"{prefix} {j+1:4d} | {lines[j]}")
                            
                            results.append(f"{rel_path}:\n" + "\n".join(context_text))
                            
                            if len(results) >= max_results:
                                break
                    
                    if len(results) >= max_results:
                        break
                        
                except Exception:
                    # Skip files that can't be read
                    continue
            
            if not results:
                return f"No matches found for: {query} (searched {files_searched} files)"
            
            output = "\n\n".join(results)
            if len(results) >= max_results:
                output += f"\n\n(Showing first {max_results} matches from {files_searched} files)"
            
            return output
        except Exception as e:
            return f"Error searching code: {e}"
    
    return search_code


def create_get_file_tree_tool(workspace_path: Path | None) -> "BaseTool":
    """Create get_file_tree tool configured for workspace."""
    
    @tool
    def get_file_tree(
        dir_path: str = ".",
        max_depth: int = 3,
        include_hidden: bool = True,
    ) -> str:
        """
        Get a tree view of directory structure.
        
        Args:
            dir_path: Starting directory (default: workspace root).
            max_depth: Maximum depth to traverse (default: 3).
            include_hidden: Include hidden files/directories (default: False).
            
        Returns:
            Tree-formatted directory structure.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, dir_path)
        if not resolved:
            return f"Error: Path '{dir_path}' is outside workspace or invalid"
        
        if not resolved.exists():
            return f"Error: Directory not found: {dir_path}"
        
        if not resolved.is_dir():
            return f"Error: Not a directory: {dir_path}"
        
        def build_tree(path: Path, prefix: str = "", depth: int = 0) -> list[str]:
            if depth > max_depth:
                return []
            
            entries = []
            try:
                items = sorted(path.iterdir())
                
                # Filter hidden if needed
                if not include_hidden:
                    items = [i for i in items if not i.name.startswith('.')]
                else:
                    # Always skip .git directory to avoid noise
                    items = [i for i in items if not (i.is_dir() and i.name == '.git')]
                
                # Separate dirs and files for better display
                dirs = [i for i in items if i.is_dir()]
                files = [i for i in items if i.is_file()]
                all_items = dirs + files
                
                for i, item in enumerate(all_items):
                    is_last = i == len(all_items) - 1
                    connector = "└── " if is_last else "├── "
                    
                    name = item.name
                    if item.is_dir():
                        name += "/"
                    
                    entries.append(f"{prefix}{connector}{name}")
                    
                    if item.is_dir() and depth < max_depth:
                        extension = "    " if is_last else "│   "
                        entries.extend(build_tree(item, prefix + extension, depth + 1))
            except PermissionError:
                entries.append(f"{prefix}└── [Permission Denied]")
            
            return entries
        
        try:
            # Get relative path for display
            try:
                display_path = resolved.relative_to(workspace_path)
                if str(display_path) == ".":
                    display_path = workspace_path.name
            except ValueError:
                display_path = resolved.name
            
            lines = [f"{display_path}/"]
            lines.extend(build_tree(resolved))
            
            return "\n".join(lines)
        except Exception as e:
            return f"Error building tree: {e}"
    
    return get_file_tree


def create_delete_file_tool(workspace_path: Path | None) -> "BaseTool":
    """Create delete_file tool configured for workspace."""
    
    @tool
    def delete_file(file_path: str) -> str:
        """
        Delete a file.
        
        Args:
            file_path: Path to the file to delete.
            
        Returns:
            Success message or error.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, file_path)
        if not resolved:
            return f"Error: Path '{file_path}' is outside workspace or invalid"
        
        if not resolved.exists():
            return f"Error: File not found: {file_path}"
        
        if not resolved.is_file():
            return f"Error: Not a file: {file_path}"
        
        try:
            resolved.unlink()
            return f"Successfully deleted: {file_path}"
        except Exception as e:
            return f"Error deleting file: {e}"
    
    return delete_file


def create_create_directory_tool(workspace_path: Path | None) -> "BaseTool":
    """Create create_directory tool configured for workspace."""
    
    @tool
    def create_directory(dir_path: str) -> str:
        """
        Create a directory (including parent directories).
        
        Args:
            dir_path: Path to the directory to create.
            
        Returns:
            Success message or error.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        resolved = _resolve_safe_path(workspace_path, dir_path)
        if not resolved:
            return f"Error: Path '{dir_path}' is outside workspace or invalid"
        
        try:
            resolved.mkdir(parents=True, exist_ok=True)
            return f"Successfully created directory: {dir_path}"
        except Exception as e:
            return f"Error creating directory: {e}"
    
    return create_directory


# ==================== Registration ====================

def register_all() -> None:
    """Register all file tools with the ToolRegistry."""
    if not LANGCHAIN_AVAILABLE:
        logger.debug("LangChain not available, skipping file tools registration")
        return
    
    from myrmec.agent.tools import ToolRegistry
    
    # Register factory functions for workspace-aware tools
    ToolRegistry.register_factory("read_file", create_read_file_tool)
    ToolRegistry.register_factory("write_file", create_write_file_tool)
    ToolRegistry.register_factory("list_directory", create_list_directory_tool)
    ToolRegistry.register_factory("search_files", create_search_files_tool)
    ToolRegistry.register_factory("search_code", create_search_code_tool)
    ToolRegistry.register_factory("get_file_tree", create_get_file_tree_tool)
    ToolRegistry.register_factory("delete_file", create_delete_file_tool)
    ToolRegistry.register_factory("create_directory", create_create_directory_tool)
    
    logger.debug("Registered file tools: read_file, write_file, list_directory, "
                 "search_files, search_code, get_file_tree, delete_file, create_directory")
