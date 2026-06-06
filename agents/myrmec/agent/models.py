"""
Data models for the Myrmec Agent SDK.
"""

from datetime import datetime
from enum import Enum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, field_validator


class LogLevel(str, Enum):
    """Log levels for agent logging."""
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARN = "WARN"
    ERROR = "ERROR"


class ToolDefinition(BaseModel):
    """Definition of a tool available for task execution."""
    name: str
    description: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    
    @field_validator("parameters", mode="before")
    @classmethod
    def parameters_none_to_empty(cls, v: Any) -> dict[str, Any]:
        return v if v is not None else {}


class ModelInfo(BaseModel):
    """LLM model information for task execution."""
    provider: str
    model_id: str = Field(alias="modelId")
    api_endpoint: str | None = Field(alias="apiEndpoint", default=None)
    api_key: str | None = Field(alias="apiKey", default=None)
    parameters: dict[str, Any] = Field(default_factory=dict)
    
    class Config:
        populate_by_name = True
    
    @field_validator("parameters", mode="before")
    @classmethod
    def parameters_none_to_empty(cls, v: Any) -> dict[str, Any]:
        return v if v is not None else {}


class KnowledgeEntry(BaseModel):
    """A single knowledge document entry from task context."""
    category: str
    name: str
    content: str
    priority: int = 0


class RagConfig(BaseModel):
    """Configuration for external RAG system access."""
    endpoint: str | None = None
    api_key_secret: str | None = Field(alias="apiKeySecret", default=None)
    collection: str | None = None
    top_k: int = Field(alias="topK", default=5)
    
    class Config:
        populate_by_name = True


class WorkspaceConfig(BaseModel):
    """
    Workspace configuration for file-based task execution.
    Agent clones the repo to a local temp directory.
    """
    repo_url: str = Field(alias="repoUrl")
    branch: str = "main"
    sub_path: str | None = Field(alias="subPath", default=None)
    repo_token: str | None = Field(alias="repoToken", default=None)
    
    class Config:
        populate_by_name = True


class KnowledgeContext(BaseModel):
    """
    Knowledge context containing resolved knowledge documents
    and optional RAG configuration for external knowledge retrieval.
    
    This is embedded in Task and provides org/project instructions,
    standards, and requirements to the agent.
    """
    knowledge: list[KnowledgeEntry] = Field(default_factory=list)
    rag: RagConfig | None = None
    knowledge_char_count: int = Field(alias="knowledgeCharCount", default=0)
    workspace: WorkspaceConfig | None = None
    
    class Config:
        populate_by_name = True
    
    def get_knowledge_by_category(self, category: str) -> list[KnowledgeEntry]:
        """Get all knowledge entries for a specific category."""
        return [k for k in self.knowledge if k.category == category]
    
    def get_standards(self) -> list[KnowledgeEntry]:
        """Get all STANDARD category knowledge entries."""
        return self.get_knowledge_by_category("STANDARD")
    
    def get_instructions(self) -> list[KnowledgeEntry]:
        """Get all INSTRUCTION category knowledge entries."""
        return self.get_knowledge_by_category("INSTRUCTION")
    
    def get_requirements(self) -> list[KnowledgeEntry]:
        """Get all REQUIREMENT category knowledge entries."""
        return self.get_knowledge_by_category("REQUIREMENT")
    
    def get_architecture(self) -> list[KnowledgeEntry]:
        """Get all ARCHITECTURE category knowledge entries."""
        return self.get_knowledge_by_category("ARCHITECTURE")
    
    def compile_system_prompt_section(self) -> str:
        """
        Compile knowledge into a section suitable for system prompt injection.
        
        Returns formatted text with all knowledge entries organized by category.
        """
        if not self.knowledge:
            return ""
        
        sections = []
        
        # Group by category
        categories = ["STANDARD", "REQUIREMENT", "ARCHITECTURE", "INSTRUCTION"]
        category_names = {
            "STANDARD": "Standards & Conventions",
            "REQUIREMENT": "Requirements",
            "ARCHITECTURE": "Architecture",
            "INSTRUCTION": "Instructions",
        }
        
        for cat in categories:
            entries = self.get_knowledge_by_category(cat)
            if entries:
                section_title = category_names.get(cat, cat)
                section_content = "\n\n".join(
                    f"### {e.name}\n{e.content}" for e in entries
                )
                sections.append(f"## {section_title}\n\n{section_content}")
        
        return "\n\n".join(sections)


class Task(BaseModel):
    """A task assigned to the agent for execution."""
    task_id: UUID
    workflow_id: UUID
    step_index: int
    step_name: str
    system_prompt: str | None = None
    step_prompt: str | None = None
    input: dict[str, Any]
    tools: list[ToolDefinition] = Field(default_factory=list)
    timeout_seconds: int = 300
    model: ModelInfo | None = None
    context: KnowledgeContext | None = None


class TaskResult(BaseModel):
    """Result of a completed task."""
    output: dict[str, Any] = Field(default_factory=dict)
    
    def __init__(self, output: dict[str, Any] | None = None, **data):
        super().__init__(output=output or {}, **data)


class TaskError(BaseModel):
    """Error information for a failed task."""
    error: str
    error_code: str | None = None


# ==================== WebSocket Message Payloads ====================

class TaskAssignPayload(BaseModel):
    """Payload for task.assign message from Engine."""
    task_id: UUID = Field(alias="taskId")
    workflow_id: UUID = Field(alias="workflowId")
    step_index: int = Field(alias="stepIndex")
    step_name: str = Field(alias="stepName")
    system_prompt: str | None = Field(alias="systemPrompt", default=None)
    step_prompt: str | None = Field(alias="stepPrompt", default=None)
    input: dict[str, Any]
    tools: list[ToolDefinition] = Field(default_factory=list)
    timeout_seconds: int = Field(alias="timeoutSeconds", default=300)
    model: ModelInfo | None = None
    context: KnowledgeContext | None = None
    
    class Config:
        populate_by_name = True
    
    def to_task(self) -> Task:
        """Convert to Task model."""
        return Task(
            task_id=self.task_id,
            workflow_id=self.workflow_id,
            step_index=self.step_index,
            step_name=self.step_name,
            system_prompt=self.system_prompt,
            step_prompt=self.step_prompt,
            input=self.input,
            tools=self.tools,
            timeout_seconds=self.timeout_seconds,
            model=self.model,
            context=self.context,
        )


class TaskCancelPayload(BaseModel):
    """Payload for task.cancel message from Engine."""
    task_id: UUID = Field(alias="taskId")
    reason: str
    
    class Config:
        populate_by_name = True


class TaskAcceptPayload(BaseModel):
    """Payload for task.accept message to Engine."""
    task_id: UUID = Field(alias="taskId")
    
    class Config:
        populate_by_name = True


class TaskRejectPayload(BaseModel):
    """Payload for task.reject message to Engine."""
    task_id: UUID = Field(alias="taskId")
    reason: str
    
    class Config:
        populate_by_name = True


class TaskProgressPayload(BaseModel):
    """Payload for task.progress message to Engine."""
    task_id: UUID = Field(alias="taskId")
    progress: int = Field(ge=0, le=100)
    message: str | None = None
    
    class Config:
        populate_by_name = True


class TaskCompletePayload(BaseModel):
    """Payload for task.complete message to Engine."""
    task_id: UUID = Field(alias="taskId")
    result: dict[str, Any]
    
    class Config:
        populate_by_name = True


class TaskFailedPayload(BaseModel):
    """Payload for task.failed message to Engine."""
    task_id: UUID = Field(alias="taskId")
    error: str
    error_code: str | None = Field(alias="errorCode", default=None)
    
    class Config:
        populate_by_name = True


class TokenUsagePayload(BaseModel):
    """Payload for token.usage message to Engine.

    Reports token consumption for a single LLM call.
    """
    task_id: UUID = Field(alias="taskId")
    model: str | None = None
    call_id: str | None = Field(alias="callId", default=None)
    prompt_tokens: int | None = Field(alias="promptTokens", default=None)
    completion_tokens: int | None = Field(alias="completionTokens", default=None)
    total_tokens: int | None = Field(alias="totalTokens", default=None)
    duration_ms: int | None = Field(alias="durationMs", default=None)

    class Config:
        populate_by_name = True


class TaskMetricsPayload(BaseModel):
    """Payload for task.metrics message to Engine.

    Sent once near task completion with agent-side aggregated metrics.
    """
    task_id: UUID = Field(alias="taskId")
    total_duration_ms: int | None = Field(alias="totalDurationMs", default=None)
    model_duration_ms: int | None = Field(alias="modelDurationMs", default=None)
    tool_duration_ms: int | None = Field(alias="toolDurationMs", default=None)
    model_call_count: int | None = Field(alias="modelCallCount", default=None)
    tool_call_count: int | None = Field(alias="toolCallCount", default=None)
    prompt_tokens: int | None = Field(alias="promptTokens", default=None)
    completion_tokens: int | None = Field(alias="completionTokens", default=None)
    total_tokens: int | None = Field(alias="totalTokens", default=None)
    model: str | None = None

    class Config:
        populate_by_name = True


class LogSource(str, Enum):
    """Source of log messages."""
    TASK = "TASK"      # Task execution logs (ctx.log_*)
    AGENT = "AGENT"    # Python logging module output
    SYSTEM = "SYSTEM"  # stdout/stderr capture


class LogPayload(BaseModel):
    """Payload for log message to Engine."""
    task_id: UUID | None = Field(alias="taskId", default=None)
    level: str
    message: str
    data: dict[str, Any] | None = None
    timestamp: datetime | None = None
    source: str = Field(default="TASK")
    
    class Config:
        populate_by_name = True


class ToolCallPayload(BaseModel):
    """Payload for tool.call message to Engine."""
    task_id: UUID = Field(alias="taskId")
    tool_name: str = Field(alias="toolName")
    call_id: str = Field(alias="callId")
    input: dict[str, Any]
    
    class Config:
        populate_by_name = True


class ToolResultPayload(BaseModel):
    """Payload for tool.result message to Engine."""
    task_id: UUID = Field(alias="taskId")
    call_id: str = Field(alias="callId")
    output: dict[str, Any] | None = None
    duration_ms: int = Field(alias="durationMs")
    error: str | None = None
    
    class Config:
        populate_by_name = True


class DisconnectPayload(BaseModel):
    """Payload for disconnect message to Engine."""
    reason: str
