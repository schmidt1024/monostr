package com.monostr.app

import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Espresso's back press, which waits up to 10 s for a root with window focus. In full runs it sometimes finds none (backlog 119,
 * SettingsOverviewTest and LinkPromptTest, green alone): when the system's focus is in our own window all the same, the key is sent
 * through the system instead; otherwise the failure says which window had the focus.
 */
fun pressBackSayingFocus() {
    try {
        Espresso.pressBack()
    } catch (e: RuntimeException) {
        if (e.javaClass.simpleName != "RootViewWithoutFocusException") throw e
        val focus = runCatching {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window displays")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().useLines { lines ->
                lines.filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mInputMethodWindow" in it }.joinToString("; ") { it.trim() }
            }
        }.getOrElse { "unread: $it" }
        // 2026-11-27, full run: the system had its focus in our own window (the dialog's has the activity's name) while no root
        // of ours had heard of it. The key then goes the system's way, to the window it has focused; anything else stays a failure
        if ("mCurrentFocus" !in focus || InstrumentationRegistry.getInstrumentation().targetContext.packageName !in focus.substringBefore(";"))
            throw AssertionError("no focused root for the back press; the system says: $focus", e)
        android.util.Log.w("TestBack", "no focused root for Espresso, back key sent through the system: $focus")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
        Thread.sleep(500)
    }
}
