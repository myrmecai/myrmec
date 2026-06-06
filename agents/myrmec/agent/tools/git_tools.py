"""
Git tools for agent task execution.

These tools provide git operations within the workspace directory.
"""

from __future__ import annotations

import logging
import subprocess
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


def _run_git_command(
    workspace: Path,
    args: list[str],
    timeout: int = 60,
) -> tuple[bool, str]:
    """
    Run a git command in the workspace.
    
    Args:
        workspace: Workspace directory (must be a git repo).
        args: Git command arguments (without 'git' prefix).
        timeout: Command timeout in seconds.
        
    Returns:
        Tuple of (success, output/error message).
    """
    try:
        result = subprocess.run(
            ["git"] + args,
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        
        if result.returncode == 0:
            return True, result.stdout.strip()
        else:
            return False, result.stderr.strip() or result.stdout.strip()
            
    except subprocess.TimeoutExpired:
        return False, f"Git command timed out after {timeout} seconds"
    except FileNotFoundError:
        return False, "Git is not installed or not in PATH"
    except Exception as e:
        return False, f"Git command failed: {e}"


def create_git_status_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git status tool."""
    
    @tool
    def git_status() -> str:
        """
        Get the current git status of the workspace.
        Shows modified, staged, and untracked files.
        
        Returns:
            Git status output showing changed files.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        success, output = _run_git_command(workspace_path, ["status", "--short"])
        if success:
            if not output:
                return "Working directory is clean - no changes"
            return f"Git status:\n{output}"
        return f"Error: {output}"
    
    return git_status


def create_git_diff_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git diff tool."""
    
    @tool
    def git_diff(file_path: str = "") -> str:
        """
        Show changes in the working directory or for a specific file.
        
        Args:
            file_path: Optional path to a specific file. If empty, shows all changes.
            
        Returns:
            Diff output showing what changed.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        args = ["diff"]
        if file_path:
            args.append(file_path)
            
        success, output = _run_git_command(workspace_path, args)
        if success:
            if not output:
                return "No differences found"
            # Truncate very long diffs
            if len(output) > 10000:
                return output[:10000] + "\n\n... (diff truncated, full diff too long)"
            return output
        return f"Error: {output}"
    
    return git_diff


def create_git_add_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git add tool."""
    
    @tool
    def git_add(file_paths: str = ".") -> str:
        """
        Stage files for commit.
        
        Args:
            file_paths: Space-separated file paths to stage, or "." for all changes.
            
        Returns:
            Success message or error.
        """
        if not workspace_path:
            return "Error: No workspace configured"
        
        # Split paths and add each
        paths = file_paths.split() if file_paths else ["."]
        
        success, output = _run_git_command(workspace_path, ["add"] + paths)
        if success:
            return f"Staged: {file_paths}"
        return f"Error: {output}"
    
    return git_add


def create_git_commit_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git commit tool."""
    
    @tool
    def git_commit(message: str) -> str:
        """
        Commit staged changes with a message.
        
        Args:
            message: Commit message describing the changes.
            
        Returns:
            Commit result with hash, or error message.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        if not message.strip():
            return "Error: Commit message cannot be empty"
            
        success, output = _run_git_command(
            workspace_path, 
            ["commit", "-m", message]
        )
        if success:
            return f"Committed: {output}"
        return f"Error: {output}"
    
    return git_commit


def create_git_push_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git push tool."""
    
    @tool
    def git_push(remote: str = "origin", branch: str = "") -> str:
        """
        Push commits to remote repository.
        
        Args:
            remote: Remote name (default: origin).
            branch: Branch to push (default: current branch).
            
        Returns:
            Push result or error message.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        args = ["push", remote]
        if branch:
            args.append(branch)
            
        success, output = _run_git_command(workspace_path, args, timeout=120)
        if success:
            return f"Pushed successfully: {output or 'OK'}"
        return f"Error: {output}"
    
    return git_push


def create_git_log_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git log tool."""
    
    @tool
    def git_log(count: int = 10) -> str:
        """
        Show recent commit history.
        
        Args:
            count: Number of commits to show (default: 10, max: 50).
            
        Returns:
            Recent commit log.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        count = min(max(1, count), 50)  # Clamp to 1-50
        
        success, output = _run_git_command(
            workspace_path,
            ["log", f"-{count}", "--oneline", "--decorate"]
        )
        if success:
            return output or "No commits yet"
        return f"Error: {output}"
    
    return git_log


def create_git_branch_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git branch tool."""
    
    @tool
    def git_branch(branch_name: str = "", create: bool = False) -> str:
        """
        List branches or create/switch to a branch.
        
        Args:
            branch_name: Branch name to create or switch to. If empty, lists branches.
            create: If True, create the branch. If False, switch to existing branch.
            
        Returns:
            Branch list, creation result, or switch result.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        if not branch_name:
            # List branches
            success, output = _run_git_command(workspace_path, ["branch", "-a"])
            if success:
                return f"Branches:\n{output}"
            return f"Error: {output}"
        
        if create:
            # Create and switch to new branch
            success, output = _run_git_command(
                workspace_path, 
                ["checkout", "-b", branch_name]
            )
        else:
            # Switch to existing branch
            success, output = _run_git_command(
                workspace_path,
                ["checkout", branch_name]
            )
            
        if success:
            action = "Created and switched to" if create else "Switched to"
            return f"{action} branch: {branch_name}"
        return f"Error: {output}"
    
    return git_branch


def create_git_merge_tool(workspace_path: Path | None) -> "BaseTool":
    """Create git merge tool."""
    
    @tool
    def git_merge(branch: str) -> str:
        """
        Merge a branch into the current branch.
        
        Args:
            branch: Name of the branch to merge into the current branch.
            
        Returns:
            Merge result or error message.
        """
        if not workspace_path:
            return "Error: No workspace configured"
            
        if not branch.strip():
            return "Error: Branch name cannot be empty"
        
        success, output = _run_git_command(
            workspace_path,
            ["merge", branch]
        )
        if success:
            return f"Merged '{branch}' into current branch: {output or 'OK'}"
        return f"Error: {output}"
    
    return git_merge


def register_all() -> None:
    """Register all git tools with the ToolRegistry."""
    if not LANGCHAIN_AVAILABLE:
        logger.warning("LangChain not available, git tools not registered")
        return
    
    from myrmec.agent.tools import ToolRegistry
    
    # Register factory functions for workspace-aware tools
    ToolRegistry.register_factory("git_status", create_git_status_tool)
    ToolRegistry.register_factory("git_diff", create_git_diff_tool)
    ToolRegistry.register_factory("git_add", create_git_add_tool)
    ToolRegistry.register_factory("git_commit", create_git_commit_tool)
    ToolRegistry.register_factory("git_push", create_git_push_tool)
    ToolRegistry.register_factory("git_log", create_git_log_tool)
    ToolRegistry.register_factory("git_branch", create_git_branch_tool)
    ToolRegistry.register_factory("git_merge", create_git_merge_tool)
    
    logger.debug("Registered git tools: git_status, git_diff, git_add, "
                 "git_commit, git_push, git_log, git_branch, git_merge")
