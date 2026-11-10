package app.resound.ui

/** 索引仍是稳定路由ID；只有显示顺序变化，已有通知和设置跳转无需改ID。 */
internal fun shellRouteOrder(routeCount: Int): List<Int> =
    if (routeCount == 6) listOf(5, 0, 2, 1, 3, 4) else (0 until routeCount).toList()
