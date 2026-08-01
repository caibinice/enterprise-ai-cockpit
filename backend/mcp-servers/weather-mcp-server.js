#!/usr/bin/env node

const https = require('https');
const readline = require('readline');

const httpAgent = new https.Agent({ keepAlive: true, maxSockets: 4 });
const weatherCache = new Map();
const requestTimeoutMs = 8_000;
const maxHttpAttempts = 3;
const freshCacheMs = 2 * 60_000;
const staleCacheMs = 30 * 60_000;
const retryableStatusCodes = new Set([408, 425, 429, 500, 502, 503, 504]);

const weatherCodes = {
  0: '晴',
  1: '大部晴朗',
  2: '局部多云',
  3: '阴',
  45: '雾',
  48: '雾凇',
  51: '小毛毛雨',
  53: '毛毛雨',
  55: '强毛毛雨',
  61: '小雨',
  63: '中雨',
  65: '大雨',
  71: '小雪',
  73: '中雪',
  75: '大雪',
  80: '阵雨',
  81: '中等阵雨',
  82: '强阵雨',
  95: '雷暴',
};

const knownLocations = {
  '常州': { name: '常州', admin1: '江苏', country: '中国', latitude: 31.8107, longitude: 119.9741 },
  '上海': { name: '上海', admin1: '上海', country: '中国', latitude: 31.2304, longitude: 121.4737 },
  '南京': { name: '南京', admin1: '江苏', country: '中国', latitude: 32.0603, longitude: 118.7969 },
  '无锡': { name: '无锡', admin1: '江苏', country: '中国', latitude: 31.4912, longitude: 120.3119 },
  '徐州': { name: '徐州', admin1: '江苏', country: '中国', latitude: 34.2044, longitude: 117.2858 },
  '南通': { name: '南通', admin1: '江苏', country: '中国', latitude: 31.9802, longitude: 120.8943 },
  '连云港': { name: '连云港', admin1: '江苏', country: '中国', latitude: 34.5967, longitude: 119.2216 },
  '淮安': { name: '淮安', admin1: '江苏', country: '中国', latitude: 33.6104, longitude: 119.0153 },
  '盐城': { name: '盐城', admin1: '江苏', country: '中国', latitude: 33.3474, longitude: 120.1636 },
  '扬州': { name: '扬州', admin1: '江苏', country: '中国', latitude: 32.3942, longitude: 119.4129 },
  '镇江': { name: '镇江', admin1: '江苏', country: '中国', latitude: 32.1880, longitude: 119.4250 },
  '泰州': { name: '泰州', admin1: '江苏', country: '中国', latitude: 32.4555, longitude: 119.9229 },
  '宿迁': { name: '宿迁', admin1: '江苏', country: '中国', latitude: 33.9630, longitude: 118.2752 },
  '北京': { name: '北京', admin1: '北京', country: '中国', latitude: 39.9042, longitude: 116.4074 },
  '深圳': { name: '深圳', admin1: '广东', country: '中国', latitude: 22.5431, longitude: 114.0579 },
  '广州': { name: '广州', admin1: '广东', country: '中国', latitude: 23.1291, longitude: 113.2644 },
  '杭州': { name: '杭州', admin1: '浙江', country: '中国', latitude: 30.2741, longitude: 120.1551 },
  '苏州': { name: '苏州', admin1: '江苏', country: '中国', latitude: 31.2989, longitude: 120.5853 },
  '成都': { name: '成都', admin1: '四川', country: '中国', latitude: 30.5728, longitude: 104.0668 },
  '重庆': { name: '重庆', admin1: '重庆', country: '中国', latitude: 29.5630, longitude: 106.5516 },
};

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

function logEvent(level, event, details = {}) {
  process.stderr.write(`${JSON.stringify({
    timestamp: new Date().toISOString(),
    level,
    event,
    ...details,
  })}\n`);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function retryAfterMilliseconds(value) {
  if (!value) return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1_000);
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : 0;
}

function requestJson(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, {
      agent: httpAgent,
      family: 4,
      headers: {
        Accept: 'application/json',
        'User-Agent': 'enterprise-ai-cockpit-mcp/1.3',
      },
      timeout: requestTimeoutMs,
    }, (response) => {
      if (
        response.statusCode >= 300
        && response.statusCode < 400
        && response.headers.location
        && redirects < 3
      ) {
        response.resume();
        resolve(requestJson(new URL(response.headers.location, url), redirects + 1));
        return;
      }
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => {
        if (response.statusCode < 200 || response.statusCode >= 300) {
          const error = new Error(`HTTP ${response.statusCode}`);
          error.retryable = retryableStatusCodes.has(response.statusCode);
          error.retryAfterMs = retryAfterMilliseconds(response.headers['retry-after']);
          reject(error);
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch {
          reject(new Error('Remote service returned invalid JSON'));
        }
      });
    });
    request.on('timeout', () => {
      const error = new Error('Request timed out');
      error.retryable = true;
      request.destroy(error);
    });
    request.on('error', (error) => {
      if (error.retryable === undefined) {
        error.retryable = [
          'ECONNRESET',
          'ECONNREFUSED',
          'EAI_AGAIN',
          'ENOTFOUND',
          'ENETUNREACH',
          'ETIMEDOUT',
        ].includes(error.code);
      }
      reject(error);
    });
  });
}

async function getJson(url) {
  let lastError;
  for (let attempt = 1; attempt <= maxHttpAttempts; attempt += 1) {
    try {
      return await requestJson(url);
    } catch (error) {
      lastError = error;
      if (!error.retryable || attempt === maxHttpAttempts) break;
      const backoffMs = Math.min(2_000, Math.max(
        error.retryAfterMs || 0,
        250 * (2 ** (attempt - 1)) + Math.floor(Math.random() * 100),
      ));
      logEvent('warn', 'upstream_retry', {
        host: new URL(url).host,
        attempt,
        backoffMs,
        reason: error.message,
      });
      await delay(backoffMs);
    }
  }
  throw lastError || new Error('Remote service request failed');
}

function normalizeCityName(city) {
  return String(city || '').trim().replace(/市$/, '');
}

function normalizeCityList(cities) {
  const raw = Array.isArray(cities)
    ? cities
    : String(cities || '').split(/[,，、;；\s]+/);
  const normalized = [...new Set(raw.map(normalizeCityName).filter(Boolean))];
  if (!normalized.length) throw new Error('至少需要一个城市');
  if (normalized.length > 20) throw new Error('单次最多查询 20 个城市');
  if (normalized.some((city) => city.length > 40)) throw new Error('城市名称过长');
  return normalized;
}

function normalizeLocationList(locations) {
  if (!Array.isArray(locations) || !locations.length) throw new Error('至少需要一个坐标城市');
  if (locations.length > 20) throw new Error('单次最多查询 20 个城市');
  const normalized = [];
  const seen = new Set();
  for (const item of locations) {
    const name = normalizeCityName(item?.city || item?.name);
    const latitude = Number(item?.latitude);
    const longitude = Number(item?.longitude);
    if (!name || name.length > 40) throw new Error('坐标城市名称无效');
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90
      || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
      throw new Error(`城市坐标无效：${name}`);
    }
    const key = name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    normalized.push({
      name,
      latitude,
      longitude,
      admin1: String(item?.admin1 || '').trim(),
      country: String(item?.country || '').trim(),
    });
  }
  return normalized;
}

function cacheKeyFor(city) {
  return normalizeCityName(city).toLowerCase();
}

async function resolveLocation(city) {
  const requestedCity = normalizeCityName(city) || '常州';
  let location = knownLocations[requestedCity];
  if (location) return location;
  const geocoding = new URL('https://geocoding-api.open-meteo.com/v1/search');
  geocoding.searchParams.set('name', requestedCity);
  geocoding.searchParams.set('count', '1');
  geocoding.searchParams.set('language', 'zh');
  geocoding.searchParams.set('format', 'json');
  const locations = await getJson(geocoding);
  [location] = locations.results || [];
  if (!location) throw new Error(`未找到城市：${requestedCity}`);
  return location;
}

async function fetchForecasts(locations) {
  const forecast = new URL('https://api.open-meteo.com/v1/forecast');
  forecast.searchParams.set('latitude', locations.map((location) => location.latitude).join(','));
  forecast.searchParams.set('longitude', locations.map((location) => location.longitude).join(','));
  forecast.searchParams.set(
    'current',
    'temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m',
  );
  forecast.searchParams.set('timezone', 'auto');
  const payload = await getJson(forecast);
  const forecasts = Array.isArray(payload) ? payload : [payload];
  if (forecasts.length !== locations.length) {
    throw new Error(`天气服务返回 ${forecasts.length} 个结果，预期 ${locations.length} 个`);
  }
  return forecasts;
}

function weatherValue(location, data) {
  const current = data.current || {};
  if (!Number.isFinite(current.temperature_2m)) {
    throw new Error(`天气服务未返回 ${location.name} 的有效气温`);
  }
  return {
    city: location.name,
    region: [location.admin1, location.country].filter(Boolean).join(' · '),
    condition: weatherCodes[current.weather_code] || `天气代码 ${current.weather_code}`,
    temperatureC: current.temperature_2m,
    apparentTemperatureC: current.apparent_temperature,
    humidityPercent: current.relative_humidity_2m,
    windSpeedKmh: current.wind_speed_10m,
    observedAt: current.time,
    timezone: data.timezone,
    source: 'Open-Meteo',
    cached: false,
    stale: false,
  };
}

async function queryWeather(city) {
  const requestedCity = normalizeCityName(city) || '常州';
  const cacheKey = cacheKeyFor(requestedCity);
  const cached = weatherCache.get(cacheKey);
  if (cached && Date.now() - cached.savedAt <= freshCacheMs) {
    return { ...cached.value, cached: true, stale: false };
  }
  try {
    const location = await resolveLocation(requestedCity);
    const [data] = await fetchForecasts([location]);
    const value = weatherValue(location, data);
    weatherCache.set(cacheKey, { value, savedAt: Date.now() });
    return value;
  } catch (error) {
    if (cached && Date.now() - cached.savedAt <= staleCacheMs) {
      logEvent('warn', 'weather_stale_cache', {
        city: requestedCity,
        reason: error.message,
      });
      return {
        ...cached.value,
        cached: true,
        stale: true,
        warning: '实时天气服务暂时不可用，返回最近一次成功结果。',
      };
    }
    throw error;
  }
}

async function queryWeatherBatch(cities, region, coordinateLocations, displayNames) {
  const suppliedLocations = coordinateLocations === undefined
    ? []
    : normalizeLocationList(coordinateLocations);
  const requestedCities = cities === undefined
    ? suppliedLocations.map((location) => location.name)
    : normalizeCityList(cities);
  const requestedDisplayNames = Array.isArray(displayNames)
    && displayNames.length === requestedCities.length
    ? displayNames.map(normalizeCityName)
    : [];
  const suppliedByCity = new Map(
    suppliedLocations.map((location) => [cacheKeyFor(location.name), location]),
  );
  const valuesByCity = new Map();
  const missing = [];
  for (const city of requestedCities) {
    const cached = weatherCache.get(cacheKeyFor(city));
    if (cached && Date.now() - cached.savedAt <= freshCacheMs) {
      valuesByCity.set(city, { ...cached.value, cached: true, stale: false });
    } else {
      missing.push(city);
    }
  }

  if (missing.length) {
    try {
      const locations = await Promise.all(missing.map((city) => (
        suppliedByCity.get(cacheKeyFor(city)) || resolveLocation(city)
      )));
      const forecasts = await fetchForecasts(locations);
      missing.forEach((city, index) => {
        const value = weatherValue(locations[index], forecasts[index]);
        weatherCache.set(cacheKeyFor(city), { value, savedAt: Date.now() });
        valuesByCity.set(city, value);
      });
    } catch (error) {
      const staleValues = new Map();
      for (const city of missing) {
        const cached = weatherCache.get(cacheKeyFor(city));
        if (!cached || Date.now() - cached.savedAt > staleCacheMs) throw error;
        staleValues.set(city, {
          ...cached.value,
          cached: true,
          stale: true,
          warning: '实时天气服务暂时不可用，返回最近一次成功结果。',
        });
      }
      logEvent('warn', 'weather_batch_stale_cache', {
        cities: missing,
        reason: error.message,
      });
      staleValues.forEach((value, city) => valuesByCity.set(city, value));
    }
  }

  const values = requestedCities.map((city, index) => {
    const value = valuesByCity.get(city);
    const displayName = requestedDisplayNames[index];
    return displayName ? { ...value, city: displayName } : value;
  });
  const derivedRegion = String(region || '').trim()
    || [...new Set(values.map((value) => String(value.region || '').split(' · ')[0]).filter(Boolean))].join('、');
  return {
    queryType: 'weather-batch',
    region: derivedRegion,
    count: values.length,
    cities: values,
    temperatureUnit: '°C',
    source: 'Open-Meteo',
    observedAt: values.map((value) => value.observedAt).filter(Boolean).sort().at(-1),
  };
}

function currentTime(timezone) {
  const requested = String(timezone || 'Asia/Shanghai').trim();
  try {
    const now = new Date();
    return {
      timezone: requested,
      localTime: new Intl.DateTimeFormat('zh-CN', {
        timeZone: requested,
        dateStyle: 'full',
        timeStyle: 'long',
        hour12: false,
      }).format(now),
      isoTime: now.toISOString(),
    };
  } catch {
    throw new Error(`无效时区：${requested}`);
  }
}

class ExpressionParser {
  constructor(expression) {
    this.source = expression.replace(/\s+/g, '');
    this.index = 0;
  }

  parse() {
    if (!this.source || !/^[\d+\-*/().]+$/.test(this.source)) {
      throw new Error('表达式只能包含数字、括号和四则运算符');
    }
    const value = this.expression();
    if (this.index !== this.source.length || !Number.isFinite(value)) {
      throw new Error('表达式格式不正确');
    }
    return value;
  }

  expression() {
    let value = this.term();
    while (this.peek() === '+' || this.peek() === '-') {
      const operator = this.source[this.index++];
      const right = this.term();
      value = operator === '+' ? value + right : value - right;
    }
    return value;
  }

  term() {
    let value = this.factor();
    while (this.peek() === '*' || this.peek() === '/') {
      const operator = this.source[this.index++];
      const right = this.factor();
      if (operator === '/' && right === 0) throw new Error('不能除以 0');
      value = operator === '*' ? value * right : value / right;
    }
    return value;
  }

  factor() {
    if (this.peek() === '+' || this.peek() === '-') {
      const operator = this.source[this.index++];
      const value = this.factor();
      return operator === '-' ? -value : value;
    }
    if (this.peek() === '(') {
      this.index += 1;
      const value = this.expression();
      if (this.peek() !== ')') throw new Error('缺少右括号');
      this.index += 1;
      return value;
    }
    const match = this.source.slice(this.index).match(/^(?:\d+(?:\.\d*)?|\.\d+)/);
    if (!match) throw new Error('此处需要数字');
    this.index += match[0].length;
    return Number(match[0]);
  }

  peek() {
    return this.source[this.index];
  }
}

function calculate(expression) {
  const requested = String(expression || '').trim();
  const rawResult = new ExpressionParser(requested).parse();
  const result = Number.parseFloat(rawResult.toPrecision(15));
  return { expression: requested, result };
}

const tools = [
  {
    name: 'queryWeather',
    description: '查询一个或多个城市的实时天气、体感温度、湿度和风速。批量查询会一次返回全部城市。',
    inputSchema: {
      type: 'object',
      properties: {
        city: { type: 'string', description: '单个城市名称' },
        cities: {
          type: 'array',
          description: '需要批量查询的城市名称，最多 20 个',
          items: { type: 'string' },
          minItems: 1,
          maxItems: 20,
          uniqueItems: true,
        },
        locations: {
          type: 'array',
          description: '可选的权威城市坐标，适合接收地图/行政区工具结果并避免再次地理编码',
          items: {
            type: 'object',
            properties: {
              city: { type: 'string' },
              latitude: { type: 'number', minimum: -90, maximum: 90 },
              longitude: { type: 'number', minimum: -180, maximum: 180 },
              admin1: { type: 'string' },
              country: { type: 'string' },
            },
            required: ['city', 'latitude', 'longitude'],
          },
          minItems: 1,
          maxItems: 20,
        },
        displayNames: {
          type: 'array',
          description: '与 cities 顺序一致的界面展示名；跨语言查询时可用中文标签配合英文查询名',
          items: { type: 'string' },
          minItems: 1,
          maxItems: 20,
        },
        region: { type: 'string', description: '城市所属区域，用于结果与图表标题' },
      },
      anyOf: [
        { required: ['city'] },
        { required: ['cities'] },
        { required: ['locations'] },
      ],
    },
  },
  {
    name: 'getCurrentTime',
    description: '查询 IANA 时区的当前日期和时间。',
    inputSchema: {
      type: 'object',
      properties: {
        timezone: {
          type: 'string',
          description: 'IANA 时区，例如 Asia/Shanghai',
        },
      },
    },
  },
  {
    name: 'calculate',
    description: '安全计算包含括号的四则运算表达式。',
    inputSchema: {
      type: 'object',
      properties: {
        expression: {
          type: 'string',
          description: '只包含数字、括号、+、-、*、/ 的表达式',
        },
      },
      required: ['expression'],
    },
  },
];

async function handle(message) {
  const { id, method, params = {} } = message;
  if (method === 'initialize') {
    send(id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'enterprise-ai-utilities', version: '1.3.0' },
    });
    return;
  }
  if (method === 'notifications/initialized') return;
  if (method === 'ping') {
    send(id, {});
    return;
  }
  if (method === 'tools/list') {
    send(id, { tools });
    return;
  }
  if (method === 'tools/call') {
    try {
      let value;
      if (params.name === 'queryWeather') {
        value = params.arguments?.cities !== undefined || params.arguments?.locations !== undefined
          ? await queryWeatherBatch(
            params.arguments?.cities,
            params.arguments?.region,
            params.arguments?.locations,
            params.arguments?.displayNames,
          )
          : await queryWeather(params.arguments?.city);
      } else if (params.name === 'getCurrentTime') {
        value = currentTime(params.arguments?.timezone);
      } else if (params.name === 'calculate') {
        value = calculate(params.arguments?.expression);
      } else {
        send(id, null, { code: -32601, message: 'Unknown tool' });
        return;
      }
      send(id, {
        content: [{ type: 'text', text: JSON.stringify(value) }],
        isError: false,
      });
    } catch (error) {
      send(id, {
        content: [{
          type: 'text',
          text: JSON.stringify({
            error: error.message || 'Tool call failed',
            code: 'UPSTREAM_UNAVAILABLE',
            retryable: Boolean(error.retryable),
          }),
        }],
        isError: true,
      });
    }
    return;
  }
  if (id !== undefined) {
    send(id, null, { code: -32601, message: `Unsupported method: ${method}` });
  }
}

const queue = [];
let handling = false;

async function drain() {
  if (handling) return;
  handling = true;
  while (queue.length) {
    const line = queue.shift();
    try {
      await handle(JSON.parse(line));
    } catch {
      // Keep stdout reserved for valid JSON-RPC responses.
    }
  }
  handling = false;
}

function startServer() {
  readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
    .on('line', (line) => {
      queue.push(line);
      void drain();
    });
}

if (require.main === module) {
  startServer();
}

module.exports = {
  calculate,
  currentTime,
  getJson,
  normalizeCityList,
  normalizeLocationList,
  queryWeather,
  queryWeatherBatch,
  requestJson,
};
