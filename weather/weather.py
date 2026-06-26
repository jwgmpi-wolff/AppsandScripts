from mcp import FastMCP

mcp = FastMCP("weather")

@mcp.tool()
def hello() -> str:
    return "Hello from weather via MCP!"