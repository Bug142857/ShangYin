// 多 TLS 栈对比：node fetch(undici) 下载图片，看是否拿到 Salted__ 密文
(async () => {
  const img = 'https://pic.jtzatly.cn/upload_01/xiao/20261015/2026101511341885591.jpeg';
  const r = await fetch(img, { headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile' } });
  const ab = await r.arrayBuffer();
  const b = Buffer.from(ab);
  console.log('node-fetch:', r.status, b.length, 'head:', b.subarray(0, 12).toString('hex').toUpperCase());
  console.log('base64头:', b.subarray(0, 16).toString('utf8'));
  const isU2 = b.subarray(0, 8).toString('utf8') === 'Salted__';
  console.log('Salted__:', isU2);
  if (isU2) {
    // CryptoJS passphrase 模式复现：EVP_BytesToKey(MD5, passphrase, salt)
    const crypto = require('crypto');
    const passphrase = 'f5d965d1f753362750';
    const salt = b.subarray(8, 16);
    // EVP_BytesToKey: MD5 迭代
    let d = Buffer.alloc(0), prev = Buffer.alloc(0);
    while (d.length < 48) {
      prev = crypto.createHash('md5').update(Buffer.concat([prev, Buffer.from(passphrase), salt])).digest();
      d = Buffer.concat([d, prev]);
    }
    const key = d.subarray(0, 32), iv = d.subarray(32, 48);
    const dec = crypto.createDecipheriv('aes-256-cbc', key, iv);
    const plain = Buffer.concat([dec.update(b.subarray(16)), dec.final()]);
    console.log('解密头:', plain.subarray(0, 8).toString('hex').toUpperCase(), 'size:', plain.length);
    fs.writeFileSync('d:/D_P/ShangYin/jstmp/plain.bin', plain);
  }
})().catch(e => console.log('ERR', e.message));
