/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.dev.browser.engine

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Message
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.*
import android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
import android.webkit.WebView.HitTestResult.*
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import com.dev.browser.R
import com.dev.browser.concept.EngineSession
import com.dev.browser.concept.EngineSession.TrackingProtectionPolicy
import com.dev.browser.concept.EngineView
import com.dev.browser.concept.HitResult
import com.dev.browser.concept.prompt.PromptRequest
import com.dev.browser.concept.request.RequestInterceptor.InterceptionResponse
import com.dev.browser.engine.matcher.UrlMatcher
import com.dev.browser.engine.permission.SystemPermissionRequest
import com.dev.browser.engine.window.SystemWindowRequest
import com.dev.browser.support.DownloadUtils
import com.dev.browser.support.ErrorType
import com.dev.util.CommonUtil
import kotlinx.coroutines.runBlocking
import java.util.*


/**
 * WebView-based implementation of EngineView.
 */
@Suppress("TooManyFunctions")
class SystemEngineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), EngineView {
    init {
        //
        isClickable=true
        isFocusable=true
        isLongClickable=true
        setOnClickListener{
            onLongClick(it)
        }
    }
    @VisibleForTesting(otherwise = PRIVATE)
    internal var session: SystemEngineSession? = null
    internal var jsAlertCount = 0
    internal var shouldShowMoreDialogs = true
    internal var lastDialogShownAt = Date()

    /**
     * Render the content of the given session.
     */
    override fun render(session: EngineSession) {
        removeAllViews()
        this.session = session as SystemEngineSession
        (session.webView.parent as? SystemEngineView)?.removeView(session.webView)
        addView(initWebView(session.webView))
    }

    fun onLongClick(view: View?): Boolean {
        val result = session?.webView?.hitTestResult
        return result?.let { handleLongClick(result.type, result.extra) } ?: false
    }

    override fun onPause() {
        session?.apply {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    override fun onResume() {
        session?.apply {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    private val runnable= Runnable { onLongClick(null) }
    private var longClickListener:LongClickListener= LongClickListener(runnable = runnable,view = this)
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        longClickListener.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }
    class LongClickListener(var view:View,var runnable: Runnable){
        /**
         * 上一次点击的的坐标
         */
        private var lastX: Float = 0.toFloat()
        private var lastY: Float = 0.toFloat()
        /**
         * 长按坐标
         */
        private val longPressX: Float = 0.toFloat()
        private val longPressY: Float = 0.toFloat()
        /**
         * 是否移动
         */
        private var isMove: Boolean = false
        /**
         * 滑动的阈值
         */
        private val TOUCH_SLOP = 20
        fun onTouch(event: MotionEvent){
            val x = event.x
            val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isMove = false
                    lastX = x
                    lastY = y
                    view.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isMove) {
                        if (Math.abs(lastX - x) > TOUCH_SLOP || Math.abs(lastY - y) > TOUCH_SLOP) {
                            //移动超过了阈值，表示移动了
                            isMove = true
                            //移除runnable
                            view.removeCallbacks(runnable)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> view.removeCallbacks(runnable)
            }
        }
    }
    override fun onDestroy() {
        session?.apply {
            // The WebView instance is long-lived, as it's referenced in the
            // engine session. We can't destroy it here since the session
            // might be used with a different engine view instance later.

            // Further, when this engine view gets destroyed, we need to
            // remove/detach the WebView so that engine view's activity context
            // can properly be destroyed and gc'ed. The WebView instances are
            // created with the context provided to the engine (application
            // context) and reference their parent (this engine view). Since
            // we're keeping the engine session (and their WebView) instances
            // in the SessionManager until closed we'd otherwise prevent
            // this engine view and its context from getting gc'ed.
            (webView.parent as? SystemEngineView)?.removeView(webView)
        }
    }

    internal fun initWebView(webView: WebView): WebView {
        webView.tag = "mozac_system_engine_webview"
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()
        webView.setDownloadListener(createDownloadListener())
        webView.setFindListener(createFindListener())
        return webView
    }

    @Suppress("ComplexMethod", "NestedBlockDepth")
    private fun createWebViewClient() = object : WebViewClient() {
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // TODO private browsing not supported for SystemEngine
            // https://github.com/mozilla-mobile/android-components/issues/649
            runBlocking {
                session?.settings?.historyTrackingDelegate?.onVisited(url, isReload)
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            url?.let {
                session?.currentUrl = url
                session?.internalNotifyObservers {
                    onLoadingStateChange(true)
                    onLocationChange(it)
                    onNavigationStateChange(view.canGoBack(), view.canGoForward())
                }
            }
            resetJSAlertAbuseState()
        }
        override fun onPageFinished(view: WebView, url: String?) {
            url?.let {
                val cert = view?.certificate
                session?.internalNotifyObservers {
                    onLocationChange(it)
                    onNavigationStateChange(view.canGoBack(), view.canGoForward())
                    onLoadingStateChange(false)
                    onSecurityChange(
                        secure = cert != null,
                        host = cert?.let { Uri.parse(url).host },
                        issuer = cert?.issuedBy?.oName
                    )
                }
            }
        }

        @Suppress("ReturnCount", "NestedBlockDepth")
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (session?.webFontsEnabled == false && UrlMatcher.isWebFont(request.url)) {
                return WebResourceResponse(null, null, null)
            }

            session?.trackingProtectionPolicy?.let {
                val resourceUri = request.url
                val scheme = resourceUri.scheme
                val path = resourceUri.path

                if (!request.isForMainFrame && scheme != "http" && scheme != "https") {
                    // Block any malformed non-http(s) URIs. WebView will already ignore things like market: URLs,
                    // but not in all cases (malformed market: URIs, such as market:://... will still end up here).
                    // (Note: data: URIs are automatically handled by WebView, and won't end up here either.)
                    // file:// URIs are disabled separately by setting WebSettings.setAllowFileAccess()
                    return WebResourceResponse(null, null, null)
                }

                // WebView always requests a favicon, even though it won't be used anywhere. This check
                // isn't able to block all favicons (some of them will be loaded using <link rel="shortcut icon">
                // with a custom URL which we can't match or detect), but reduces the amount of unnecessary
                // favicon loading that's performed.
                if (path != null && path.endsWith("/favicon.ico")) {
                    return WebResourceResponse(null, null, null)
                }

                if (!request.isForMainFrame &&
                    getOrCreateUrlMatcher(view.context, it).matches(resourceUri, Uri.parse(session?.currentUrl))
                ) {
                    session?.internalNotifyObservers { onTrackerBlocked(resourceUri.toString()) }
                    return WebResourceResponse(null, null, null)
                }
            }

            session?.let { session ->
                session.settings.requestInterceptor?.let { interceptor ->
                    interceptor.onLoadRequest(
                        session, request.url.toString()
                    )?.apply {
                        return when (this) {
                            is InterceptionResponse.Content ->
                                WebResourceResponse(mimeType, encoding, data.byteInputStream())
                            is InterceptionResponse.Url -> {
                                view.post { view.loadUrl(url) }
                                super.shouldInterceptRequest(view, request)
                            }
                        }
                    }
                }
            }

            return super.shouldInterceptRequest(view, request)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            session?.let { session ->
                session.settings.requestInterceptor?.onErrorRequest(
                    session,
                    ErrorType.ERROR_SECURITY_SSL,
                    error.url
                )?.apply {
                    view.loadDataWithBaseURL(url, data, mimeType, encoding, null)
                }
            }
        }

        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            session?.let { session ->
                val errorType = SystemEngineSession.webViewErrorToErrorType(errorCode)
                session.settings.requestInterceptor?.onErrorRequest(
                    session,
                    errorType,
                    failingUrl
                )?.apply {
                    view.loadDataWithBaseURL(url ?: failingUrl, data, mimeType, encoding, null)
                }
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            session?.let { session ->
                if (!request.isForMainFrame) {
                    return
                }
                val errorType = SystemEngineSession.webViewErrorToErrorType(error.errorCode)
                session.settings.requestInterceptor?.onErrorRequest(
                    session,
                    errorType,
                    request.url.toString()
                )?.apply {
                    view.loadDataWithBaseURL(url ?: request.url.toString(), data, mimeType, encoding, null)
                }
            }
        }
    }

    @Suppress("ComplexMethod")
    private fun createWebChromeClient() = object : WebChromeClient() {
        override fun getVisitedHistory(callback: ValueCallback<Array<String>>) {
            // TODO private browsing not supported for SystemEngine
            // https://github.com/mozilla-mobile/android-components/issues/649
            session?.settings?.historyTrackingDelegate?.let {
                runBlocking {
                    callback.onReceiveValue(it.getVisited().toTypedArray())
                }
            }
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            session?.internalNotifyObservers { onProgress(newProgress) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val titleOrEmpty = title ?: ""
            // TODO private browsing not supported for SystemEngine
            // https://github.com/mozilla-mobile/android-components/issues/649
            session?.currentUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                session?.settings?.historyTrackingDelegate?.let { delegate ->
                    runBlocking {
                        delegate.onTitleChanged(url, titleOrEmpty)
                    }
                }
            }
            session?.internalNotifyObservers {
                onTitleChange(titleOrEmpty)
                onNavigationStateChange(view.canGoBack(), view.canGoForward())
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            addFullScreenView(view, callback)
            session?.internalNotifyObservers { onFullScreenChange(true) }
        }

        override fun onHideCustomView() {
            removeFullScreenView()
            session?.internalNotifyObservers { onFullScreenChange(false) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            session?.internalNotifyObservers { onCancelContentPermissionRequest(SystemPermissionRequest(request)) }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            session?.internalNotifyObservers { onContentPermissionRequest(SystemPermissionRequest(request)) }
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val session = session ?: return applyDefaultJsDialogBehavior(result)

            // When an alert is triggered from a iframe, url is equals to about:blank, using currentUrl as a fallback.
            val safeUrl = if (url.isNullOrBlank()) {
                session.currentUrl
            } else {
                if (url.contains("about")) session.currentUrl else url
            }

            val title = context.getString(R.string.mozac_browser_engine_system_alert_title, safeUrl)

            val onDismiss: () -> Unit = {
                result.cancel()
            }

            if (shouldShowMoreDialogs) {

                session.notifyObservers {
                    onPromptRequest(
                        PromptRequest.Alert(
                            title,
                            message ?: "",
                            areDialogsBeingAbused(),
                            onDismiss
                        ) { shouldNotShowMoreDialogs ->
                            shouldShowMoreDialogs = !shouldNotShowMoreDialogs
                            result.confirm()
                        })
                }
            } else {
                result.cancel()
            }

            updateJSDialogAbusedState()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            val session = session ?: return applyDefaultJsDialogBehavior(result)

            val title = context.getString(R.string.mozac_browser_engine_system_alert_title, url ?: session.currentUrl)

            val onDismiss: () -> Unit = {
                result.cancel()
            }

            val onConfirm: (Boolean, String) -> Unit = { shouldNotShowMoreDialogs, valueInput ->
                shouldShowMoreDialogs = !shouldNotShowMoreDialogs
                result.confirm(valueInput)
            }

            if (shouldShowMoreDialogs) {
                session.notifyObservers {
                    onPromptRequest(
                        PromptRequest.TextPrompt(
                            title ?: "",
                            message ?: "",
                            defaultValue ?: "",
                            areDialogsBeingAbused(),
                            onDismiss,
                            onConfirm
                        )
                    )
                }
            } else {
                result.cancel()
            }
            updateJSDialogAbusedState()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            val session = session ?: return applyDefaultJsDialogBehavior(result)
            val title = context.getString(R.string.mozac_browser_engine_system_alert_title, url ?: session.currentUrl)
            val positiveButton = context.getString(android.R.string.ok)
            val negativeButton = context.getString(android.R.string.cancel)

            val onDismiss: () -> Unit = {
                result.cancel()
            }

            val onConfirmPositiveButton: (Boolean) -> Unit = { shouldNotShowMoreDialogs ->
                shouldShowMoreDialogs = !shouldNotShowMoreDialogs
                result.confirm()
            }

            val onConfirmNegativeButton: (Boolean) -> Unit = { shouldNotShowMoreDialogs ->
                shouldShowMoreDialogs = !shouldNotShowMoreDialogs
                result.cancel()
            }

            if (shouldShowMoreDialogs) {
                session.notifyObservers {
                    onPromptRequest(
                        PromptRequest.Confirm(
                            title,
                            message ?: "",
                            areDialogsBeingAbused(),
                            positiveButton,
                            negativeButton,
                            "",
                            onConfirmPositiveButton,
                            onConfirmNegativeButton,
                            {},
                            onDismiss
                        )
                    )
                }
            } else {
                result.cancel()
            }
            updateJSDialogAbusedState()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {

            var mimeTypes = fileChooserParams?.acceptTypes ?: arrayOf()

            if (mimeTypes.isNotEmpty() && mimeTypes.first().isNullOrEmpty()) {
                mimeTypes = arrayOf()
            }

            val isMultipleFilesSelection = fileChooserParams?.mode == MODE_OPEN_MULTIPLE

            val onSelectMultiple: (Context, Array<Uri>) -> Unit = { _, uris ->
                filePathCallback?.onReceiveValue(uris)
            }

            val onSelectSingle: (Context, Uri) -> Unit = { _, uri ->
                filePathCallback?.onReceiveValue(arrayOf(uri))
            }

            val onDismiss: () -> Unit = {
                filePathCallback?.onReceiveValue(null)
            }

            session?.notifyObservers {
                onPromptRequest(
                    PromptRequest.File(
                        mimeTypes,
                        isMultipleFilesSelection,
                        onSelectSingle,
                        onSelectMultiple,
                        onDismiss
                    )
                )
            }

            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            session?.internalNotifyObservers {
                onOpenWindowRequest(
                    SystemWindowRequest(
                        view, NestedWebView(context), isDialog, isUserGesture, resultMsg
                    )
                )
            }
            return true
        }

        override fun onCloseWindow(window: WebView) {
            session?.internalNotifyObservers { onCloseWindowRequest(SystemWindowRequest(window)) }
        }
    }

    internal fun createDownloadListener(): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            session?.internalNotifyObservers {
                val fileName = DownloadUtils.guessFileName(contentDisposition, url, mimetype)
                val cookie = CookieManager.getInstance().getCookie(url)
                onExternalResource(url, fileName, contentLength, mimetype, cookie, userAgent)
            }
        }
    }

    internal fun createFindListener(): WebView.FindListener {
        return WebView.FindListener { activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean ->
            session?.internalNotifyObservers {
                onFindResult(activeMatchOrdinal, numberOfMatches, isDoneCounting)
            }
        }
    }

    internal fun handleLongClick(type: Int, extra: String): Boolean {
        val result: HitResult? = when (type) {
            EMAIL_TYPE -> {
                HitResult.EMAIL(extra)
            }
            GEO_TYPE -> {
                HitResult.GEO(extra)
            }
            PHONE_TYPE -> {
                HitResult.PHONE(extra)
            }
            IMAGE_TYPE -> {
                HitResult.IMAGE(extra)
            }
            SRC_ANCHOR_TYPE -> {
                HitResult.UNKNOWN(extra)
            }
            SRC_IMAGE_ANCHOR_TYPE -> {
                // HitTestResult.getExtra() contains only the image URL, and not the link
                // URL. Internally, WebView's HitTestData contains both, but they only
                // make it available via requestFocusNodeHref...
                val message = Message()
                message.target = ImageHandler(session)
                session?.webView?.requestFocusNodeHref(message)
                null
            }
            else -> null
        }
        result?.let {
            session?.internalNotifyObservers { onLongPress(it) }
            return true
        }
        return false
    }

    internal fun addFullScreenView(view: View, callback: WebChromeClient.CustomViewCallback) {
        val webView = findViewWithTag<WebView>("mozac_system_engine_webview")
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView?.apply { this.visibility = View.INVISIBLE }

        session?.fullScreenCallback = callback

        view.tag = "mozac_system_engine_fullscreen"
        addView(view, layoutParams)
    }

    internal fun removeFullScreenView() {
        val view = findViewWithTag<View>("mozac_system_engine_fullscreen")
        val webView = findViewWithTag<WebView>("mozac_system_engine_webview")
        view?.let {
            webView?.apply { this.visibility = View.VISIBLE }
            removeView(view)
        }
    }

    class ImageHandler(val session: SystemEngineSession?) : Handler() {
        override fun handleMessage(msg: Message) {
            val url = msg.data.getString("url")
            val src = msg.data.getString("src")

            if (url == null || src == null) {
                throw IllegalStateException("WebView did not supply url or src for image link")
            }

            session?.internalNotifyObservers { onLongPress(HitResult.IMAGE_SRC(src, url)) }
        }
    }

    override fun canScrollVerticallyDown() = session?.webView?.canScrollVertically(1) ?: false

    override fun captureThumbnail(onFinish: (Bitmap?) -> Unit) {
        val webView = session?.webView

        val thumbnail = if (webView == null) {
            null
        } else {
            //TODO:压缩
            loadBitmapFromView(webView)
        }
        onFinish(thumbnail)
    }

    private fun loadBitmapFromView(v: View): Bitmap {
        val fullSizeBitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(fullSizeBitmap)
        v.layout(v.left, v.top, v.right, v.bottom)
        v.draw(c)

        val density=v.context.resources.displayMetrics.density
        val sampleBitmap = CommonUtil.getResizedBitmap(fullSizeBitmap,v.height/density,v.width/density)
        fullSizeBitmap?.recycle()
        return sampleBitmap
    }

    private fun resetJSAlertAbuseState() {
        jsAlertCount = 0
        shouldShowMoreDialogs = true
    }

    private fun applyDefaultJsDialogBehavior(result: JsResult?): Boolean {
        result?.cancel()
        return true
    }

    internal fun updateJSDialogAbusedState() {
        if (!areDialogsAbusedByTime()) {
            jsAlertCount = 0
        }
        ++jsAlertCount
        lastDialogShownAt = Date()
    }

    internal fun areDialogsBeingAbused(): Boolean {
        return areDialogsAbusedByTime() || areDialogsAbusedByCount()
    }

    @Suppress("MagicNumber")
    internal fun areDialogsAbusedByTime(): Boolean {
        return if (jsAlertCount == 0) {
            false
        } else {
            val now = Date()
            val diffInSeconds = (now.time - lastDialogShownAt.time) / 1000 // 1 second equal to 1000 milliseconds
            diffInSeconds < MAX_SUCCESSIVE_DIALOG_SECONDS_LIMIT
        }
    }

    internal fun areDialogsAbusedByCount(): Boolean {
        return jsAlertCount > MAX_SUCCESSIVE_DIALOG_COUNT
    }

    companion object {

        // Maximum number of successive dialogs before we prompt users to disable dialogs.
        internal const val MAX_SUCCESSIVE_DIALOG_COUNT: Int = 2

        // Minimum time required between dialogs in seconds before enabling the stop dialog.
        internal const val MAX_SUCCESSIVE_DIALOG_SECONDS_LIMIT: Int = 3

        @Volatile
        internal var URL_MATCHER: UrlMatcher? = null

        private val urlMatcherCategoryMap = mapOf(
            UrlMatcher.ADVERTISING to EngineSession.TrackingProtectionPolicy.AD,
            UrlMatcher.ANALYTICS to TrackingProtectionPolicy.ANALYTICS,
            UrlMatcher.CONTENT to TrackingProtectionPolicy.CONTENT,
            UrlMatcher.SOCIAL to TrackingProtectionPolicy.SOCIAL
        )

        @Synchronized
        internal fun getOrCreateUrlMatcher(context: Context, policy: TrackingProtectionPolicy): UrlMatcher {
            val categories = urlMatcherCategoryMap.filterValues { policy.contains(it) }.keys

            URL_MATCHER?.setCategoriesEnabled(categories) ?: run {
                URL_MATCHER = UrlMatcher.createMatcher(
                    context,
                    R.raw.domain_blacklist,
                    intArrayOf(R.raw.domain_overrides),
                    R.raw.domain_whitelist,
                    categories
                )
            }

            return URL_MATCHER as UrlMatcher
        }
    }
}
