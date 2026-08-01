#!/usr/bin/env node

const https = require('https');
const readline = require('readline');

const httpAgent = new https.Agent({ keepAlive: true, maxSockets: 4 });
const requestTimeoutMs = 8_000;
const retryableStatusCodes = new Set([408, 425, 429, 500, 502, 503, 504]);

function send(id, result, error) {
  const wire = JSON.stringify({
    jsonrpc: '2.0',
    id,
    ...(error ? { error } : { result }),
  }).replace(
    /[\u007f-\uffff]/g,
    (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`,
  );
  process.stdout.write(`${wire}\n`);
}

function requestJson(url) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, {
      agent: httpAgent,
      family: 4,
      timeout: requestTimeoutMs,
      headers: {
        Accept: 'application/json',
        'User-Agent': 'enterprise-ai-cockpit-amap-mcp/1.0',
      },
    }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => {
        if (response.statusCode < 200 || response.statusCode >= 300) {
          const error = new Error(`高德服务 HTTP ${response.statusCode}`);
          error.retryable = retryableStatusCodes.has(response.statusCode);
          reject(error);
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch {
          reject(new Error('高德服务返回了无效 JSON'));
        }
      });
    });
    request.on('timeout', () => request.destroy(new Error('高德服务请求超时')));
    request.on('error', reject);
  });
}

async function amapRequest(path, params) {
  const key = String(process.env.AMAP_MAPS_API_KEY || '').trim();
  if (!key) throw new Error('高德地图凭据未配置');
  const url = new URL(path, 'https://restapi.amap.com');
  url.searchParams.set('key', key);
  Object.entries(params).forEach(([name, value]) => {
    if (value !== undefined && value !== null && String(value).trim()) {
      url.searchParams.set(name, String(value).trim());
    }
  });
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const payload = await requestJson(url);
      if (String(payload.status) !== '1') {
        throw new Error(`高德服务错误：${payload.info || payload.infocode || 'UNKNOWN'}`);
      }
      return payload;
    } catch (error) {
      lastError = error;
      if (!error.retryable || attempt === 3) break;
      await new Promise((resolve) => setTimeout(resolve, 250 * (2 ** (attempt - 1))));
    }
  }
  throw lastError || new Error('高德服务暂时不可用');
}

function normalizeText(value, label, maxLength = 120) {
  const text = String(value || '').trim();
  if (!text) throw new Error(`${label}不能为空`);
  if (text.length > maxLength) throw new Error(`${label}过长`);
  return text;
}

function compactDistrict(district) {
  return {
    name: district.name,
    adcode: district.adcode,
    citycode: Array.isArray(district.citycode) ? district.citycode[0] || '' : district.citycode || '',
    center: district.center,
    level: district.level,
  };
}

function parseDistrictPayload(payload, keyword) {
  const [district] = payload.districts || [];
  if (!district) throw new Error(`未找到行政区：${keyword}`);
  return {
    queryType: 'amap-district',
    keyword,
    ...compactDistrict(district),
    children: (district.districts || []).map(compactDistrict),
    source: '高德地图',
  };
}

async function districtSearch(keywords, subdistrict = 1) {
  const keyword = normalizeText(keywords, '行政区关键词', 80);
  const depth = Math.max(0, Math.min(3, Number(subdistrict) || 1));
  const payload = await amapRequest('/v3/config/district', {
    keywords: keyword,
    subdistrict: depth,
    extensions: 'base',
  });
  return parseDistrictPayload(payload, keyword);
}

async function geocode(address, city) {
  const requestedAddress = normalizeText(address, '地址');
  const payload = await amapRequest('/v3/geocode/geo', {
    address: requestedAddress,
    city,
  });
  return {
    queryType: 'amap-geocode',
    address: requestedAddress,
    geocodes: (payload.geocodes || []).slice(0, 10).map((item) => ({
      formattedAddress: item.formatted_address,
      province: item.province,
      city: item.city,
      district: item.district,
      adcode: item.adcode,
      location: item.location,
      level: item.level,
    })),
    source: '高德地图',
  };
}

async function weather(city) {
  const requestedCity = normalizeText(city, '城市', 80);
  const payload = await amapRequest('/v3/weather/weatherInfo', {
    city: requestedCity,
    extensions: 'all',
  });
  return {
    queryType: 'amap-weather',
    city: requestedCity,
    forecasts: payload.forecasts || [],
    source: '高德地图',
  };
}

async function textSearch(keywords, city) {
  const requestedKeywords = normalizeText(keywords, '搜索关键词', 80);
  const payload = await amapRequest('/v3/place/text', {
    keywords: requestedKeywords,
    city,
    citylimit: city ? 'true' : 'false',
    offset: 10,
    page: 1,
    extensions: 'base',
  });
  return {
    queryType: 'amap-place-search',
    keywords: requestedKeywords,
    city: city || '',
    pois: (payload.pois || []).slice(0, 10).map((poi) => ({
      id: poi.id,
      name: poi.name,
      type: poi.type,
      address: poi.address,
      location: poi.location,
      province: poi.pname,
      city: poi.cityname,
      district: poi.adname,
    })),
    source: '高德地图',
  };
}

const tools = [
  {
    name: 'maps_district',
    description: '查询中国行政区及其下级行政区。适合先解析“某省所有城市”，再把 children 城市交给天气等工具。',
    inputSchema: {
      type: 'object',
      properties: {
        keywords: { type: 'string', description: '行政区名称或 adcode，例如 浙江省、330000' },
        subdistrict: { type: 'integer', description: '返回下级层级，通常为 1', minimum: 0, maximum: 3 },
      },
      required: ['keywords'],
    },
  },
  {
    name: 'maps_geo',
    description: '将结构化地址转换为高德经纬度。',
    inputSchema: {
      type: 'object',
      properties: {
        address: { type: 'string', description: '需要解析的地址' },
        city: { type: 'string', description: '可选城市，用于提升准确度' },
      },
      required: ['address'],
    },
  },
  {
    name: 'maps_weather',
    description: '按单个中国城市名称或 adcode 查询高德天气预报；批量城市天气必须改用 queryWeather，一次传 cities。',
    inputSchema: {
      type: 'object',
      properties: { city: { type: 'string', description: '城市名称或 adcode' } },
      required: ['city'],
    },
  },
  {
    name: 'maps_text_search',
    description: '按关键词和可选城市搜索高德 POI 地点。',
    inputSchema: {
      type: 'object',
      properties: {
        keywords: { type: 'string', description: '地点或类别关键词' },
        city: { type: 'string', description: '可选城市' },
      },
      required: ['keywords'],
    },
  },
];

async function handle(message) {
  const { id, method, params = {} } = message;
  if (method === 'initialize') {
    send(id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'enterprise-ai-amap', version: '1.0.0' },
    });
    return;
  }
  if (method === 'notifications/initialized') return;
  if (method === 'ping') return send(id, {});
  if (method === 'tools/list') return send(id, { tools });
  if (method === 'tools/call') {
    try {
      let value;
      if (params.name === 'maps_district') {
        value = await districtSearch(params.arguments?.keywords, params.arguments?.subdistrict);
      } else if (params.name === 'maps_geo') {
        value = await geocode(params.arguments?.address, params.arguments?.city);
      } else if (params.name === 'maps_weather') {
        value = await weather(params.arguments?.city);
      } else if (params.name === 'maps_text_search') {
        value = await textSearch(params.arguments?.keywords, params.arguments?.city);
      } else {
        return send(id, null, { code: -32601, message: 'Unknown tool' });
      }
      return send(id, {
        content: [{ type: 'text', text: JSON.stringify(value) }],
        isError: false,
      });
    } catch (error) {
      return send(id, {
        content: [{
          type: 'text',
          text: JSON.stringify({ error: error.message || '高德工具调用失败' }),
        }],
        isError: true,
      });
    }
  }
  if (id !== undefined) send(id, null, { code: -32601, message: `Unsupported method: ${method}` });
}

const queue = [];
let handling = false;
async function drain() {
  if (handling) return;
  handling = true;
  while (queue.length) {
    try { await handle(JSON.parse(queue.shift())); } catch { /* stdout stays JSON-RPC only */ }
  }
  handling = false;
}

function startServer() {
  readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
    .on('line', (line) => { queue.push(line); void drain(); });
}

if (require.main === module) startServer();

module.exports = {
  compactDistrict,
  districtSearch,
  normalizeText,
  parseDistrictPayload,
};
