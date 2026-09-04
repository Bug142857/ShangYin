import urllib.request, urllib.parse, re
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
CK = "bid=abcd1234efg"
def get(url, ref):
    r = urllib.request.Request(url, headers={"User-Agent":UA,"Cookie":CK,"Referer":ref})
    try: return urllib.request.urlopen(r, timeout=15).read().decode("utf-8","ignore")
    except Exception as e: return f"ERR {e}"
# 桌面游戏搜索：game.douban.com search （尝试）
qs = ["小岛秀夫", "任天堂", "卡普空", "Supergiant"]
for q in qs:
    qq = urllib.parse.quote(q)
    # 尝试 game.douban.com search（豆瓣游戏子域）
    h = get(f"https://www.douban.com/search?cat=1004&q={qq}","https://www.douban.com/")
    ids_total = re.findall(r'sid["\s:=]+(\d+):(\w+)', h)
    print(f"\n综合 search(q={q}) len={len(h)} type counts:")
    from collections import Counter
    cnt = Counter(t for _,t in ids_total)
    for k,v in cnt.most_common(8): print(f"  {k}={v}")
    # 具体游戏条目
    game_ids = [i for i,t in ids_total if t in ('1004', 'game', '3114')][:6]
    print(f"  game-like sids: {game_ids}")
    # onclick sid:数字:类型 形式，数 game
    m2 = re.findall(r'sid:\s*(\d+):(\w+)\b', h)
    if m2:
        cnt2 = Counter(t for _,t in m2)
        print(f"  onclick sid types: {dict(cnt2.most_common(8))}")
        gids = [i for i,t in m2 if t == 'game'][:6]
        print(f"  typed game sids: {gids}")
