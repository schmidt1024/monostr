package com.monostr.app.net

/** Nothing here can keep a process alive: a release build always ends after a direct relay route. The debug build has the test suite's seam. */
internal fun directRouteEndsProcess(used: () -> Boolean): () -> Boolean = used
