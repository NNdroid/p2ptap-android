package app.fjj.p2ptap.service

import app.fjj.p2ptap.config.P2PConfig

enum class VpnReloadPlan {
    NONE,
    HOT,
    RESTART
}

fun planVpnReload(oldConfig: P2PConfig?, newConfig: P2PConfig, forceRestart: Boolean = false): VpnReloadPlan {
    if (forceRestart) return VpnReloadPlan.RESTART
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
