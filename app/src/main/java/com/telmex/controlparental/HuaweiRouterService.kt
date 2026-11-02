package com.telmex.controlparental

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class HuaweiRouterService(
    private val context: Context,
    private val webView: WebView,
    private val settings: SettingsManager
) {
    interface RouterCallback {
        fun onStatus(message: String)
        fun onSuccess(filterActive: Boolean)
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentCallback: RouterCallback? = null
    private var targetFilterState: Boolean = false
    private var isProcessing = false
    private var step = Step.IDLE

    private enum class Step {
        IDLE,
        INITIAL_LOAD,
        CLICK_SEGURIDAD,
        CLICK_WLAN_FILTER,
        APPLY_CHECKBOX
    }

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isProcessing) return

                when (step) {
                    Step.INITIAL_LOAD -> handleInitialPage()
                    else -> {}
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && isProcessing) {
                    val errMsg = "No se pudo conectar al módem (${settings.routerIp}). Asegúrate de estar conectado al Wi-Fi de Telmex."
                    currentCallback?.onError(errMsg)
                    isProcessing = false
                    step = Step.IDLE
                }
            }
        }
    }

    fun toggleFilter(enable: Boolean, callback: RouterCallback) {
        if (settings.routerPass.isBlank()) {
            callback.onError("Debes configurar la contraseña del módem en Ajustes (⚙) primero.")
            return
        }

        currentCallback = callback
        targetFilterState = enable
        isProcessing = true
        step = Step.INITIAL_LOAD

        callback.onStatus("Conectando con módem ${settings.routerIp}...")
        webView.loadUrl("http://${settings.routerIp}/")

        mainHandler.postDelayed({
            if (isProcessing) {
                isProcessing = false
                step = Step.IDLE
                currentCallback?.onError("Tiempo de espera agotado comunicando con el módem.")
            }
        }, 40000)
    }

    private fun handleInitialPage() {
        mainHandler.postDelayed({
            if (!isProcessing) return@postDelayed
            currentCallback?.onStatus("Verificando sesión en el módem...")

            val cleanUser = settings.routerUser.replace("'", "\\'")
            val cleanPass = settings.routerPass.replace("'", "\\'")

            // 1. Intentar Login si está en pantalla de inicio
            val loginJs = """
                (function() {
                    var doc = document;
                    var userField = doc.getElementById('txt_Username') || 
                                    doc.querySelector('input[type="text"]') ||
                                    doc.getElementsByName('Username')[0];
                                    
                    var passField = doc.getElementById('txt_Password') || 
                                    doc.querySelector('input[type="password"]') ||
                                    doc.getElementsByName('Password')[0];
                                    
                    var btnLogin = doc.getElementById('btnLogin') || 
                                   doc.querySelector('input[value*="Iniciar"]') ||
                                   doc.querySelector('button[type="submit"]');

                    if (userField && passField) {
                        userField.value = '$cleanUser';
                        passField.value = '$cleanPass';
                        
                        if (btnLogin) {
                            btnLogin.click();
                        } else if (typeof SubmitForm === 'function') {
                            SubmitForm();
                        } else if (typeof onLogin === 'function') {
                            onLogin();
                        }
                        return "LOGIN_ATTEMPTED";
                    }
                    return "ALREADY_LOGGED_IN";
                })();
            """.trimIndent()

            webView.evaluateJavascript(loginJs) { res ->
                val result = res?.replace("\"", "") ?: ""
                val waitTime = if (result == "LOGIN_ATTEMPTED") 3000L else 1200L

                // 2. Hacer clic en la pestaña "Seguridad"
                mainHandler.postDelayed({
                    if (!isProcessing) return@postDelayed
                    currentCallback?.onStatus("Accediendo a pestaña 'Seguridad'...")
                    step = Step.CLICK_SEGURIDAD

                    val clickSeguridadJs = """
                        (function() {
                            function findSeguridad(doc) {
                                if (!doc) return false;
                                var all = doc.querySelectorAll('a, li, td, span, div');
                                for (var i = 0; i < all.length; i++) {
                                    var t = (all[i].innerText || '').trim();
                                    if (t === 'Seguridad') {
                                        all[i].click();
                                        return true;
                                    }
                                }
                                return false;
                            }
                            if (findSeguridad(document)) return "CLICKED_SEGURIDAD";
                            var ifrs = document.querySelectorAll('iframe, frame');
                            for (var j = 0; j < ifrs.length; j++) {
                                try {
                                    if (findSeguridad(ifrs[j].contentDocument || ifrs[j].contentWindow.document)) return "CLICKED_SEGURIDAD";
                                } catch(e) {}
                            }
                            return "NOT_FOUND";
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(clickSeguridadJs) { _ ->
                        // 3. Hacer clic en "Configuración del WLAN MAC Filter"
                        mainHandler.postDelayed({
                            if (!isProcessing) return@postDelayed
                            currentCallback?.onStatus("Abriendo 'WLAN MAC Filter'...")
                            step = Step.CLICK_WLAN_FILTER

                            val clickWlanJs = """
                                (function() {
                                    function findWlan(doc) {
                                        if (!doc) return false;
                                        var all = doc.querySelectorAll('a, li, span, td');
                                        for (var i = 0; i < all.length; i++) {
                                            var t = (all[i].innerText || '').trim();
                                            if (t.indexOf('WLAN MAC Filter') !== -1 && all[i].children.length <= 1) {
                                                all[i].click();
                                                return true;
                                            }
                                        }
                                        return false;
                                    }
                                    if (findWlan(document)) return "CLICKED_WLAN";
                                    var ifrs = document.querySelectorAll('iframe, frame');
                                    for (var j = 0; j < ifrs.length; j++) {
                                        try {
                                            if (findWlan(ifrs[j].contentDocument || ifrs[j].contentWindow.document)) return "CLICKED_WLAN";
                                        } catch(e) {}
                                    }
                                    return "NOT_FOUND";
                                })();
                            """.trimIndent()

                            webView.evaluateJavascript(clickWlanJs) { _ ->
                                // 4. Esperar que cargue la pantalla y palomear la casilla
                                pollAndApplyCheckbox()
                            }
                        }, 2000)
                    }
                }, waitTime)
            }
        }, 1200)
    }

    private fun pollAndApplyCheckbox() {
        var attempts = 0
        val maxAttempts = 8
        val shouldCheck = if (targetFilterState) "true" else "false"

        val applyJs = """
            (function() {
                function applyInDoc(doc) {
                    if (!doc) return false;
                    var bodyText = doc.body ? doc.body.innerText || '' : '';
                    if (bodyText.indexOf('Habilitar WLAN MAC Filter') === -1) return false;

                    var checkbox = doc.querySelector('input[type="checkbox"]');
                    var applyBtn = null;
                    var btns = doc.querySelectorAll('input[type="button"], button, input[type="submit"]');
                    for (var i = 0; i < btns.length; i++) {
                        var txt = (btns[i].value || btns[i].innerText || '').trim();
                        if (txt === 'Aplicar' || txt === 'Apply') {
                            applyBtn = btns[i];
                            break;
                        }
                    }

                    if (checkbox) {
                        checkbox.checked = $shouldCheck;
                        var evt = doc.createEvent('HTMLEvents');
                        evt.initEvent('change', true, false);
                        checkbox.dispatchEvent(evt);

                        if (applyBtn) {
                            setTimeout(function() { applyBtn.click(); }, 300);
                        }
                        return true;
                    }
                    return false;
                }

                if (applyInDoc(document)) return "APPLIED";
                var ifrs = document.querySelectorAll('iframe, frame');
                for (var j = 0; j < ifrs.length; j++) {
                    try {
                        if (applyInDoc(ifrs[j].contentDocument || ifrs[j].contentWindow.document)) return "APPLIED";
                    } catch(e) {}
                }
                return "NOT_FOUND";
            })();
        """.trimIndent()

        val pollRunnable = object : Runnable {
            override fun run() {
                if (!isProcessing) return
                attempts++

                webView.evaluateJavascript(applyJs) { result ->
                    val res = result?.replace("\"", "") ?: ""
                    if (res == "APPLIED" || attempts >= maxAttempts) {
                        mainHandler.postDelayed({
                            isProcessing = false
                            step = Step.IDLE

                            if (res == "APPLIED") {
                                settings.isFilterActive = targetFilterState
                                currentCallback?.onSuccess(targetFilterState)
                            } else {
                                currentCallback?.onError("No se pudo confirmar el cambio en el módem.")
                            }
                        }, 2000)
                    } else {
                        mainHandler.postDelayed(this, 900)
                    }
                }
            }
        }

        currentCallback?.onStatus(if (targetFilterState) "Marcando casilla para pausar internet..." else "Desmarcando casilla para reactivar...")
        mainHandler.postDelayed(pollRunnable, 1000)
    }
}
