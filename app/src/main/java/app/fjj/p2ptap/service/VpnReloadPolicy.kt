package app.fjj.p2ptap.service

import app.fjj.p2ptap.config.P2PConfig

internal enum class VpnReloadPlan {
    NONE,
    HOT,
    RESTART
}

internal fun planVpnReload(oldConfig: P2PConfig?, newConfig: P2PConfig): VpnReloadPlan {
    if (oldConfig == null) return VpnReloadPlan.RESTART

    val exitModeChanged = oldConfig.exitNode.isBlank() != newConfig.exitNode.isBlank()
    if (exitModeChanged) return VpnReloadPlan.RESTART

    val nonHotConfigChanged = oldConfig.copy(
        exitNode = newConfig.exitNode,
        logLevel = newConfig.logLevel
    ) != newConfig
    if (nonHotConfigChanged) return VpnReloadPlan.RESTART

    return if (oldConfig.exitNode != newConfig.exitNode || oldConfig.logLevel != newConfig.logLevel) {
        VpnReloadPlan.HOT
    } else {
        VpnReloadPlan.NONE
    }
}
