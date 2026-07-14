#!/usr/bin/env node

// Minimal stdio MCP server used by the integration test. It implements the
// JSON-RPC methods needed by Spring AI's MCP sync client and exposes one tool.
const readline = require('readline');

const weather = {
  '上海': { condition: '晴', temperature: 28, humidity: 62 },
  '常州': { condition: '多云', temperature: 26, humidity: 68 },
  '南京': { condition: '小雨', temperature: 24, humidity: 75 },
  '北京': { condition: '晴', temperature: 30, humidity: 40 },
};

function send(id, result, error) {
  // ASCII-only JSON keeps stdio MCP messages stable on Windows code pages.
  const wire = JSON.stringify({ jsonrpc: '2.0', id, ...(error ? { error } : { result }) })
    .replace(/[\u007f-\uffff]/g, (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`);
  process.stdout.write(wire + '\n');
}

function handle(message) {
  const { id, method, params = {} } = message;
  if (method === 'initialize') {
    return send(id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'enterprise-ai-weather', version: '1.0.0' },
    });
  }
  if (method === 'notifications/initialized') return;
  if (method === 'ping') return send(id, {});
  if (method === 'tools/list') {
    return send(id, {
      tools: [{
        name: 'queryWeather',
        description: '查询示例城市天气。',
        inputSchema: {
          type: 'object',
          properties: { city: { type: 'string', description: '城市名称' } },
          required: ['city'],
        },
      }],
    });
  }
  if (method === 'tools/call') {
    const name = params.name;
    if (name !== 'queryWeather') return send(id, null, { code: -32601, message: 'Unknown tool' });
    const city = params.arguments?.city || '常州';
    const value = weather[city] || { condition: '晴', temperature: 25, humidity: 55 };
    return send(id, { content: [{ type: 'text', text: JSON.stringify({ city, ...value }) }], isError: false });
  }
  if (id !== undefined) return send(id, null, { code: -32601, message: `Unsupported method: ${method}` });
}

readline.createInterface({ input: process.stdin, crlfDelay: Infinity }).on('line', (line) => {
  try { handle(JSON.parse(line)); } catch (error) { /* keep stdout JSON-RPC clean */ }
});
