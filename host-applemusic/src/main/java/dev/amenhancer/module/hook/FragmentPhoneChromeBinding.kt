package dev.amenhancer.module.hook

import android.app.Activity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.host.OwnedHostProperty
import java.util.IdentityHashMap

/** Exact current-build phone contracts, cached per Fragment root. */
internal class FragmentPhoneChromeBinding(
    private val owner: Any,
    private val activity: Activity,
    override val contentRoot: ViewGroup,
    private val contract: FragmentChromeContract,
) : FragmentPhoneChromePort {
    private val names = contract.names.getJSONObject("phone")
    private val resources = names.getJSONObject("resources")
    private val ids = ChromeResource.entries.associateWith { role -> resources.optJSONObject(role.name)?.let {
        activity.resources.getIdentifier(it.getString("name"), it.getString("type"), activity.packageName)
    } ?: 0 }
    private val views = HashMap<ChromeResource, View?>()
    private val types = names.getJSONObject("views")
    private val scrollTypes = types.getJSONArray("scrollTypes").let { a -> (0 until a.length()).map(a::getString).toSet() }
    private val scrollClasses = IdentityHashMap<Class<*>, Boolean>()
    private val collapsed = contract.collapsedSheetTop
    private val peek = contract.peek
    private val autoPeek = contract.autoPeek
    private val expanded = contract.expandedSheetTop
    private val writePeek = contract.writePeek
    private val nav = checkNotNull(find(ChromeResource.BOTTOM_NAVIGATION))
    private val menu = contract.navigationMenu
    private val selected = contract.navigationSelected
    private val select = contract.navigationSelect
    private val drawerId = activity.resources.getIdentifier(names.getString("drawerQualifier"), "bool", activity.packageName)
    private val blurViews = listOf("bottomNavigationBlur", "miniBlur").mapNotNull { role ->
        activity.resources.getIdentifier(contract.resources.getString(role), "id", activity.packageName)
            .takeIf { it != 0 }?.let { contentRoot.findViewById<View>(it) }
    }
    private var callbacks: FragmentPhoneGlassCallbacks? = null
    private var closed = false
    private var writing = false
    private var mini: View? = null
    private val presentation = ArrayList<AutoCloseable>()
    private var elevation: OwnedHostProperty<Float>? = null
    private var nextPadding: OwnedHostProperty<List<Int>>? = null
    private var direction: OwnedHostProperty<Int>? = null
    private var translation: OwnedHostProperty<Float>? = null
    private var translationBaseline = 0f
    private var peekWrites = 0
    private var collapseOwner: Any? = null
    private var collapseSheet: View? = null
    private var collapseTarget = 0
    private fun extra(role: String): View? = names.getJSONObject("presentation").optString(role).takeIf { it.isNotEmpty() }
        ?.let { activity.resources.getIdentifier(it, "id", activity.packageName) }?.takeIf { it != 0 }?.let { contentRoot.findViewById(it) }

    override val pageFamily = HostPageFamily.FRAGMENT_VIEW
    override val viewSessionIdentity: Any get() = contentRoot
    override fun eligible(): Boolean {
        return FragmentPhoneGlassPolicy.eligible(android.os.Build.VERSION.SDK_INT,
            drawerId != 0 && activity.resources.getBoolean(drawerId), nav.isAttachedToWindow)
    }
    override fun regions() = PlayerRegions(nav, NavigationPlacement.BOTTOM, find(ChromeResource.MINI_PLAYER), find(ChromeResource.PLAYER_SHEET_CONTAINER), true)
    override fun resourceId(role: ChromeResource): Int = ids.getValue(role)
    override fun find(role: ChromeResource): View? = views.getOrPut(role) {
        resourceId(role).takeIf { it != 0 }?.let { contentRoot.findViewById(it) }
    }
    override fun dimension(role: ChromeResource): Int = resourceId(role).takeIf { it != 0 }
        ?.let(activity.resources::getDimensionPixelSize) ?: 0
    override fun invalidateViews() { views.clear() }
    override fun playerBehavior(preferPlayerRuntime: Boolean): Any? = contract.playerOf.invoke(owner)?.let(contract.playerBehavior::get)
    override fun sheetSnapshot(owner: Any) = NativeSheetSnapshot(contract.behaviorState.getInt(owner), collapsed.getInt(owner), (expanded.invoke(owner) as Number).toInt())
    override fun nativePeekBaseline(owner: Any): Int = if (autoPeek.getBoolean(owner)) -1 else peek.getInt(owner)
    override fun writePeek(owner: Any, height: Int) {
        peekWrites++
        try { writePeek.invoke(owner, height, false) } finally { peekWrites-- }
    }
    fun prepareSheetSettle(owner: Any, sheet: View, targetState: Int) {
        if (closed || sheet !== find(ChromeResource.PLAYER_SHEET_CONTAINER) || owner !== playerBehavior(false)) return
        clearCollapseTarget()
        if (targetState != 4 || callbacks?.replacing != true) return
        val height = callbacks?.desiredPeek(owner) ?: return
        // Do this before native P() captures G(4), rather than waiting for the next pre-draw.
        writePeek(owner, height)
        // The same-value setter skips recalculation, so refresh the native offset explicitly.
        contract.refreshCollapsedOffset.invoke(owner)
        collapseOwner = owner
        collapseSheet = sheet
        collapseTarget = collapsed.getInt(owner)
    }
    private fun clearCollapseTarget() { collapseOwner = null; collapseSheet = null }
    override fun reconcileCollapsedSheet(owner: Any): Boolean {
        if (closed || callbacks?.replacing != true) { clearCollapseTarget(); return false }
        val state = contract.behaviorState.getInt(owner)
        if (state != 2) clearCollapseTarget()
        if (state != 2 && state != 4) return false
        val sheet = find(ChromeResource.PLAYER_SHEET_CONTAINER) ?: return false
        if (!sheet.isAttachedToWindow || !sheet.isLaidOut) return false
        val parent = sheet.parent as? View ?: return false
        val target = collapsed.getInt(owner)
        if (target !in 0..parent.height) return false
        if (state == 2) {
            // Layout/insets can change collapsedTop after the native animation has started.
            // Only retarget a known native collapse, and only when its endpoint changed.
            if (collapseOwner === owner && collapseSheet === sheet && kotlin.math.abs(collapseTarget - target) > 1) {
                contract.settleSheet.invoke(owner, sheet, 4, false)
            }
            return false
        }
        if (kotlin.math.abs(sheet.top - target) <= 1) return false
        // A peek update during settling changes collapsedTop, but not the drag helper's
        // captured destination. The same-value peek setter is a no-op after it settles.
        // Let native onLayout apply the latest target; never move a running gesture.
        if (!sheet.isLayoutRequested) sheet.requestLayout()
        return true
    }
    override fun navigation(view: View) = NativeNavigationSnapshot(menu.invoke(view) as Menu, selected.invoke(view) as Int)
    override fun selectNavigation(view: View, id: Int) { select.invoke(view, id) }
    override fun isScrollContainer(view: View) = scrollClasses.getOrPut(view.javaClass) {
        generateSequence(view.javaClass as Class<*>?) { it.superclass }.any { it.name in scrollTypes }
    }
    override fun isComposeScene(view: View) = view.javaClass.name == types.getString("composeType")
    override fun isPagerPageHost(view: View) = (view.parent as? View)?.javaClass?.name == types.getString("pagerType")
    override fun navigationFrameParams(height: Int) = ConstraintLayoutPane.newBottomNavigationFrameParams(nav.layoutParams, height)
    override fun observeGlass(callbacks: FragmentPhoneGlassCallbacks): HostSubscription {
        check(!closed && this.callbacks == null)
        this.callbacks = callbacks
        return HostSubscription { if (this.callbacks === callbacks) this.callbacks = null }
    }
    fun alpha(view: View, value: Float): Float? = if (closed || writing) null else callbacks?.alpha(view, value)
    fun padding(view: View): Int? = if (closed || writing) null else callbacks?.padding(view)
    fun peek(owner: Any, value: Int): Int? = if (closed || peekWrites > 0) null else callbacks?.peek(owner, value)
    fun replacesBlur(view: View): Boolean = !closed && callbacks?.replacing == true && blurViews.any { it === view }

    override fun syncMiniPresentation(miniRoot: View?) {
        val content = find(ChromeResource.MINI_PLAYER_CONTENT) ?: return
        writing = true
        try {
            if (content !== mini) {
                restorePresentation()
                mini = content
                elevation = OwnedHostProperty({ content.elevation }, { content.elevation = it }).also(presentation::add)
                find(ChromeResource.MINI_PLAYER_NEXT_BTN)?.let { next ->
                    nextPadding = OwnedHostProperty({ listOf(next.paddingLeft, next.paddingTop, next.paddingRight, next.paddingBottom) },
                        { next.setPadding(it[0], it[1], it[2], it[3]) }).also(presentation::add)
                }
                extra("buttons")?.let { buttons ->
                    direction = OwnedHostProperty({ buttons.layoutDirection }, { buttons.layoutDirection = it }).also(presentation::add)
                }
                miniRoot?.let { root ->
                    translationBaseline = root.translationY
                    translation = OwnedHostProperty({ root.translationY }, { root.translationY = it }).also(presentation::add)
                }
            }
            elevation?.set(0f)
            nextPadding?.set(listOf(0, 0, 0, 0))
            direction?.set(View.LAYOUT_DIRECTION_LTR)
            translation?.set(translationBaseline + dimension(ChromeResource.SHADOW_HEIGHT))
        } finally { writing = false }
    }
    private fun restorePresentation() {
        presentation.forEach(AutoCloseable::close); presentation.clear()
        mini = null; elevation = null; nextPadding = null; direction = null; translation = null
    }
    override fun close() {
        if (closed) return
        closed = true; callbacks = null
        clearCollapseTarget()
        restorePresentation(); views.clear(); scrollClasses.clear()
    }
}
