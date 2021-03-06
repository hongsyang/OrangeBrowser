/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.dev.browser.session

import android.graphics.Bitmap
import androidx.fragment.app.Fragment
import com.dev.browser.concept.HitResult
import com.dev.browser.concept.media.Media
import com.dev.browser.concept.permission.PermissionRequest
import com.dev.browser.concept.prompt.PromptRequest
import com.dev.browser.concept.window.WindowRequest
import com.dev.browser.session.engine.EngineSessionHolder
import com.dev.browser.session.manifest.WebAppManifest
import com.dev.browser.session.tab.CustomTabConfig
import com.dev.browser.support.Consumable
import com.dev.browser.support.Observable
import com.dev.browser.support.ObserverRegistry
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.properties.Delegates

/**
 * Value type that represents the state of a browser session. Changes can be observed.
 */
@Suppress("TooManyFunctions")
class Session(
    initialUrl: String?=NO_EXIST_URL,
    var private: Boolean = false,
    val source: Source = Source.NONE,
    val id: String = UUID.randomUUID().toString(),
    var forbidImageMode: Boolean = false,
    var visionMode: Int = NORMAL_SCREEN_MODE,
    var enterFullScreenMode: Boolean = false,
    var themeColorMap: HashMap<String, Int> = HashMap(),
    var isStatusBarDarkMode: Boolean = false,
    var screenNumber:Int=HOME_SCREEN,//screenNumber默认为
    var tmpThumbnail:SoftReference<Bitmap>?=null,
    delegate: Observable<Observer> = ObserverRegistry()
) : Observable<Session.Observer> by delegate {
    var homeScreenState:Fragment.SavedState?=null  //保存HomeScreen的状态
    var thumbnailPath:String?=null  //将thumbnail保存起来
    @Synchronized get()= field
    @Synchronized set(value) {
         field=value
    }
    companion object {
        const val HOME_TITLE="首页"
        const val THUMBNAIL_DIR="thumbnail"
        const val HOME_SCREEN=1  //Home screen代表的值
        const val NO_EXIST_URL=""  //不存在的URL，表示还未加载过
        const val NORMAL_SCREEN_MODE = 1  //正常模式
        const val SCROLL_FULL_SCREEN_MODE = 2  //滑动最大视野模式
        const val MAX_SCREEN_MODE = 3  //保持最大视野模式
        const val STATIC_FULL_SCREEN_MODE = 4 //全局模式，不可以滑动，如视频这类的应用
    }

    /**
     * Holder for keeping a reference to an engine session and its observer to update this session
     * object.
     */
    internal val engineSessionHolder = EngineSessionHolder()

    /**
     * Id of parent session, usually refer to the session which created this one. The clue to indicate if this session
     * is terminated, which target we should go back.
     */
    var parentId: String? = null

    /**
     * Interface to be implemented by classes that want to observe a session.
     */
    interface Observer {
        fun onUrlChanged(session: Session, url: String) = Unit
        fun onTitleChanged(session: Session, title: String) = Unit
        fun onProgress(session: Session, progress: Int) = Unit
        fun onLoadingStateChanged(session: Session, loading: Boolean) = Unit
        fun onNavigationStateChanged(session: Session, canGoBack: Boolean, canGoForward: Boolean) = Unit
        fun onSearch(session: Session, searchTerms: String) = Unit
        fun onSecurityChanged(session: Session, securityInfo: SecurityInfo) = Unit
        fun onCustomTabConfigChanged(session: Session, customTabConfig: CustomTabConfig?) = Unit
        fun onWebAppManifestChanged(session: Session, manifest: WebAppManifest?) = Unit
        fun onDownload(session: Session, download: Download): Boolean = false
        fun onTrackerBlockingEnabledChanged(session: Session, blockingEnabled: Boolean) = Unit
        fun onTrackerBlocked(session: Session, blocked: String, all: List<String>) = Unit
        fun onLongPress(session: Session, hitResult: HitResult): Boolean = false
        fun onFindResult(session: Session, result: FindResult) = Unit
        fun onDesktopModeChanged(session: Session, enabled: Boolean) = Unit
        fun onFullScreenChanged(session: Session, enabled: Boolean) = Unit
        fun onThumbnailCapture(session: Session, bitmap: Bitmap?) = Unit
        fun onContentPermissionRequested(session: Session, permissionRequest: PermissionRequest): Boolean = false
        fun onAppPermissionRequested(session: Session, permissionRequest: PermissionRequest): Boolean = false
        fun onPromptRequested(session: Session, promptRequest: PromptRequest): Boolean = false
        fun onOpenWindowRequested(session: Session, windowRequest: WindowRequest): Boolean = false
        fun onCloseWindowRequested(session: Session, windowRequest: WindowRequest): Boolean = false
        fun onMediaRemoved(session: Session, media: List<Media>, removed: Media) = Unit
        fun onMediaAdded(session: Session, media: List<Media>, added: Media) = Unit
    }

    /**
     * A value type holding security information for a Session.
     *
     * @property secure true if the session is currently pointed to a URL with
     * a valid SSL certificate, otherwise false.
     * @property host domain for which the SSL certificate was issued.
     * @property issuer name of the certificate authority who issued the SSL certificate.
     */
    data class SecurityInfo(val secure: Boolean = false, val host: String = "", val issuer: String = "")

    /**
     * Represents the origin of a session to describe how and why it was created.
     */
    enum class Source {
        /**
         * Created to handle an ACTION_SEND (share) intent
         */
        ACTION_SEND,

        /**
         * Created to handle an ACTION_VIEW intent
         */
        ACTION_VIEW,

        /**
         * Created to handle a CustomTabs intent
         */
        CUSTOM_TAB,

        /**
         * User interacted with the home screen
         */
        HOME_SCREEN,

        /**
         * User interacted with a menu
         */
        MENU,

        /**
         * User opened a new tab
         */
        NEW_TAB,

        /**
         * Default value and for testing purposes
         */
        NONE,

        /**
         * Default value and for testing purposes
         */
        TEXT_SELECTION,

        /**
         * User entered a URL or search term
         */
        USER_ENTERED
    }

    /**
     * A value type representing a result of a "find in page" operation.
     *
     * @property activeMatchOrdinal the zero-based ordinal of the currently selected match.
     * @property numberOfMatches the match count
     * @property isDoneCounting true if the find operation has completed, otherwise false.
     */
    data class FindResult(val activeMatchOrdinal: Int, val numberOfMatches: Int, val isDoneCounting: Boolean)

    /**
     * The currently loading or loaded URL.
     */
    var url: String by Delegates.observable(initialUrl ?: "") { _, old, new ->
        notifyObservers(old, new) {
            onUrlChanged(this@Session, new)
        }
    }
    /**
     * The title of the currently displayed website changed.
     */
    var title: String by Delegates.observable(HOME_TITLE) { _, old, new ->
        notifyObservers(old, new) { onTitleChanged(this@Session, new) }
    }

    /**
     * The progress loading the current URL.
     */
    var progress: Int by Delegates.observable(0) { _, old, new ->
        notifyObservers(old, new) { onProgress(this@Session, new) }
    }

    /**
     * Loading state, true if this session's url is currently loading, otherwise false.
     */
    var loading: Boolean by Delegates.observable(false) { _, old, new ->
        notifyObservers(old, new) { onLoadingStateChanged(this@Session, new) }
    }

    /**
     * Navigation state, true if there's an history item to go back to, otherwise false.
     */
    var canGoBack: Boolean by Delegates.observable(false) { _, old, new ->
        notifyObservers(old, new) {
            onNavigationStateChanged(this@Session, new, canGoForward)
        }
    }

    /**
     * Navigation state, true if there's an history item to go forward to, otherwise false.
     */
    var canGoForward: Boolean by Delegates.observable(false) { _, old, new ->
        notifyObservers(old, new) { onNavigationStateChanged(this@Session, canGoBack, new) }
    }

    /**
     * The currently / last used search terms.
     */
    var searchTerms: String by Delegates.observable("") { _, _, new ->
        notifyObservers { if (!new.isEmpty()) onSearch(this@Session, new) }
    }

    /**
     * Security information indicating whether or not the current session is
     * for a secure URL, as well as the host and SSL certificate authority, if applicable.
     */
    var securityInfo: SecurityInfo by Delegates.observable(SecurityInfo()) { _, old, new ->
        notifyObservers(old, new) { onSecurityChanged(this@Session, new) }
    }

    /**
     * Configuration data in case this session is used for a Custom Tab.
     */
    var customTabConfig: CustomTabConfig? by Delegates.observable<CustomTabConfig?>(null) { _, _, new ->
        notifyObservers { onCustomTabConfigChanged(this@Session, new) }
    }

    /**
     * The Web App Manifest for the currently visited page (or null).
     */
    var webAppManifest: WebAppManifest? by Delegates.observable<WebAppManifest?>(null) { _, _, new ->
        notifyObservers { onWebAppManifestChanged(this@Session, new) }
    }

    /**
     * Last download request if it wasn't consumed by at least one observer.
     */
    var download: Consumable<Download> by Delegates.vetoable(Consumable.empty()) { _, _, download ->
        val consumers = wrapConsumers<Download> { onDownload(this@Session, it) }
        !download.consumeBy(consumers)
    }

    /**
     * List of [Media] on the currently visited page.
     */
    var media: List<Media> by Delegates.observable(emptyList()) { _, old, new ->
        if (old.size > new.size) {
            val removed = old - new
            require(removed.size == 1) { "Expected only one item to be removed, but was ${removed.size}" }
            notifyObservers {
                onMediaRemoved(this@Session, new, removed[0])
            }
        } else if (new.size > old.size) {
            val added = new - old
            require(added.size == 1) { "Expected only one item to be added, but was ${added.size}" }
            notifyObservers {
                onMediaAdded(this@Session, new, added[0])
            }
        }
    }

    /**
     * Tracker blocking state, true if blocking trackers is enabled, otherwise false.
     */
    var trackerBlockingEnabled: Boolean by Delegates.observable(false) { _, old, new ->
        notifyObservers(old, new) { onTrackerBlockingEnabledChanged(this@Session, new) }
    }

    /**
     * List of URIs that have been blocked in this session.
     */
    var trackersBlocked: List<String> by Delegates.observable(emptyList()) { _, old, new ->
        notifyObservers(old, new) {
            if (new.isNotEmpty()) {
                onTrackerBlocked(this@Session, new.last(), new)
            }
        }
    }

    /**
     * List of results of that latest "find in page" operation.
     */
    var findResults: List<FindResult> by Delegates.observable(emptyList()) { _, old, new ->
        notifyObservers(old, new) {
            if (new.isNotEmpty()) {
                onFindResult(this@Session, new.last())
            }
        }
    }

    /**
     * The target of the latest long click operation.
     */
    var hitResult: Consumable<HitResult> by Delegates.vetoable(Consumable.empty()) { _, _, result ->
        val consumers = wrapConsumers<HitResult> { onLongPress(this@Session, it) }
        !result.consumeBy(consumers)
    }

    /**
     * The target of the latest thumbnail.//仅仅用于主题颜色，不用于保存
     */
    var thumbnail: Bitmap? by Delegates.observable<Bitmap?>(null) { _, _, new ->
        notifyObservers { onThumbnailCapture(this@Session, new) }
    }

    /**
     * Desktop Mode state, true if the desktop mode is requested, otherwise false.
     */
    var desktopMode: Boolean by Delegates.observable(false) { _, old, new ->
        notifyObservers(old, new) { onDesktopModeChanged(this@Session, new) }
    }

    /**
     * Exits fullscreen mode if it's in that state.
     */
    var fullScreenMode: Boolean by Delegates.observable(false) { _, old, new ->
        notifyObservers(old, new) { onFullScreenChanged(this@Session, new) }
    }

    /**
     * [Consumable] permission request from web mContent. A [PermissionRequest]
     * must be consumed i.e. either [PermissionRequest.grant] or
     * [PermissionRequest.reject] must be called. A mContent permission request
     * can also be cancelled, which will result in a new empty [Consumable].
     */
    var contentPermissionRequest: Consumable<PermissionRequest> by Delegates.vetoable(Consumable.empty()) { _, _, request ->
        val consumers = wrapConsumers<PermissionRequest> { onContentPermissionRequested(this@Session, it) }
        !request.consumeBy(consumers)
    }

    /**
     * [Consumable] permission request for the app. A [PermissionRequest]
     * must be consumed i.e. either [PermissionRequest.grant] or
     * [PermissionRequest.reject] must be called.
     */
    var appPermissionRequest: Consumable<PermissionRequest> by Delegates.vetoable(Consumable.empty()) { _, _, request ->
        val consumers = wrapConsumers<PermissionRequest> { onAppPermissionRequested(this@Session, it) }
        !request.consumeBy(consumers)
    }

    /**
     * [Consumable] State for a prompt request from web mContent.
     */
    var promptRequest: Consumable<PromptRequest> by Delegates.vetoable(Consumable.empty()) { _, _, request ->
        val consumers = wrapConsumers<PromptRequest> { onPromptRequested(this@Session, it) }
        !request.consumeBy(consumers)
    }

    /**
     * [Consumable] request to open/create a window.
     */
    var openWindowRequest: Consumable<WindowRequest> by Delegates.vetoable(Consumable.empty()) { _, _, request ->
        val consumers = wrapConsumers<WindowRequest> { onOpenWindowRequested(this@Session, it) }
        !request.consumeBy(consumers)
    }

    /**
     * [Consumable] request to close a window.
     */
    var closeWindowRequest: Consumable<WindowRequest> by Delegates.vetoable(Consumable.empty()) { _, _, request ->
        val consumers = wrapConsumers<WindowRequest> { onCloseWindowRequested(this@Session, it) }
        !request.consumeBy(consumers)
    }

    /**
     * Returns whether or not this session is used for a Custom Tab.
     */
    fun isCustomTabSession() = customTabConfig != null

    /**
     * Helper method to notify observers.
     */
    private fun notifyObservers(old: Any, new: Any, block: Observer.() -> Unit) {
        if (old != new) {
            notifyObservers(block)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Session
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "Session($id, $url)"
    }
}
