"""
LangChain-based TaskExecutor implementation.
"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import TYPE_CHECKING, Any, Sequence

from myrmec.agent.context import TaskContext
from myrmec.agent.executor import TaskExecutor
from myrmec.agent.models import Task, TaskResult
from myrmec.agent.retry import RateLimitRetryConfig, retry_on_rate_limit

# LangChain imports - optional dependency
try:
    from langchain_core.language_models import BaseChatModel
    from langchain_core.messages import (
        AIMessage,
        BaseMessage,
        HumanMessage,
        SystemMessage,
        ToolMessage,
    )
    from langchain_core.tools import BaseTool
    LANGCHAIN_AVAILABLE = True
except ImportError:
    LANGCHAIN_AVAILABLE = False
    BaseChatModel = Any
    BaseTool = Any
    BaseMessage = Any

if TYPE_CHECKING:
    from langchain_core.language_models import BaseChatModel
    from langchain_core.tools import BaseTool

logger = logging.getLogger(__name__)


class LangChainExecutor(TaskExecutor):
    """
    TaskExecutor that uses LangChain for LLM-based task execution.
    
    Features:
    - Automatic tool binding and execution
    - Tool call tracking for audit
    - Progress reporting during execution
    - Cancellation support
    
    Usage:
        from langchain_openai import ChatOpenAI
        from langchain_core.tools import tool
        
        @tool
        def search(query: str) -> str:
            '''Search for information.'''
            return "search results..."
        
        executor = LangChainExecutor(
            model=ChatOpenAI(model="gpt-4"),
            tools=[search],
            system_prompt="You are a helpful assistant.",
        )
        
    The executor can also dynamically create models from task.model credentials:
    
        # No model in constructor - uses task.model credentials
        executor = LangChainExecutor(tools=[search])
    """
    
    # Map provider codes to LangChain model classes
    _PROVIDER_MODELS: dict[str, type] = {}
    
    # OpenAI-compatible providers (use ChatOpenAI with custom base_url)
    _OPENAI_COMPATIBLE_PROVIDERS = {
        "github_models", "azure_openai", "openrouter", "together_ai", "groq", "fireworks",
        "generic", "ollama", "localai", "vllm", "tgi", "llama_cpp"
    }

    # OpenAI-compatible providers that commonly run without auth in local/dev setups.
    _NO_AUTH_PROVIDERS = {"generic", "ollama", "localai", "vllm", "tgi", "llama_cpp"}
    
    @classmethod
    def _init_provider_models(cls) -> None:
        """Lazily initialize provider model mapping."""
        if cls._PROVIDER_MODELS:
            return
        
        try:
            from langchain_openai import ChatOpenAI
            cls._PROVIDER_MODELS["openai"] = ChatOpenAI
            # OpenAI-compatible providers also use ChatOpenAI
            for provider in cls._OPENAI_COMPATIBLE_PROVIDERS:
                cls._PROVIDER_MODELS[provider] = ChatOpenAI
        except ImportError:
            pass
        
        try:
            from langchain_anthropic import ChatAnthropic
            cls._PROVIDER_MODELS["anthropic"] = ChatAnthropic
        except ImportError:
            pass
        
        try:
            from langchain_google_genai import ChatGoogleGenerativeAI
            cls._PROVIDER_MODELS["google"] = ChatGoogleGenerativeAI
            cls._PROVIDER_MODELS["google_ai"] = ChatGoogleGenerativeAI
        except ImportError:
            pass
    
    def __init__(
        self,
        model: "BaseChatModel | None" = None,
        tools: Sequence["BaseTool"] | None = None,
        system_prompt: str | None = None,
        max_iterations: int = 25,
        workspace_path: str | Path | None = None,
        rate_limit_config: RateLimitRetryConfig | None = None,
    ):
        """
        Initialize LangChain executor.
        
        Args:
            model: LangChain chat model. If None, model is created from task.model.
            tools: Optional list of LangChain tools.
            system_prompt: Optional system prompt for the model (fallback if task has none).
            max_iterations: Maximum tool call iterations to prevent infinite loops.
            workspace_path: Optional workspace path for file tools. If not provided,
                file tools will be disabled unless workspace is provided via task context.
            rate_limit_config: Backoff policy applied when the model provider returns a
                429 / quota error. Defaults to :class:`RateLimitRetryConfig` defaults.
        """
        if not LANGCHAIN_AVAILABLE:
            raise ImportError(
                "LangChain is not installed. Install with: "
                "pip install myrmec-agent[langchain]"
            )
        
        self._init_provider_models()
        
        self._default_model = model
        self._tools = list(tools) if tools else []
        self._system_prompt = system_prompt
        self._max_iterations = max_iterations
        self._workspace_path = Path(workspace_path) if workspace_path else None
        self._rate_limit_config = rate_limit_config or RateLimitRetryConfig()
        
        # Build tool lookup for constructor-provided tools
        self._tool_map: dict[str, BaseTool] = {
            tool.name: tool for tool in self._tools
        }
    
    def _get_model_for_task(self, task: Task) -> "BaseChatModel":
        """
        Get the model to use for a task.
        
        Prioritizes task.model credentials, falls back to constructor model.
        """
        if task.model:
            # Create model from task credentials
            provider = task.model.provider.lower()
            model_cls = self._PROVIDER_MODELS.get(provider)
            
            if not model_cls:
                if self._default_model:
                    logger.warning(
                        "Unknown provider '%s', using default model", provider
                    )
                    return self._default_model
                raise ValueError(f"Unknown provider '{provider}' and no default model")

            if provider not in self._NO_AUTH_PROVIDERS and not task.model.api_key:
                if self._default_model:
                    logger.warning(
                        "Provider '%s' requires credentials but none were supplied; using default model",
                        provider,
                    )
                    return self._default_model
                raise ValueError(
                    f"Provider '{provider}' requires credentials but task.model.api_key is empty"
                )
            
            # Build model kwargs. Disable the provider SDK's own retry loop —
            # we own retries via ``retry_on_rate_limit`` so we can report each
            # wait through the task's observability channel and honour the
            # task-level total-wait budget.
            kwargs: dict[str, Any] = {
                "model": task.model.model_id,
                "max_retries": 0,
            }

            if task.model.api_key:
                kwargs["api_key"] = task.model.api_key
            elif provider in self._NO_AUTH_PROVIDERS:
                # Local OpenAI-compatible servers like Ollama don't require auth,
                # but ChatOpenAI still expects an API key value.
                kwargs["api_key"] = "ollama-local"
            
            # Add endpoint if provided - handle per-provider differences
            if task.model.api_endpoint:
                if provider == "openai" or provider in self._OPENAI_COMPATIBLE_PROVIDERS:
                    kwargs["openai_api_base"] = task.model.api_endpoint
                elif provider == "anthropic":
                    kwargs["anthropic_api_url"] = task.model.api_endpoint
            
            # Add inference parameters
            if task.model.parameters:
                for key, value in task.model.parameters.items():
                    # Convert camelCase to snake_case
                    snake_key = "".join(
                        f"_{c.lower()}" if c.isupper() else c for c in key
                    ).lstrip("_")
                    kwargs[snake_key] = value
            
            return model_cls(**kwargs)
        
        if self._default_model:
            return self._default_model
        
        raise ValueError(
            "No model available. Provide model in constructor or ensure task.model has credentials."
        )
    
    async def _resolve_workspace_path(self, task: Task, context: TaskContext) -> Path | None:
        """
        Resolve workspace path for file tools.
        
        Priority:
        1. Constructor-provided workspace_path (if set)
        2. task.context.workspace (clone repo to temp)
        3. MYRMEC_WORKSPACE_PATH env var
        4. None (file tools will be disabled)
        """
        import os
        import tempfile
        
        # 1. Constructor-provided path takes precedence
        if self._workspace_path:
            return self._workspace_path
        
        # 2. Clone from task.context.workspace if available
        if task.context and task.context.workspace:
            ws = task.context.workspace
            await context.log_info(f"Cloning workspace from {ws.repo_url} (branch: {ws.branch})")
            
            try:
                import subprocess
                
                # Build authenticated URL if token provided
                clone_url = self._build_auth_url(ws.repo_url, ws.repo_token)
                
                # Clone to temp/myrmec folder
                workspace_dir = Path(tempfile.gettempdir()) / "myrmec"
                
                if workspace_dir.exists():
                    # Pull latest changes
                    await context.log_debug("Workspace exists, pulling latest changes")
                    
                    # Update remote URL if token provided (for subsequent fetches/pushes)
                    if ws.repo_token:
                        subprocess.run(
                            ["git", "remote", "set-url", "origin", clone_url],
                            cwd=workspace_dir,
                            capture_output=True,
                            timeout=30,
                        )
                    
                    # Unshallow if previously cloned with --depth 1
                    shallow_file = workspace_dir / ".git" / "shallow"
                    if shallow_file.exists():
                        await context.log_debug("Unshallowing existing workspace")
                        subprocess.run(
                            ["git", "fetch", "--unshallow"],
                            cwd=workspace_dir,
                            capture_output=True,
                            timeout=120,
                        )
                    
                    # Fetch all remote branches
                    subprocess.run(
                        ["git", "fetch", "origin"],
                        cwd=workspace_dir,
                        capture_output=True,
                        timeout=60,
                    )
                    
                    # Checkout the feature branch
                    await self._checkout_branch(workspace_dir, ws.branch, context)
                else:
                    # Clone fresh — clone default branch first, then checkout feature branch
                    workspace_dir.parent.mkdir(parents=True, exist_ok=True)
                    await context.log_debug(f"Cloning to {workspace_dir}")
                    result = subprocess.run(
                        ["git", "clone", clone_url, str(workspace_dir)],
                        capture_output=True,
                        text=True,
                        timeout=120,
                    )
                    if result.returncode != 0:
                        # Redact token from error message
                        error_msg = result.stderr
                        if ws.repo_token and ws.repo_token in error_msg:
                            error_msg = error_msg.replace(ws.repo_token, "***")
                        await context.log_error(f"Git clone failed: {error_msg}")
                        return None
                    
                    # Now checkout the feature branch (creates if needed)
                    await self._checkout_branch(workspace_dir, ws.branch, context)
                
                # Configure git user identity for commits
                subprocess.run(
                    ["git", "config", "user.name", "Myrmec Agent"],
                    cwd=workspace_dir,
                    capture_output=True,
                    timeout=10,
                )
                subprocess.run(
                    ["git", "config", "user.email", "agent@myrmec.ai"],
                    cwd=workspace_dir,
                    capture_output=True,
                    timeout=10,
                )
                
                # Apply subPath if specified
                if ws.sub_path:
                    workspace_dir = workspace_dir / ws.sub_path
                
                await context.log_info(f"Workspace ready: {workspace_dir}")
                return workspace_dir
                
            except subprocess.TimeoutExpired:
                await context.log_error("Git operation timed out")
                return None
            except FileNotFoundError:
                await context.log_error("Git not installed or not in PATH")
                return None
            except Exception as e:
                await context.log_error(f"Failed to setup workspace: {e}")
                return None
        
        # 3. Environment variable fallback
        env_path = os.environ.get("MYRMEC_WORKSPACE_PATH")
        if env_path:
            return Path(env_path)
        
        return None
    
    def _build_auth_url(self, repo_url: str, token: str | None) -> str:
        """
        Build authenticated git URL by injecting token.
        
        Supports HTTPS URLs in these formats:
        - https://github.com/user/repo.git
        - https://gitlab.com/user/repo.git
        - https://bitbucket.org/user/repo.git
        
        The token is injected as: https://x-access-token:TOKEN@host/path
        
        Args:
            repo_url: Original repository URL (HTTPS).
            token: Authentication token (PAT, deploy key, etc.).
            
        Returns:
            URL with embedded credentials, or original URL if no token.
        """
        if not token:
            return repo_url
        
        # Parse and inject token into HTTPS URLs
        from urllib.parse import urlparse, urlunparse
        
        parsed = urlparse(repo_url)
        if parsed.scheme in ("http", "https"):
            # Use x-access-token as username (works for GitHub, GitLab, Bitbucket)
            auth_netloc = f"x-access-token:{token}@{parsed.hostname}"
            if parsed.port:
                auth_netloc += f":{parsed.port}"
            
            authenticated = parsed._replace(netloc=auth_netloc)
            return urlunparse(authenticated)
        
        # SSH URLs don't use token-based auth
        return repo_url
    
    async def _checkout_branch(
        self,
        workspace_dir: Path,
        branch: str,
        context: TaskContext,
    ) -> None:
        """
        Checkout a branch, creating it if it doesn't exist remotely.
        
        For feature branches (myrmec/*), creates from current HEAD if not on remote.
        For existing remote branches, checks them out and pulls latest.
        """
        import subprocess
        
        # Check if branch exists on remote
        result = subprocess.run(
            ["git", "ls-remote", "--heads", "origin", branch],
            cwd=workspace_dir,
            capture_output=True,
            text=True,
            timeout=30,
        )
        
        branch_exists_remote = bool(result.stdout.strip())
        
        if branch_exists_remote:
            # Branch exists remotely — checkout and track
            await context.log_debug(f"Checking out existing remote branch: {branch}")
            subprocess.run(
                ["git", "checkout", branch],
                cwd=workspace_dir,
                capture_output=True,
                timeout=30,
            )
            subprocess.run(
                ["git", "pull", "--ff-only", "origin", branch],
                cwd=workspace_dir,
                capture_output=True,
                timeout=60,
            )
        else:
            # Branch doesn't exist — create from current HEAD and push
            await context.log_info(f"Creating feature branch: {branch}")
            subprocess.run(
                ["git", "checkout", "-b", branch],
                cwd=workspace_dir,
                capture_output=True,
                timeout=30,
            )
            subprocess.run(
                ["git", "push", "-u", "origin", branch],
                cwd=workspace_dir,
                capture_output=True,
                timeout=60,
            )

    def _get_tools_for_task(
        self, 
        task: Task, 
        workspace_path: Path | None,
    ) -> tuple[list["BaseTool"], dict[str, "BaseTool"]]:
        """
        Get tools for a task by merging constructor tools with task.tools from registry.
        
        Priority:
        1. Constructor-provided tools (always included)
        2. Task tools from ToolRegistry (only if tool exists in registry)
        
        Args:
            task: Task containing tool definitions from Engine.
            workspace_path: Resolved workspace path for file tools.
            
        Returns:
            Tuple of (tools list, tool_map dict).
        """
        # Start with constructor tools
        tools: list[BaseTool] = list(self._tools)
        tool_map: dict[str, BaseTool] = dict(self._tool_map)
        
        # Add task tools from registry if available
        if task.tools:
            try:
                from myrmec.agent.tools import ToolRegistry
                
                # Get tool names from task that we don't already have
                task_tool_names = [t.name for t in task.tools if t.name not in tool_map]
                
                if task_tool_names:
                    # Get tools from registry (with workspace path for file tools)
                    registry_tools = ToolRegistry.get_tools(task_tool_names, workspace_path)
                    
                    for tool in registry_tools:
                        if tool.name not in tool_map:
                            tools.append(tool)
                            tool_map[tool.name] = tool
                            
            except ImportError:
                # Tools module not available - continue with constructor tools only
                logger.debug("ToolRegistry not available, using constructor tools only")
        
        return tools, tool_map
    
    async def execute(self, task: Task, context: TaskContext) -> TaskResult:
        """
        Execute task using LangChain model.
        
        Prompt construction:
        - System: task.system_prompt (from Agent Profile) + knowledge context
        - User: task.step_prompt (from Workflow Step) + task.input
        
        Returns TaskResult with 'response' containing the final output.
        """
        await context.log_info(f"Starting LangChain execution for step: {task.step_name}")
        
        # Debug: Log model info received from engine
        if task.model:
            key = task.model.api_key
            key_preview = f"{key[:4]}...{key[-4:]}" if key and len(key) > 8 else ("(empty)" if not key else key)
            await context.log_info(
                f"Task model: provider={task.model.provider}, "
                f"modelId={task.model.model_id}, "
                f"endpoint={task.model.api_endpoint}, "
                f"apiKey={key_preview}"
            )
        else:
            await context.log_info("Task model: None (will use default if available)")
        
        # Get model for this task (uses task.model credentials or falls back to default)
        model = self._get_model_for_task(task)
        if task.model and task.model.api_key:
            await context.log_info(f"Using model {task.model.model_id} from {task.model.provider}")
        
        # Resolve workspace path (may clone repo from task.context.workspace)
        workspace_path = await self._resolve_workspace_path(task, context)
        if workspace_path:
            await context.log_debug(f"Workspace path: {workspace_path}")
        
        # Get tools for this task (merges constructor tools with task.tools from registry)
        task_tools, task_tool_map = self._get_tools_for_task(task, workspace_path)
        
        # Bind tools to model if available
        model_with_tools = model.bind_tools(task_tools) if task_tools else model
        
        # Log prompt configuration
        if task.system_prompt:
            await context.log_debug(f"Using system prompt from agent profile ({len(task.system_prompt)} chars)")
        if task.step_prompt:
            await context.log_debug(f"Using step prompt from workflow ({len(task.step_prompt)} chars)")
        if task.context and task.context.knowledge:
            await context.log_debug(
                f"Loaded {len(task.context.knowledge)} knowledge documents "
                f"({task.context.knowledge_char_count} chars)"
            )
        
        # Debug: Log task input
        await context.log_debug(f"Task input keys: {list(task.input.keys()) if task.input else 'empty'}")
        if task.input:
            input_preview = str(task.input)[:500]
            await context.log_debug(f"Task input: {input_preview}")
        
        # Build initial messages
        messages = self._build_messages(task)
        await context.log_debug(f"Built {len(messages)} messages for LLM")
        
        # Debug: Log user message content
        for i, msg in enumerate(messages):
            msg_type = type(msg).__name__
            content_preview = msg.content[:300] + "..." if len(msg.content) > 300 else msg.content
            await context.log_debug(f"Message {i} ({msg_type}): {content_preview}")
        
        # Log available tools
        if task_tools:
            await context.log_info(f"Available tools: {', '.join(task_tool_map.keys())}")
        
        # Run agent loop
        iteration = 0
        while iteration < self._max_iterations:
            if context.is_cancelled:
                await context.log_info("Task cancelled, stopping execution")
                return TaskResult(output={"cancelled": True})
            
            iteration += 1
            progress = min(90, iteration * 10)
            await context.report_progress(progress, f"Iteration {iteration}")
            
            # Invoke model
            await context.log_debug(f"Invoking model (iteration {iteration})")
            model_name = task.model.model_id if task.model else None

            async def _on_rate_limit_retry(attempt: int, delay: float, exc: BaseException) -> None:
                await context.log_warn(
                    f"Rate limited by model provider (attempt {attempt}/{self._rate_limit_config.max_attempts}), "
                    f"retrying in {delay:.1f}s: {exc}"
                )

            async with context.model_call(model=model_name) as call_ctx:
                response = await retry_on_rate_limit(
                    lambda: self._invoke_model(model_with_tools, messages),
                    config=self._rate_limit_config,
                    on_retry=_on_rate_limit_retry,
                )
                self._capture_usage(response, call_ctx, model_name)
            
            # Check for tool calls
            if hasattr(response, "tool_calls") and response.tool_calls:
                await context.log_info(f"Model requested {len(response.tool_calls)} tool call(s)")
                
                # Add AI message to history
                messages.append(response)
                
                # Execute tool calls
                for tool_call in response.tool_calls:
                    if context.is_cancelled:
                        break
                    
                    tool_result = await self._execute_tool_call(
                        tool_call, task, context, task_tool_map
                    )
                    messages.append(tool_result)
            else:
                # No tool calls - we have final response
                await context.log_info("Model returned final response")
                
                content = response.content if hasattr(response, "content") else str(response)
                
                # Log response preview for debugging
                preview = content[:500] + "..." if len(content) > 500 else content
                await context.log_debug(f"Response content ({len(content)} chars): {preview}")
                
                await context.report_progress(100, "Complete")
                return TaskResult(output={
                    "response": content,
                    "iterations": iteration,
                })
        
        # Max iterations reached
        await context.log_warn(f"Max iterations ({self._max_iterations}) reached")
        
        # Get last response content if available
        last_content = ""
        for msg in reversed(messages):
            if hasattr(msg, "content") and msg.content:
                last_content = msg.content
                break
        
        return TaskResult(output={
            "response": last_content,
            "iterations": iteration,
            "maxIterationsReached": True,
        })
    
    def _build_messages(self, task: Task) -> list["BaseMessage"]:
        """Build message list from task input."""
        messages: list[BaseMessage] = []
        
        # Build combined system prompt
        system_parts = []
        
        # 1. Task system prompt from Agent Profile (primary expertise/personality)
        if task.system_prompt:
            system_parts.append(task.system_prompt)
        
        # 2. Fallback to configured system prompt if task has none
        if not task.system_prompt and self._system_prompt:
            system_parts.append(self._system_prompt)
        
        # 3. Add knowledge context if present (standards, requirements)
        if task.context and task.context.knowledge:
            knowledge_section = task.context.compile_system_prompt_section()
            if knowledge_section:
                system_parts.append(
                    "\n\n# Project Context\n\n"
                    "The following information provides project standards, "
                    "requirements, and instructions you should follow:\n\n"
                    + knowledge_section
                )
        
        # Add combined system message
        if system_parts:
            messages.append(SystemMessage(content="\n\n".join(system_parts)))
        
        # Build user prompt
        user_parts = []
        
        # 4. Step prompt from workflow (task-specific instructions)
        if task.step_prompt:
            user_parts.append(task.step_prompt)
        
        # 5. Add input data
        if "messages" in task.input:
            # If input contains chat messages, add step_prompt first then messages
            if user_parts:
                messages.append(HumanMessage(content="\n\n".join(user_parts)))
                user_parts = []
            
            for msg in task.input["messages"]:
                role = msg.get("role", "user")
                content = msg.get("content", "")
                
                if role == "system":
                    messages.append(SystemMessage(content=content))
                elif role == "assistant":
                    messages.append(AIMessage(content=content))
                else:
                    messages.append(HumanMessage(content=content))
        elif task.input:
            # Add prompt if present
            if "prompt" in task.input:
                user_parts.append(task.input["prompt"])
            
            # Add other input data (excluding prompt which is already added)
            other_input = {k: v for k, v in task.input.items() if k != "prompt"}
            if other_input:
                user_parts.append(f"\n## Input Data\n```json\n{json.dumps(other_input, indent=2)}\n```")
        
        # Add combined user message
        if user_parts:
            messages.append(HumanMessage(content="\n\n".join(user_parts)))
        
        return messages
    
    async def _invoke_model(
        self, model: "BaseChatModel", messages: list["BaseMessage"]
    ) -> "AIMessage":
        """Invoke the model asynchronously."""
        # Use ainvoke if available, otherwise run in executor
        if hasattr(model, "ainvoke"):
            return await model.ainvoke(messages)
        else:
            loop = asyncio.get_event_loop()
            return await loop.run_in_executor(None, model.invoke, messages)

    @staticmethod
    def _capture_usage(response: Any, call_ctx: Any, model_name: str | None) -> None:
        """Extract token usage from a LangChain response into the model-call tracker.

        Tries the modern ``usage_metadata`` attribute (set by LangChain >=0.2
        for OpenAI, Anthropic, Bedrock, etc.), then falls back to provider
        specific ``response_metadata`` shapes.
        """
        try:
            prompt = completion = total = None
            usage = getattr(response, "usage_metadata", None)
            if isinstance(usage, dict):
                prompt = usage.get("input_tokens")
                completion = usage.get("output_tokens")
                total = usage.get("total_tokens")
            if prompt is None and completion is None:
                metadata = getattr(response, "response_metadata", None) or {}
                token_usage = metadata.get("token_usage") or metadata.get("usage") or {}
                prompt = prompt if prompt is not None else token_usage.get("prompt_tokens") or token_usage.get("input_tokens")
                completion = completion if completion is not None else token_usage.get("completion_tokens") or token_usage.get("output_tokens")
                total = total if total is not None else token_usage.get("total_tokens")
            if any(v is not None for v in (prompt, completion, total)):
                call_ctx.set_usage(
                    prompt_tokens=prompt,
                    completion_tokens=completion,
                    total_tokens=total,
                    model=model_name,
                )
        except Exception:  # noqa: BLE001 — observability must never break execution
            logger.debug("Failed to capture token usage", exc_info=True)
    
    async def _execute_tool_call(
        self,
        tool_call: dict[str, Any],
        task: Task,
        context: TaskContext,
        tool_map: dict[str, "BaseTool"],
    ) -> "ToolMessage":
        """Execute a single tool call and return result message."""
        tool_name = tool_call.get("name", "unknown")
        tool_id = tool_call.get("id", "")
        tool_args = tool_call.get("args", {})
        
        await context.log_debug(f"Executing tool: {tool_name}")
        
        # Track tool call
        async with await context.track_tool_call(tool_name, tool_args) as tracker:
            try:
                # Get tool
                tool = tool_map.get(tool_name)
                if not tool:
                    error_msg = f"Unknown tool: {tool_name}"
                    tracker.set_error(error_msg)
                    return ToolMessage(
                        content=error_msg,
                        tool_call_id=tool_id,
                    )
                
                # Execute tool
                if hasattr(tool, "ainvoke"):
                    result = await tool.ainvoke(tool_args)
                else:
                    loop = asyncio.get_event_loop()
                    result = await loop.run_in_executor(
                        None, tool.invoke, tool_args
                    )
                
                # Convert result to string
                if isinstance(result, str):
                    result_str = result
                else:
                    result_str = json.dumps(result)
                
                tracker.set_output({"result": result_str})
                
                return ToolMessage(
                    content=result_str,
                    tool_call_id=tool_id,
                )
                
            except Exception as e:
                error_msg = f"Tool error: {e}"
                tracker.set_error(str(e))
                await context.log_error(f"Tool {tool_name} failed: {e}")
                
                return ToolMessage(
                    content=error_msg,
                    tool_call_id=tool_id,
                )
    
    async def on_cancel(self, task: Task, reason: str) -> None:
        """Handle task cancellation."""
        logger.info(f"LangChain execution cancelled for task {task.task_id}: {reason}")
        # Cancellation is handled via context.is_cancelled check in execute loop
