package dev.amenhancer.module.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HyperOS SystemUI-side unblock for media-notification CustomActions.
 *
 * HyperOS keeps a CustomAction denylist on `NotificationSettingsManager.mHiddenCustomActionsList`
 * (+ `...Local`). The app-side [HyperOsMediaActionsFeature] publishes well-formed actions, but
 * SystemUI still strips them while the list is populated. This hook empties the list right before
 * SystemUI builds actions from a PlaybackState. Requires LSPosed scope `com.android.systemui`.
 *
 * SystemUI cannot read Apple Music's private settings, so this side follows the scope selection
 * rather than the in-app toggle. Ported from the user-verified SpotifyPlus implementation.
 */
internal object SystemUiMediaActionsHook {
    const val SYSTEM_UI = "com.android.systemui"

    private val MANAGER_CLASSES = listOf(
        "com.miui.systemui.notification.NotificationSettingsManager",
        "com.android.systemui.statusbar.notification.NotificationSettingsManager",
        "com.miui.systemui.statusbar.notification.NotificationSettingsManager",
        "com.android.systemui.statusbar.notification.row.NotificationSettingsManager",
        "com.miui.systemui.notification.MiuiNotificationSettingsManager",
    )

    private val ACTION_PIPELINE_HOOKS = listOf(
        "com.android.systemui.media.controls.pipeline.MediaDataManager" to "createActionsFromState",
        "com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl" to "createActionsFromState",
        "com.android.systemui.media.MediaDataManager" to "createActionsFromState",
        "com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl\$createActionsFromState\$customActions\$1" to "invoke",
        "com.android.systemui.media.controls.domain.pipeline.MediaActionsKt\$\$ExternalSyntheticLambda0" to "invoke",
        "com.android.systemui.media.controls.pipeline.MediaDataManager\$createActionsFromState\$customActions\$1" to "invoke",
    )

    private val HIDDEN_LIST_FIELDS = listOf("mHiddenCustomActionsList", "mHiddenCustomActionsListLocal")

    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        HyperOsActionsLog.i(
            "SystemUI install start; managers present=" +
                MANAGER_CLASSES.filter { findClass(it, classLoader) != null } +
                " mediaPipelines present=" +
                ACTION_PIPELINE_HOOKS.map { it.first }.distinct().filter { findClass(it, classLoader) != null },
        )
        var hooked = 0
        for ((className, methodName) in ACTION_PIPELINE_HOOKS) {
            val cls = findClass(className, classLoader) ?: continue
            val methods = cls.declaredMethods.filter { it.name == methodName }
            for (m in methods) {
                runCatching {
                    ModernXposedRuntime.hookMethod(m, object : ModernMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            clearHiddenLists(classLoader)
                        }
                    })
                    hooked++
                }.onFailure { HyperOsActionsLog.e("SystemUI hook $className#$methodName failed", it) }
            }
        }
        clearHiddenLists(classLoader)
        hooked += hookManagerCheckMethods(classLoader)
        HyperOsActionsLog.i("SystemUI install done pipelineHooks=$hooked managerResolved=${resolveManager(classLoader) != null}")
    }

    private fun hookManagerCheckMethods(classLoader: ClassLoader): Int {
        var n = 0
        for (name in MANAGER_CLASSES) {
            val cls = findClass(name, classLoader) ?: continue
            for (m in cls.declaredMethods) {
                if (!m.name.contains("CustomAction", true) && !m.name.contains("HiddenCustom", true)) continue
                runCatching {
                    ModernXposedRuntime.hookMethod(m, object : ModernMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            clearHiddenLists(classLoader)
                            when {
                                m.returnType == java.lang.Boolean.TYPE -> param.result = false
                                List::class.java.isAssignableFrom(m.returnType) -> param.result = ArrayList<Any>()
                            }
                        }
                    })
                    n++
                }
            }
        }
        return n
    }

    @Volatile private var clearLogs = 0

    fun clearHiddenLists(classLoader: ClassLoader) {
        val manager = resolveManager(classLoader)
        if (manager == null) {
            if (clearLogs++ < 5) HyperOsActionsLog.i("clearHiddenLists: NotificationSettingsManager instance not found")
            return
        }
        var cleared = 0
        var c: Class<*>? = manager.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val n = f.name
                val hit = n in HIDDEN_LIST_FIELDS ||
                    (n.contains("hidden", true) && n.contains("action", true))
                if (!hit || Modifier.isStatic(f.modifiers) && f.type.isPrimitive) continue
                runCatching {
                    f.isAccessible = true
                    val before = (f.get(manager) as? Collection<*>)?.size
                    when (val cur = f.get(manager)) {
                        is MutableCollection<*> -> cur.clear()
                        else -> if (Collection::class.java.isAssignableFrom(f.type) || f.type == Any::class.java) {
                            f.set(manager, ArrayList<Any>())
                        }
                    }
                    cleared++
                    if (clearLogs++ < 8) HyperOsActionsLog.i("cleared ${c!!.name}#$n (was size=$before)")
                }.onFailure { if (clearLogs++ < 8) HyperOsActionsLog.e("clear ${f.name} failed", it) }
            }
            c = c.superclass
        }
        if (cleared == 0 && clearLogs++ < 8) {
            HyperOsActionsLog.i(
                "manager ${manager.javaClass.name} has no hidden-action field; fields=" +
                    manager.javaClass.declaredFields.map { it.name }.take(60),
            )
        }
    }

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun resolveManager(classLoader: ClassLoader): Any? {
        for (name in MANAGER_CLASSES) {
            val cls = findClass(name, classLoader) ?: continue
            runCatching {
                val f = cls.getDeclaredField("sINSTANCE")
                f.isAccessible = true
                f.get(null)?.let { return it }
            }
            for (m in cls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty() &&
                    (m.name == "getInstance" || m.name == "get") && cls.isAssignableFrom(m.returnType)
                ) {
                    runCatching {
                        m.isAccessible = true
                        m.invoke(null)?.let { return it }
                    }
                }
            }
        }
        val dep = findClass("com.android.systemui.Dependency", classLoader)
        if (dep != null) {
            for (name in MANAGER_CLASSES) {
                val mgrCls = findClass(name, classLoader) ?: continue
                runCatching {
                    val get = dep.methods.firstOrNull {
                        it.name == "get" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Class::class.java
                    } ?: return@runCatching
                    get.isAccessible = true
                    get.invoke(null, mgrCls)?.let { return it }
                }
            }
        }
        return null
    }
}
