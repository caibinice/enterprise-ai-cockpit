const test = require('node:test');
const assert = require('node:assert/strict');

const {
  calculate,
  currentTime,
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
