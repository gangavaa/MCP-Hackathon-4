/**
 * The MCP server: how Claude reaches NMS. A gateway in front of the NMS REST API — it holds no data and runs no SQL;
 * every tool is a call to the API with the caller's own token ({@link com.nms.dashboard.mcp.api.NmsApiClient}).
 *
 * <ul>
 *   <li>{@link com.nms.dashboard.mcp.McpConfig} — the server and its Streamable HTTP transport at {@code /mcp}
 *   <li>{@link com.nms.dashboard.mcp.McpTools} — the tools, worded for the model; schemas in {@code resources/mcp/}
 *   <li>{@link com.nms.dashboard.mcp.McpPanel} — the chat panel (the MCP App) as a resource
 *   <li>{@link com.nms.dashboard.mcp.ChartImageRenderer} — a PNG of each chart, for hosts that cannot frame the panel
 *   <li>{@code api/} — the REST client, the parts of the API's JSON this server reads, and its errors
 *   <li>{@code security/} — the MCP authorization flow: token checks, open discovery, the RFC 9728 metadata
 * </ul>
 */
package com.nms.dashboard.mcp;
