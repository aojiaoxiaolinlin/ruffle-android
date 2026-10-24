package rs.ruffle

/**
 * Injected into the official 4399 login page. The page owns the opaque state
 * and session; we only replace window.open with an in-place navigation and
 * invoke the same function its QQ button uses.
 */
internal object KabuQqLoginPage {
    val startQqFlow = """
        (function () {
            if (window.__kabuQqStarted) return "started";
            window.__kabuQqStarted = true;
            window.open = function (url) {
                try {
                    if (url) location.href = new URL(url, location.href).href;
                } catch (ignored) {}
                return {
                    closed: false,
                    focus: function () {},
                    close: function () {},
                    postMessage: function () {}
                };
            };
            function start() {
                if (!window.UniLogin ||
                    typeof window.UniLogin.toQzoneLogin !== "function") return false;
                window.UniLogin.toQzoneLogin(true);
                return true;
            }
            if (start()) return "started";
            var tries = 0;
            var timer = setInterval(function () {
                if (start()) {
                    clearInterval(timer);
                } else if (++tries > 40) {
                    clearInterval(timer);
                }
            }, 250);
            return "waiting";
        })()
    """.trimIndent()

    /**
     * Tencent's xlogin page owns the QR session. Calling its own poller is
     * what turns the page into a wtloginmqq:// URL that the QQ app can accept.
     */
    val startQqApp = """
        (function () {
            try {
                if (window.pt && window.pt.qrcode &&
                    typeof window.pt.qrcode.get === "function") {
                    window.pt.qrcode.get(1);
                    return "qr";
                }
            } catch (ignored) {}
            var button = document.getElementById("onekey");
            if (button) {
                try {
                    button.click();
                    return "onekey";
                } catch (ignored) {}
            }
            return "none";
        })()
    """.trimIndent()

    /**
     * Ported from the roco branch. Installed as a document-start script for
     * the QQ xlogin iframe. A real tap on QQ's one-key element is redirected
     * to Tencent's own QR poller, which emits the wtloginmqq://qrcode URL
     * our WebView can launch and later resume, instead of the p= URL that
     * sends the result to an external browser.
     */
    val useQqPolling = """
        (function () {
            if (location.protocol !== "https:" ||
                location.hostname !== "xui.ptlogin2.qq.com" ||
                location.pathname !== "/cgi-bin/xlogin") return;
            var lastStart = 0;
            function start(e) {
                var node = e.target;
                while (node && node.id !== "onekey") node = node.parentElement;
                if (!node || typeof pt !== "object" || !pt.qrcode ||
                    typeof pt.qrcode.get !== "function") return;
                e.preventDefault();
                e.stopImmediatePropagation();
                if (Date.now() - lastStart < 1200) return;
                lastStart = Date.now();
                pt.qrcode.get(1);
            }
            document.addEventListener("touchstart", start, { capture: true, passive: false });
            document.addEventListener("click", start, true);
        })()
    """.trimIndent()
}
