"""
LangChain integration for Myrmec Agent SDK.

Provides LangChainExecutor - a batteries-included TaskExecutor that uses
LangChain for LLM-based task execution with tool support.

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
    )
    
    agent = Agent(
        engine_url="http://localhost:8080",
        registration_key="myr_agent_...",
        executor=executor,
    )
"""

from myrmec.agent.langchain.executor import LangChainExecutor

__all__ = ["LangChainExecutor"]
