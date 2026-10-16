// 验证站点 decryptImage：eval zzz.js → decryptImage(base64(a.bin)) → 检查明文头
const fs = require('fs');
const src = fs.readFileSync('d:/D_P/ShangYin/jstmp/zzz.js', 'utf8');
global.window = global;
global.document = {
  addEventListener() {}, querySelectorAll() { return []; },
  getElementById() { return null; }, createElement() { return { style: {}, setAttribute() {}, appendChild() {} }; },
  head: { appendChild() {} }, body: { appendChild() {} }
};
global.navigator = { userAgent: 'Mozilla/5.0' };
global.location = { href: 'https://boy.fugkjqjmg.cc/', protocol: 'https:' };
try { eval(src); } catch (e) { console.log('eval err:', e.message, e.stack ? e.stack.split('\n')[1] : ''); }
// 观察混淆表状态：var 声明会进 global
console.log('_0x2f53:', typeof _0x2f53, Array.isArray(_0x2f53) ? _0x2f53.length : '-');
console.log('_0x442c:', typeof _0x442c);
if (typeof _0x442c === 'function') {
  try {
    console.log('tbl(0x176):', JSON.stringify(_0x442c(0x176)));
    console.log('tbl(0x16f):', JSON.stringify(_0x442c(0x16f)));
    console.log('tbl(0x179):', JSON.stringify(_0x442c(0x179)));
  } catch (e) { console.log('tbl err:', e.message); }
}
console.log('decryptImage type:', typeof decryptImage);
if (typeof decryptImage !== 'function') { console.log('FAIL: 无 decryptImage'); process.exit(1); }
const buf = fs.readFileSync('d:/D_P/ShangYin/a.bin');
const b64 = buf.toString('base64');
const out = decryptImage(b64);
console.log('out type:', typeof out, 'len:', out ? out.length : 0);
// out 是 base64 明文字符串 → 解码看魔数
const plain = Buffer.from(out, 'base64');
console.log('plain head:', plain.subarray(0, 8).toString('hex').toUpperCase());
console.log('plain size:', plain.length);
