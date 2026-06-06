"""
Myrmec Agent Tools - Built-in tools for task execution.

This module provides a registry of built-in tools that can be selected
based on tool definitions from the Engine.

Usage:
    from myrmec.agent.tools import ToolRegistry, create_tools_for_task

    # Get all registered tools by name
    tools = ToolRegistry.get_tools(["read_file", "write_file", "list_directory"])

    # Or create tools configured for a specific workspace
    tools = create_tools_for_task(task, workspace_path="/path/to/workspace")
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any, Callable

if TYPE_CHECKING:
    from pathlib import Path
    from langchain_core.tools import BaseTool
    from myrmec.agent.models import Task, ToolDefinition

logger = logging.getLogger(__name__)


class ToolRegistry:
    """
    Registry of built-in tools.
    
    Tools are registered by name and can be retrieved by name.
    Factory functions can create tools configured with context (e.g., workspace path).
    """
    
    # Static registry: name -> tool factory function
    # Factory signature: (workspace_path: Path | None) -> BaseTool
    _factories: dict[str, Callable[[Any], "BaseTool"]] = {}
    
    # Singleton tools (no context needed)
    _static_tools: dict[str, "BaseTool"] = {}
    
    @classmethod
    def register_static(cls, tool: "BaseTool") -> None:
        """Register a static tool (no context needed)."""
        cls._static_tools[tool.name] = tool
        logger.debug("Registered static tool: %s", tool.name)
    
    @classmethod
    def register_factory(
        cls,
        name: str,
        factory: Callable[[Any], "BaseTool"],
    ) -> None:
        """
        Register a tool factory.
        
        Args:
            name: Tool name (must match Engine tool definition).
            factory: Factory function that takes workspace_path and returns a tool.
        """
        cls._factories[name] = factory
        logger.debug("Registered tool factory: %s", name)
    
    @classmethod
    def get_tool(
        cls,
        name: str,
        workspace_path: "Path | None" = None,
    ) -> "BaseTool | None":
        """
        Get a tool by name.
        
        Args:
            name: Tool name.
            workspace_path: Optional workspace path for tools that need it.
            
        Returns:
            Tool instance or None if not found.
        """
        # Check static tools first
        if name in cls._static_tools:
            return cls._static_tools[name]
        
        # Check factories
        if name in cls._factories:
            return cls._factories[name](workspace_path)
        
        return None
    
    @classmethod
    def get_tools(
        cls,
        names: list[str],
        workspace_path: "Path | None" = None,
    ) -> list["BaseTool"]:
        """
        Get multiple tools by name.
        
        Args:
            names: List of tool names.
            workspace_path: Optional workspace path for tools that need it.
            
        Returns:
            List of tool instances (excludes tools not found).
        """
        tools = []
        for name in names:
            tool = cls.get_tool(name, workspace_path)
            if tool:
                tools.append(tool)
            else:
                # Debug level - not all server-side tool codes have agent implementations
                logger.debug("Tool not implemented in agent registry: %s", name)
        return tools
    
    @classmethod
    def list_available(cls) -> list[str]:
        """List all available tool names."""
        return sorted(set(cls._static_tools.keys()) | set(cls._factories.keys()))


def create_tools_for_task(
    task: "Task",
    workspace_path: "Path | str | None" = None,
    include_names: list[str] | None = None,
) -> list["BaseTool"]:
    """
    Create tools for a task based on task.tools definitions.
    
    Args:
        task: Task containing tool definitions from Engine.
        workspace_path: Workspace path for file tools.
        include_names: If provided, only include these tools (ignore task.tools).
        
    Returns:
        List of LangChain tools ready for execution.
    """
    from pathlib import Path
    
    # Convert workspace_path to Path
    ws_path: Path | None = None
    if workspace_path:
        ws_path = Path(workspace_path) if isinstance(workspace_path, str) else workspace_path
    
    # Determine which tools to include
    if include_names:
        tool_names = include_names
    elif task.tools:
        tool_names = [t.name for t in task.tools]
    else:
        # No tools specified - return empty
        return []
    
    return ToolRegistry.get_tools(tool_names, ws_path)


def get_tool_names_from_definitions(definitions: list["ToolDefinition"]) -> list[str]:
    """Extract tool names from ToolDefinition list."""
    return [d.name for d in definitions]


# Import and register built-in tools when module is loaded
def _register_builtin_tools() -> None:
    """Register all built-in tools."""
    try:
        from myrmec.agent.tools import file_tools
        file_tools.register_all()
    except ImportError as e:
        logger.debug("Could not register file tools: %s", e)
    
    try:
        from myrmec.agent.tools import git_tools
        git_tools.register_all()
    except ImportError as e:
        logger.debug("Could not register git tools: %s", e)


# Auto-register on import
_register_builtin_tools()
