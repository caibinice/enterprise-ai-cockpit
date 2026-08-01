const test = require('node:test');
const assert = require('node:assert/strict');

const {
  normalizeText,
  parseDistrictPayload,
} = require('./amap-mcp-server');

test('district payload preserves city names and administrative codes', () => {
  const result = parseDistrictPayload({
    districts: [{
      name: '浙江省',
      adcode: '330000',
      citycode: [],
      center: '120.153576,30.287459',
      level: 'province',
      districts: [
        { name: '杭州市', adcode: '330100', citycode: '0571', center: '120,30', level: 'city' },
        { name: '宁波市', adcode: '330200', citycode: '0574', center: '121,29', level: 'city' },
      ],
    }],
  }, '浙江省');

  assert.equal(result.name, '浙江省');
  assert.deepEqual(result.children.map((city) => city.name), ['杭州市', '宁波市']);
  assert.deepEqual(result.children.map((city) => city.adcode), ['330100', '330200']);
  assert.equal(result.source, '高德地图');
});

test('input normalization rejects empty or excessive values', () => {
  assert.equal(normalizeText(' 浙江省 ', '行政区'), '浙江省');
  assert.throws(() => normalizeText('', '行政区'), /不能为空/);
  assert.throws(() => normalizeText('x'.repeat(81), '行政区', 80), /过长/);
});

