/*
 * LX 自定义音源（洛雪音源脚本）运行时 shim。
 *
 * 作用：在 WebView 里合成脚本期待的 globalThis.lx 宿主 API，把脚本与 App 原生侧连通：
 *   - lx.request  → 走原生 OkHttp（绕过浏览器 CORS，且脚本感知不到差别）
 *   - lx.send     → inited / updateAlert 事件回传原生
 *   - lx.utils    → Buffer / crypto(md5、aes、rsa) / zlib，给自己算签名的音源用
 *
 * 协议细节见 https://lxmusic.toside.cn/desktop/custom-source
 * 原生侧回调约定（见 LxSourceEngine.kt）：
 *   __lxLoadScript(scriptId, code, infoJson, timeoutMs)  注入并执行一个音源脚本
 *   __lxRequest(scriptId, reqId, payloadJson)            派发 request 事件（action=musicUrl 等）
 *   __lxHttpResponse(reqId, err, respJson, bodyB64)      原生 HTTP 返回
 */
(function () {
    'use strict';

    // ---------------- Buffer（Node Buffer 的最小可用实现） ----------------

    var textEncoder = new TextEncoder();
    var textDecoder = new TextDecoder('utf-8');

    function bytesOf(value) {
        if (value instanceof Uint8Array) return value;
        if (value instanceof ArrayBuffer) return new Uint8Array(value);
        if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
        if (typeof value === 'string') return textEncoder.encode(value);
        if (Array.isArray(value)) return new Uint8Array(value);
        return new Uint8Array(0);
    }

    function toHex(bytes) {
        var s = '';
        for (var i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
        return s;
    }

    function fromHex(str) {
        var out = new Uint8Array(Math.floor(str.length / 2));
        for (var i = 0; i < out.length; i++) out[i] = parseInt(str.substr(i * 2, 2), 16);
        return out;
    }

    function toBase64(bytes) {
        var bin = '';
        for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return btoa(bin);
    }

    function fromBase64(str) {
        str = String(str || '').replace(/[\r\n\s]/g, '');
        var bin = atob(str);
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }

    function toLatin1(bytes) {
        var s = '';
        for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
        return s;
    }

    class LxBuffer extends Uint8Array {
        static from(value, encoding) {
            if (typeof value === 'string') {
                var enc = String(encoding || 'utf8').toLowerCase();
                if (enc === 'base64') return new LxBuffer(fromBase64(value));
                if (enc === 'hex') return new LxBuffer(fromHex(value));
                if (enc === 'latin1' || enc === 'binary') {
                    var arr = new Uint8Array(value.length);
                    for (var i = 0; i < value.length; i++) arr[i] = value.charCodeAt(i) & 0xff;
                    return new LxBuffer(arr);
                }
                return new LxBuffer(textEncoder.encode(value));
            }
            return new LxBuffer(bytesOf(value));
        }

        static alloc(size, fill) {
            var buf = new LxBuffer(size);
            if (fill !== undefined) buf.fill(typeof fill === 'string' ? fill.charCodeAt(0) : fill);
            return buf;
        }

        static concat(list, totalLength) {
            var arrays = (list || []).map(bytesOf);
            var total = totalLength || arrays.reduce(function (n, a) { return n + a.length; }, 0);
            var out = new LxBuffer(total);
            var offset = 0;
            arrays.forEach(function (a) {
                out.set(a.subarray(0, Math.max(0, total - offset)), offset);
                offset += a.length;
            });
            return out;
        }

        static isBuffer(obj) { return obj instanceof LxBuffer; }

        toString(encoding, start, end) {
            var view = this.subarray(start || 0, end === undefined ? this.length : end);
            var enc = String(encoding || 'utf8').toLowerCase();
            if (enc === 'base64') return toBase64(view);
            if (enc === 'hex') return toHex(view);
            if (enc === 'latin1' || enc === 'binary') return toLatin1(view);
            return textDecoder.decode(view);
        }

        slice(start, end) {
            var view = this.subarray(start, end);
            return new LxBuffer(view);
        }

        equals(other) {
            var o = bytesOf(other);
            if (o.length !== this.length) return false;
            for (var i = 0; i < this.length; i++) if (this[i] !== o[i]) return false;
            return true;
        }

        toJSON() { return { type: 'Buffer', data: Array.prototype.slice.call(this) }; }
    }

    // ---------------- lx 宿主 API ----------------

    var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };
    var handlers = {};
    var pendingHttp = {};
    var scriptId = '';
    var initTimer = null;

    function safeJsonParse(text, fallback) {
        try { return JSON.parse(text); } catch (e) { return fallback; }
    }

    function stringifyFormData(formData) {
        // 支持 { key: value } 与 { key: { value, fileName, contentType } } 两种写法
        var out = [];
        Object.keys(formData || {}).forEach(function (k) {
            var v = formData[k];
            if (v && typeof v === 'object' && !(v instanceof LxBuffer) && !ArrayBuffer.isView(v)) {
                out.push({ key: k, value: v.value === undefined ? '' : String(v.value), fileName: v.fileName, contentType: v.contentType });
            } else {
                out.push({ key: k, value: String(v) });
            }
        });
        return out;
    }

    globalThis.lx = {
        version: '2.0.0',
        env: 'mobile',
        EVENT_NAMES: EVENT_NAMES,
        currentScriptInfo: {},

        on: function (name, handler) {
            (handlers[name] = handlers[name] || []).push(handler);
        },

        send: function (name, data) {
            var json = JSON.stringify(data === undefined ? null : data);
            if (name === EVENT_NAMES.inited) {
                if (initTimer) { clearTimeout(initTimer); initTimer = null; }
                LxNative.onInited(scriptId, json);
            } else if (name === EVENT_NAMES.updateAlert) {
                LxNative.onUpdateAlert(scriptId, json);
            }
        },

        request: function (url, options, callback) {
            var opts = options || {};
            var id = 'r' + (++requestSeq);
            var payload = {
                url: String(url),
                method: String(opts.method || 'GET').toUpperCase(),
                headers: opts.headers || {},
                timeout: opts.timeout || 15000,
                binary: !!opts.binary || opts.responseType === 'arraybuffer',
                followMax: opts.follow_max || 5
            };
            if (opts.form) {
                payload.form = opts.form;
            } else if (opts.formData) {
                payload.formData = stringifyFormData(opts.formData);
            } else if (opts.body !== undefined && opts.body !== null) {
                var body = opts.body;
                if (typeof body === 'string') {
                    payload.body = body;
                } else if (body instanceof ArrayBuffer || ArrayBuffer.isView(body) || body instanceof LxBuffer) {
                    payload.bodyB64 = toBase64(bytesOf(body));
                } else {
                    payload.body = typeof body === 'string' ? body : JSON.stringify(body);
                }
            }
            pendingHttp[id] = { callback: callback, binary: payload.binary, timer: null };
            // 原生侧也设了超时，这里兜底防止回调永远不来
            pendingHttp[id].timer = setTimeout(function () {
                if (pendingHttp[id]) {
                    var cb = pendingHttp[id].callback;
                    delete pendingHttp[id];
                    if (cb) cb(new Error('请求超时'), null, null);
                }
            }, payload.timeout + 5000);
            LxNative.httpRequest(id, JSON.stringify(payload));
            return function cancel() { LxNative.httpCancel(id); };
        },

        utils: {
            buffer: {
                from: function (value, encoding) { return LxBuffer.from(value, encoding); },
                bufToString: function (buf, format) { return LxBuffer.from(buf).toString(format); },
                concat: function (list, total) { return LxBuffer.concat(list, total); },
                alloc: function (size, fill) { return LxBuffer.alloc(size, fill); },
                isBuffer: function (o) { return LxBuffer.isBuffer(o); },
                Buffer: LxBuffer
            },
            crypto: {
                md5: function (str) {
                    var input = typeof str === 'string' ? CryptoJS.enc.Utf8.parse(str) : CryptoJS.lib.WordArray.create(bytesOf(str));
                    return CryptoJS.MD5(input).toString();
                },
                sha1: function (str) {
                    var input = typeof str === 'string' ? CryptoJS.enc.Utf8.parse(str) : CryptoJS.lib.WordArray.create(bytesOf(str));
                    return CryptoJS.SHA1(input).toString();
                },
                randomBytes: function (size) {
                    var arr = new Uint8Array(size);
                    crypto.getRandomValues(arr);
                    return LxBuffer.from(arr);
                },
                aesEncrypt: function (buffer, mode, key, iv) {
                    var out = aesCipher(buffer, mode, key, iv, true);
                    return LxBuffer.from(out);
                },
                aesDecrypt: function (buffer, mode, key, iv) {
                    var out = aesCipher(buffer, mode, key, iv, false);
                    return LxBuffer.from(out);
                },
                rsaEncrypt: function (buffer, key) {
                    var enc = new JSEncrypt();
                    enc.setPublicKey(String(key));
                    var raw = typeof buffer === 'string' ? buffer : toLatin1(bytesOf(buffer));
                    var b64 = enc.encrypt(raw);
                    if (b64 === false) throw new Error('RSA 加密失败');
                    return LxBuffer.from(fromBase64(b64));
                }
            },
            zlib: {
                inflate: function (buffer) { return Promise.resolve(LxBuffer.from(pako.inflate(bytesOf(buffer)))); },
                deflate: function (buffer) { return Promise.resolve(LxBuffer.from(pako.deflate(bytesOf(buffer)))); },
                inflateRaw: function (buffer) { return Promise.resolve(LxBuffer.from(pako.inflateRaw(bytesOf(buffer)))); },
                deflateRaw: function (buffer) { return Promise.resolve(LxBuffer.from(pako.deflateRaw(bytesOf(buffer)))); }
            },
            str2b64: function (str) { return toBase64(textEncoder.encode(String(str))); },
            b64DecodeUnicode: function (str) { return textDecoder.decode(fromBase64(str)); }
        }
    };

    var requestSeq = 0;

    function aesCipher(buffer, mode, key, iv, encrypt) {
        var modeStr = String(mode || 'cbc').toLowerCase();
        var useEcb = modeStr.indexOf('ecb') >= 0;
        var keyWords = CryptoJS.lib.WordArray.create(bytesOf(key));
        var cfg = {
            mode: useEcb ? CryptoJS.mode.ECB : CryptoJS.mode.CBC,
            padding: modeStr.indexOf('nopadding') >= 0 ? CryptoJS.pad.NoPadding : CryptoJS.pad.Pkcs7
        };
        if (!useEcb) cfg.iv = CryptoJS.lib.WordArray.create(bytesOf(iv || []));
        var dataWords = CryptoJS.lib.WordArray.create(bytesOf(buffer));
        var result = encrypt
            ? CryptoJS.AES.encrypt(dataWords, keyWords, cfg)
            : CryptoJS.AES.decrypt({ ciphertext: dataWords }, keyWords, cfg);
        var out = encrypt ? result.ciphertext : result;
        return fromBase64(out.toString(CryptoJS.enc.Base64));
    }

    // ---------------- 原生 → JS 入口 ----------------

    globalThis.__lxHttpResponse = function (id, err, respJson, bodyB64) {
        var pending = pendingHttp[id];
        if (!pending) return;
        delete pendingHttp[id];
        if (pending.timer) clearTimeout(pending.timer);
        var cb = pending.callback;
        if (!cb) return;
        if (err) {
            cb(new Error(err), null, null);
            return;
        }
        var resp = safeJsonParse(respJson, {}) || {};
        var body = '';
        try {
            if (bodyB64) {
                var bytes = fromBase64(bodyB64);
                if (pending.binary) {
                    body = bytes.buffer;
                } else {
                    // 按响应头 charset 解码（酷我等老接口返回 gbk，按 utf-8 解会乱码）
                    var charset = resp.charset || 'utf-8';
                    try {
                        body = new TextDecoder(charset).decode(bytes);
                    } catch (e) {
                        body = textDecoder.decode(bytes);
                    }
                }
            }
        } catch (e) {
            body = '';
        }
        resp.body = body;
        cb(null, resp, body);
    };

    globalThis.__lxLoadScript = function (id, code, infoJson, timeoutMs) {
        scriptId = id;
        lx.currentScriptInfo = safeJsonParse(infoJson, {}) || {};
        try {
            // 间接 eval：在全局作用域执行脚本（脚本自身是 IIFE）
            (0, eval)(code);
        } catch (e) {
            LxNative.onInited(scriptId, JSON.stringify({ status: false, error: String((e && e.message) || e) }));
            return;
        }
        if (initTimer) clearTimeout(initTimer);
        initTimer = setTimeout(function () {
            initTimer = null;
            LxNative.onInited(scriptId, JSON.stringify({ status: false, error: '脚本初始化超时：未收到 inited 事件' }));
        }, timeoutMs || 20000);
    };

    globalThis.__lxRequest = function (id, reqId, payloadJson) {
        scriptId = id;
        var list = handlers[EVENT_NAMES.request] || [];
        if (!list.length) {
            LxNative.onMusicUrl(reqId, null, '该音源脚本未注册 request 事件');
            return;
        }
        var payload = safeJsonParse(payloadJson, null);
        if (!payload) {
            LxNative.onMusicUrl(reqId, null, '请求参数解析失败');
            return;
        }
        var ret;
        try {
            ret = list[0](payload);
        } catch (e) {
            LxNative.onMusicUrl(reqId, null, String((e && e.message) || e));
            return;
        }
        Promise.resolve(ret).then(function (value) {
            if (value === undefined || value === null || value === '') {
                LxNative.onMusicUrl(reqId, null, '音源返回空链接');
            } else if (typeof value === 'string') {
                LxNative.onMusicUrl(reqId, value, null);
            } else if (value.url || value.data) {
                LxNative.onMusicUrl(reqId, String(value.url || value.data), null);
            } else {
                LxNative.onMusicUrl(reqId, JSON.stringify(value), null);
            }
        }, function (err) {
            var msg = err && err.message ? err.message : String(err || '音源返回失败');
            LxNative.onMusicUrl(reqId, null, msg);
        });
    };

    // 脚本异常/日志回传，便于排查"某音源为什么解析不出链接"
    globalThis.onerror = function (message, source, lineno, colno, error) {
        LxNative.log('JS错误: ' + message + ' @' + lineno + ':' + colno);
        return false;
    };
    // 宿主环境兜底：极少数精简 JS 环境没有 addEventListener，不能因为一行监控把整个 shim 打断
    if (typeof globalThis.addEventListener === 'function') {
        globalThis.addEventListener('unhandledrejection', function (e) {
            LxNative.log('未处理的 Promise 异常: ' + ((e.reason && e.reason.message) || e.reason));
        });
    }

    var rawConsoleLog = console.log.bind(console);
    console.log = function () {
        try {
            var text = Array.prototype.map.call(arguments, function (a) {
                return typeof a === 'string' ? a : JSON.stringify(a);
            }).join(' ');
            LxNative.log(text.length > 2000 ? text.substring(0, 2000) : text);
        } catch (e) { /* 忽略日志转发异常 */ }
        rawConsoleLog.apply(null, arguments);
    };
})();
