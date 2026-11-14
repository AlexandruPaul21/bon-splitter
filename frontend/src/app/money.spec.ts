import assert from 'node:assert/strict';
import { test } from 'node:test';
import { equalShares, moneyInput, parseMoney, parsePercent, percentInput } from './money';

test('money inputs convert to integer cents without rounding errors', () => {
  assert.equal(parseMoney('0'), 0);
  assert.equal(parseMoney('12.3'), 1230);
  assert.equal(parseMoney('12.34'), 1234);
  assert.equal(parseMoney('0.01'), 1);
  assert.equal(moneyInput(1234), '12.34');
  for (const invalid of ['-2.00', '1.999', '1e3', 'abc', '', '9007199254740992']) {
    assert.equal(parseMoney(invalid), null, `${invalid} must not be accepted`);
  }
});

test('percent inputs convert to basis points within 0–100%', () => {
  assert.equal(parsePercent('0'), 0);
  assert.equal(parsePercent('33.33%'), 3333);
  assert.equal(parsePercent('100.00'), 10000);
  assert.equal(percentInput(3333), '33.33');
  for (const invalid of ['100.01', '-1', '1.234', '50e0', '']) {
    assert.equal(parsePercent(invalid), null, `${invalid} must not be accepted`);
  }
});

test('equal amount splits allocate every cent once', () => {
  assert.deepEqual(equalShares(['a', 'b', 'c'], 100, 'AMOUNT'), [
    { memberId: 'a', value: 34 }, { memberId: 'b', value: 33 }, { memberId: 'c', value: 33 }
  ]);
  assert.deepEqual(equalShares([], 100, 'AMOUNT'), []);
});

test('equal percentage splits allocate all 10000 basis points', () => {
  const shares = equalShares(['a', 'b', 'c'], 999, 'PERCENT');
  assert.deepEqual(shares.map(share => share.value), [3334, 3333, 3333]);
  assert.equal(shares.reduce((sum, share) => sum + share.value, 0), 10000);
});
