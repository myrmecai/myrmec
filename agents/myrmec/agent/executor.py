"""
TaskExecutor abstract base class.

Developers extend this class to implement custom task execution logic.
"""

from abc import ABC, abstractmethod

from myrmec.agent.models import Task, TaskResult


class TaskExecutor(ABC):
    """
    Abstract base class for task execution.
    
    Extend this class to implement custom task execution logic.
    The SDK handles connectivity, protocol, and lifecycle - your executor
    focuses only on the business logic of executing tasks.
    
    Example:
        class MyExecutor(TaskExecutor):
            async def execute(self, task: Task, context: TaskContext) -> TaskResult:
                await context.log_info(f"Processing step: {task.step_name}")
                
                # Your task logic here
                result = await do_something(task.input)
                
                await context.report_progress(50, "Halfway done")
                
                # More processing...
                
                return TaskResult(output={"result": result})
    """
    
    @abstractmethod
    async def execute(self, task: "Task", context: "TaskContext") -> "TaskResult":
        """
        Execute a task and return the result.
        
        This method is called by the Agent when a task is assigned and accepted.
        Use the context to report progress, log messages, and track tool calls.
        
        Args:
            task: The task to execute, containing input data and configuration.
            context: Context object for logging, progress updates, and tool tracking.
        
        Returns:
            TaskResult containing the output data.
        
        Raises:
            Exception: Any exception will be caught and reported as task failure.
        """
        ...
    
    async def on_cancel(self, task: "Task", reason: str) -> None:
        """
        Called when a task is cancelled by the Engine.
        
        Override this method to implement cancellation logic (e.g., cleanup,
        stopping long-running operations).
        
        The default implementation does nothing - the task will simply be
        interrupted and marked as cancelled.
        
        Args:
            task: The task being cancelled.
            reason: Reason for cancellation.
        """
        pass
    
    async def on_startup(self) -> None:
        """
        Called when the agent starts up (after WebSocket connection).
        
        Override to initialize resources needed for task execution.
        """
        pass
    
    async def on_shutdown(self) -> None:
        """
        Called when the agent shuts down.
        
        Override to cleanup resources.
        """
        pass


# Import TaskContext here to avoid circular imports at module level
from myrmec.agent.context import TaskContext  # noqa: E402
