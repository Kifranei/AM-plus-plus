package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup

/** Native 7.0 phone seams for the shared, reference phone renderer. */
interface FragmentPhoneChromePort : ChromeHostBinding {
    val contentRoot: ViewGroup
    fun eligible(): Boolean
    fun navigationFrameParams(height: Int): ViewGroup.MarginLayoutParams
    fun nativePeekBaseline(owner: Any): Int
    /** Sync an active collapse target; true only when terminal layout must finish before drawing. */
    fun reconcileCollapsedSheet(owner: Any): Boolean
    fun syncMiniPresentation(miniRoot: View?)
    fun observeGlass(callbacks: FragmentPhoneGlassCallbacks): HostSubscription
}

/** Scoped native writes; implementations never expose host members to the renderer. */
interface FragmentPhoneGlassCallbacks {
    val replacing: Boolean
    fun alpha(view: View, value: Float): Float?
    fun padding(view: View): Int?
    /** Module geometry query; unlike peek(), this is not a native height request to remember. */
    fun desiredPeek(owner: Any): Int?
    fun peek(owner: Any, value: Int): Int?
}
