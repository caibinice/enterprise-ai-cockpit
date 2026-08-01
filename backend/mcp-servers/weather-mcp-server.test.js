const test = require('node:test');
const assert = require('node:assert/strict');

const {
  calculate,
  currentTime,
  normalizeCityList,
} = require('./weather-mcp-server');

test('calculator handles precedence and parentheses without eval', () => {
  assert.deepEqual(calculate('(120 + 95) * 1.08'), {
    expression: '(120 + 95) * 1.08',
    result: 232.2,
  });
  assert.throws(() => calculate('process.exit()'), /只能包含数字/);
});

test('time tool validates the requested IANA timezone', () => {
  const result = currentTime('Asia/Shanghai');
  assert.equal(result.timezone, 'Asia/Shanghai');
  assert.match(result.isoTime, /^\d{4}-\d{2}-\d{2}T/);
  assert.throws(() => currentTime('Invalid/Timezone'), /无效时区/);
});

test('batch weather input normalizes city suffixes and removes duplicates', () => {
  assert.deepEqual(
    normalizeCityList(['南京市', '无锡', '南京', ' 苏州 ']),
    ['南京', '无锡', '苏州'],
  );
  assert.throws(() => normalizeCityList([]), /至少需要一个城市/);
  assert.throws(
    () => normalizeCityList(Array.from({ length: 21 }, (_, index) => `城市${index}`)),
    /最多查询 20 个城市/,
  );
});
