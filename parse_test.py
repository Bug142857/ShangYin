import sys, re, json, html, urllib.parse

raw = open(sys.argv[1], encoding='utf-8').read()
print('size:', len(raw))
print(raw[:400])
# try json
try:
    d = json.loads(raw)
    print('JSON keys:', list(d.keys()))
    print(json.dumps(d, ensure_ascii=False)[:600])
except Exception as e:
    # 豆瓣 link2 跳转链接里 URL 是编码的，需要 decode 后再匹配
    decoded = urllib.parse.unquote(raw)
    ms = re.findall(r'celebrity/(\d+)', decoded)
    print('celebrity:', ms[:6])
    ms2 = re.findall(r'personage/(\d+)', decoded)
    print('personage:', ms2[:6])
    # 也检查 onclick 里的 sid
    ms3 = re.findall(r'sid:\s*(\d+)', raw)
    print('sid:', ms3[:6])
    # 检查 h3>a 结构（Jsoup 选择器 div.result h3 a）
    titles = re.findall(r'<h3>\s*<span>\[人物\]</span>&nbsp;<a[^>]*>([^<]+)', raw)
    print('titles:', titles[:6])
