# Climate MCP Server

> 端口: 9093 | MCP endpoint: `/mcp`

## 工具

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `getStormWarnings` | `state` (US state code) | 获取暴风预警 |
| `getClimateForecast` | `latitude`, `longitude` | 获取气候预报 |

## 数据源

api.weather.gov (NWS API) — 与 weather-server 共享数据源但返回不同维度的信息
